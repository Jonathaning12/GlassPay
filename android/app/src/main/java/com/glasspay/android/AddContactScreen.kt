package com.glasspay.android

import android.Manifest
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            AddContactState.Scanning -> {
                if (cameraGranted) {
                    CameraScanner(lifecycleOwner = lifecycleOwner, onTokenDetected = ::handleToken)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xCC161D36),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                    ) {
                        Text(
                            "Apunta al QR de tu amigo",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Necesitamos la cámara para leer el QR.", style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Permitir cámara")
                        }
                    }
                }
                OutlinedButton(
                    onClick = onDone,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                ) { Text("Volver") }
            }
            AddContactState.Working -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text("Agregando contacto…")
            }
            is AddContactState.Result -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    current.message,
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (current.isError) GlassPayColors.Error else GlassPayColors.Success,
                )
                Button(onClick = {
                    if (current.isError) lastToken = null
                    state = AddContactState.Scanning
                }) { Text("Agregar otro") }
                OutlinedButton(onClick = onDone) { Text("Listo") }
            }
        }
    }
}
