package com.glasspay.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GlassPayApp() }
    }
}

@androidx.compose.runtime.Composable
private fun GlassPayApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val api = remember { PaymentApi() }
    val stateMachine = remember { ResolveStateMachine() }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var uiState by remember { mutableStateOf<ResolveState>(ResolveState.Scanning) }
    var attemptCounter by remember { mutableStateOf(0L) }
    var serverReady by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var rejectedToken by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (!granted) cameraError = "Necesitamos la cámara para leer el QR."
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
        launch {
            try {
                api.warmUp()
                serverReady = true
            } catch (exception: ApiException) {
                Log.i("GlassPay", "health failure type=${exception.type} httpCode=${exception.httpCode}")
            }
        }
    }

    fun dispatch(event: ResolveEvent) {
        uiState = stateMachine.dispatch(event)
    }

    fun cancelActiveRequest() {
        activeJob?.cancel()
        activeJob = null
    }

    fun resolveToken(token: String) {
        if (uiState is ResolveState.Identified) return
        if (uiState is ResolveState.Failed) return
        if (token == rejectedToken) return
        if (token != rejectedToken) rejectedToken = null
        if (uiState is ResolveState.Resolving &&
            (uiState as ResolveState.Resolving).token == token
        ) {
            return
        }
        cancelActiveRequest()
        attemptCounter += 1
        val attemptId = attemptCounter
        val waking = !serverReady
        dispatch(ResolveEvent.Begin(attemptId, token, waking))
        activeJob = scope.launch {
            try {
                val resolution = api.resolveQrToken(token, if (waking) 60_000 else 15_000)
                serverReady = true
                dispatch(
                    ResolveEvent.Success(
                        attemptId,
                        resolution.displayName,
                        resolution.resolutionId,
                        resolution.expiresAt,
                    ),
                )
                vibrate(context)
            } catch (exception: ApiException) {
                if (isActive) {
                    Log.i(
                        "GlassPay",
                        "resolve attempt=$attemptId failure type=${exception.type} httpCode=${exception.httpCode}",
                    )
                    if (exception.type == ApiFailureType.NOT_FOUND) rejectedToken = token
                    dispatch(ResolveEvent.Failure(attemptId, exception.type.toResolveFailureType()))
                }
            } catch (_: Exception) {
                if (isActive) {
                    Log.i("GlassPay", "resolve attempt=$attemptId failure type=UNKNOWN httpCode=none")
                    dispatch(ResolveEvent.Failure(attemptId, ResolveFailureType.NETWORK))
                }
            } finally {
                if (isActive) activeJob = null
            }
        }
    }

    LaunchedEffect(uiState) {
        val identified = uiState as? ResolveState.Identified ?: return@LaunchedEffect
        val expiresAt = runCatching { Instant.parse(identified.expiresAt) }.getOrNull() ?: return@LaunchedEffect
        val remaining = expiresAt.toEpochMilli() - System.currentTimeMillis()
        if (remaining > 0) {
            delay(remaining)
        }
        if (stateMachine.state == identified) {
            cancelActiveRequest()
            dispatch(ResolveEvent.Reset)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1020))) {
        if (cameraGranted && uiState !is ResolveState.Identified) {
            CameraPreview(
                lifecycleOwner = lifecycleOwner,
                onTokenDetected = ::resolveToken,
            )
        }

        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when (val state = uiState) {
                    is ResolveState.Identified -> "Pagador identificado"
                    is ResolveState.Resolving -> if (state.waking) "Despertando el servidor…" else "Resolviendo QR…"
                    else -> "Busca un QR de GlassPay"
                },
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
            if (uiState is ResolveState.Resolving && (uiState as ResolveState.Resolving).waking) {
                CircularProgressIndicator()
            }
            (uiState as? ResolveState.Identified)?.let { identified ->
                Text("Pagador: ${identified.displayName}", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Button(onClick = {
                    cancelActiveRequest()
                    dispatch(ResolveEvent.Reset)
                }) {
                    Text("Escanear otro QR")
                }
            }
            (uiState as? ResolveState.RetryableError)?.let { retryable ->
                Text(retryable.message, color = Color(0xFFFFB4AB))
                Button(onClick = { resolveToken(retryable.token) }) {
                    Text("Reintentar")
                }
            }
            (uiState as? ResolveState.Failed)?.let { failed ->
                Text(failed.message, color = Color(0xFFFFB4AB))
            }
            cameraError?.let { Text(it, color = Color(0xFFFFB4AB)) }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CameraPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onTokenDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val latestCallback = androidx.compose.runtime.rememberUpdatedState(onTokenDetected)

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { imageProxy ->
            analyzeFrame(imageProxy, scanner) { token -> latestCallback.value(token) }
        }
        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun analyzeFrame(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onTokenDetected: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            barcodes.firstNotNullOfOrNull { QrTokenParser.extractToken(it.rawValue) }
                ?.let(onTokenDetected)
        }
        .addOnCompleteListener { imageProxy.close() }
}

private fun vibrate(context: android.content.Context) {
    val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
}

private fun ApiFailureType.toResolveFailureType(): ResolveFailureType {
    return when (this) {
        ApiFailureType.NETWORK -> ResolveFailureType.NETWORK
        ApiFailureType.TIMEOUT -> ResolveFailureType.TIMEOUT
        ApiFailureType.NOT_FOUND -> ResolveFailureType.NOT_FOUND
        ApiFailureType.RATE_LIMITED -> ResolveFailureType.RATE_LIMITED
        ApiFailureType.SERVER -> ResolveFailureType.SERVER
        ApiFailureType.INVALID_RESPONSE -> ResolveFailureType.INVALID_RESPONSE
    }
}
