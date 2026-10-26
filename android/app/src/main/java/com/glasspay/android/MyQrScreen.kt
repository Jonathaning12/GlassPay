package com.glasspay.android

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val RENEW_BEFORE_SECONDS = 30L

@Composable
fun MyQrScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
    onBack: () -> Unit,
    locked: Boolean,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var qr by remember { mutableStateOf<MyQr?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var secondsLeft by remember { mutableStateOf(0L) }
    var reload by remember { mutableStateOf(0) }

    BackHandler { onBack() }

    // Brillo al máximo mientras el QR está visible.
    DisposableEffect(locked) {
        if (locked) return@DisposableEffect onDispose { }
        val window = context.findActivity()?.window
        val previous = window?.attributes?.screenBrightness
        window?.let { it.attributes = it.attributes.apply { screenBrightness = 1f } }
        onDispose {
            if (window != null && previous != null) {
                window.attributes = window.attributes.apply { screenBrightness = previous }
            }
        }
    }

    LaunchedEffect(reload, locked) {
        if (locked) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val current = qr
                if (current == null || remainingSeconds(current.expiresAt) <= RENEW_BEFORE_SECONDS) {
                    try {
                        qr = api.issueMyQr(session)
                        error = null
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: ApiException) {
                        Log.i("GlassPay", "myQr failure type=${exception.type} httpCode=${exception.httpCode}")
                        if (isSessionInvalid(exception.type)) {
                            onSessionInvalid(SESSION_INVALID_MESSAGE)
                        } else if (exception.type == ApiFailureType.RATE_LIMITED) {
                            error = "Demasiados intentos. Espera unos segundos."
                        } else if (qr == null || remainingSeconds(qr!!.expiresAt) <= 0) {
                            error = "No pudimos generar tu QR."
                        }
                        if (error != null) return@repeatOnLifecycle
                        // Fallo de red: espera antes de reintentar, no cada segundo.
                        delay(5_000)
                        continue
                    } catch (_: Exception) {
                        Log.i("GlassPay", "myQr failure type=UNKNOWN httpCode=none")
                        error = "No pudimos generar tu QR."
                        return@repeatOnLifecycle
                    }
                }
                qr?.let { secondsLeft = remainingSeconds(it.expiresAt) }
                delay(1_000)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) { Text("Volver") }
        Text(session.displayName, style = MaterialTheme.typography.headlineMedium)
        val current = qr
        if (current != null && error == null) {
            val bitmap = remember(current.token) { qrMatrix(current.token, 560).toImageBitmap() }
            Box(
                modifier = Modifier
                    .background(Color.White, RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Tu código QR de GlassPay",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(280.dp),
                )
            }
            Text(
                "Muéstralo para que te agreguen o te cobren.",
                style = MaterialTheme.typography.bodyLarge,
                color = GlassPayColors.MutedText,
            )
            Text("Se renueva en ${secondsLeft}s", color = GlassPayColors.Warning)
        } else if (error != null) {
            Text(error ?: "", color = GlassPayColors.Error, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = {
                error = null
                qr = null
                reload += 1
            }) { Text("Reintentar") }
        } else {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Generando tu QR" })
        }
    }
}
