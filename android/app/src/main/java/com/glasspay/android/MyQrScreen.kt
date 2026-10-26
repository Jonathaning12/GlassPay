package com.glasspay.android

import android.util.Log
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Mi QR", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Muéstralo para que te agreguen o te cobren.",
                style = MaterialTheme.typography.bodyLarge,
                color = GlassPayColors.MutedText,
                textAlign = TextAlign.Center,
            )
            val current = qr
            if (current != null && error == null) {
                val bitmap = remember(current.token) { qrMatrix(current.token, 560).toImageBitmap() }
                Column(
                    modifier = Modifier
                        .background(GlassPayColors.QrPaper, MaterialTheme.shapes.large)
                        .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Tu código QR de GlassPay",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(260.dp),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(session.displayName, style = MaterialTheme.typography.headlineSmall, color = QrInk)
                        Text("Sin datos personales en el código", style = MaterialTheme.typography.bodySmall, color = QrInkMuted)
                    }
                }
                Row(
                    modifier = Modifier
                        .background(GlassPayColors.Card, RoundedCornerShape(50))
                        .border(1.dp, GlassPayColors.Line, RoundedCornerShape(50))
                        .padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        progress = { (secondsLeft / QR_REFERENCE_SECONDS).coerceIn(0f, 1f) },
                        color = GlassPayColors.Accent,
                        trackColor = GlassPayColors.Raised,
                        strokeWidth = 3.dp,
                        gapSize = 0.dp,
                        modifier = Modifier.size(22.dp),
                    )
                    Text("Se renueva en ${secondsLeft} s", color = GlassPayColors.Text, style = MaterialTheme.typography.labelLarge)
                }
            } else if (error != null) {
                InlineNotice(error ?: "", kind = StatusKind.FAILURE)
                GpButton(
                    "Reintentar",
                    onClick = {
                        error = null
                        qr = null
                        reload += 1
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                CircularProgressIndicator(
                    color = GlassPayColors.Accent,
                    modifier = Modifier.padding(top = 48.dp).semantics { contentDescription = "Generando tu QR" },
                )
            }
        }
    }
}

private val QrInk = Color(0xFF0A0D14)
private val QrInkMuted = Color(0xFF5B6578)

// Vida aproximada de un QR; solo dimensiona el anillo, la renovación la decide el servidor.
private const val QR_REFERENCE_SECONDS = 300f
