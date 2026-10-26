package com.glasspay.android

import android.util.Log
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CREATE_ACCOUNT_ERROR =
    "No pudimos crear tu cuenta. Revisa tu conexión e inténtalo de nuevo."

@Composable
fun WelcomeScreen(
    api: PaymentApi,
    message: String?,
    onAccountCreated: (CollectorSession) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var waking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun createAccount() {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || creating) return
        creating = true
        waking = false
        error = null
        val wakingTimer = scope.launch {
            delay(3_000)
            waking = true
        }
        scope.launch {
            try {
                onAccountCreated(api.createCollector(trimmed))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                Log.i("GlassPay", "createCollector failure type=${exception.type} httpCode=${exception.httpCode}")
                error = CREATE_ACCOUNT_ERROR
            } catch (_: Exception) {
                Log.i("GlassPay", "createCollector failure type=UNKNOWN httpCode=none")
                error = CREATE_ACCOUNT_ERROR
            } finally {
                wakingTimer.cancel()
                creating = false
                waking = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        IconBadge(R.drawable.ic_charge, tint = GlassPayColors.OnAccent, size = 72.dp, background = GlassPayColors.Accent)
        Text("GlassPay", style = MaterialTheme.typography.headlineLarge, color = GlassPayColors.Text)
        Text(
            "Cobra a tus amigos con la voz y un QR.",
            style = MaterialTheme.typography.bodyLarge,
            color = GlassPayColors.MutedText,
        )
        (error ?: message)?.let { InlineNotice(it, kind = StatusKind.FAILURE) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text("Tu nombre") },
            supportingText = { Text("Es lo que verán tus amigos al cobrarles.") },
            singleLine = true,
            enabled = !creating,
            shape = MaterialTheme.shapes.medium,
            colors = gpTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        GpButton(
            if (creating) "Creando tu cuenta…" else "Crear mi cuenta",
            onClick = { createAccount() },
            enabled = name.isNotBlank() && !creating,
            modifier = Modifier.fillMaxWidth(),
        )
        if (waking) InlineNotice("Despertando el servidor… la primera vez tarda unos segundos.", kind = StatusKind.PENDING)
        Text(
            "Actividad en GlassPay (Sandbox): no se mueve dinero real.",
            style = MaterialTheme.typography.bodySmall,
            color = GlassPayColors.MutedText,
        )
    }
}
