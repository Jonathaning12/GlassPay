package com.glasspay.android

import android.Manifest
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface AddContactState {
    data object Scanning : AddContactState
    data object Working : AddContactState
    data class Result(val message: String, val isError: Boolean) : AddContactState
}

@Composable
fun AddContactScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    var state by remember { mutableStateOf<AddContactState>(AddContactState.Scanning) }
    var lastToken by remember { mutableStateOf<String?>(null) }

    BackHandler { onDone() }

    fun finish(message: String, isError: Boolean) {
        state = AddContactState.Result(message, isError)
    }

    fun handleToken(token: String) {
        if (state != AddContactState.Scanning || token == lastToken) return
        lastToken = token
        state = AddContactState.Working
        scope.launch {
            try {
                val resolution = api.resolveQrToken(session, token, 15_000)
                if (resolution.contactId != null) {
                    finish("${resolution.displayName} ya está en tus contactos", false)
                } else {
                    api.saveContact(session, resolution.resolutionId)
                    vibrate(context)
                    finish("${resolution.displayName} se agregó a tus contactos", false)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                Log.i("GlassPay", "addContact failure type=${exception.type} httpCode=${exception.httpCode}")
                when (exception.type) {
                    ApiFailureType.UNAUTHORIZED -> onSessionInvalid(SESSION_INVALID_MESSAGE)
                    ApiFailureType.NOT_FOUND -> finish("Ese QR ya no es válido. Pide que lo actualicen.", true)
                    ApiFailureType.CONFLICT -> finish("Ese es tu propio QR.", true)
                    ApiFailureType.RATE_LIMITED -> finish("Demasiados intentos. Espera unos segundos.", true)
                    else -> finish("Revisa tu conexión.", true)
                }
            } catch (_: Exception) {
                Log.i("GlassPay", "addContact failure type=UNKNOWN httpCode=none")
                finish("Revisa tu conexión.", true)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(GlassPayColors.Background)) {
        when (val current = state) {
            AddContactState.Scanning -> {
                if (cameraGranted) {
                    CameraScanner(lifecycleOwner = lifecycleOwner, onTokenDetected = ::handleToken)
                    Box(Modifier.fillMaxSize().background(GlassPayColors.Background.copy(alpha = 0.35f)))
                    ScanFrame(modifier = Modifier.align(Alignment.Center))
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(20.dp)
                            .fillMaxWidth()
                            .background(GlassPayColors.Card.copy(alpha = 0.94f), MaterialTheme.shapes.large)
                            .border(1.dp, GlassPayColors.Line, MaterialTheme.shapes.large)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Agregar contacto", color = GlassPayColors.Text, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Apunta al QR de tu amigo. Solo se guarda su nombre visible.",
                            color = GlassPayColors.MutedText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        IconBadge(R.drawable.ic_charge, tint = GlassPayColors.Accent, size = 72.dp, background = GlassPayColors.AccentSoft)
                        Text(
                            "Necesitamos la cámara para leer el QR.",
                            style = MaterialTheme.typography.titleMedium,
                            color = GlassPayColors.Text,
                            textAlign = TextAlign.Center,
                        )
                        GpButton("Permitir cámara", onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth())
                    }
                }
                CircleIconButton(
                    R.drawable.ic_back,
                    "Volver",
                    onDone,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                )
            }
            AddContactState.Working -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = GlassPayColors.Accent)
                Text("Agregando contacto…", color = GlassPayColors.Text, style = MaterialTheme.typography.titleMedium)
            }
            is AddContactState.Result -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val kind = if (current.isError) StatusKind.FAILURE else StatusKind.SUCCESS
                IconBadge(
                    if (current.isError) R.drawable.ic_close else R.drawable.ic_check,
                    tint = statusColor(kind),
                    size = 88.dp,
                    background = statusSoftColor(kind),
                )
                Text(
                    current.message,
                    style = MaterialTheme.typography.headlineSmall,
                    color = statusColor(kind),
                    textAlign = TextAlign.Center,
                )
                GpButton(
                    "Agregar otro",
                    onClick = {
                        if (current.isError) lastToken = null
                        state = AddContactState.Scanning
                    },
                    icon = R.drawable.ic_qr,
                    modifier = Modifier.fillMaxWidth(),
                )
                GpButton("Listo", onClick = onDone, kind = GpButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
