package com.glasspay.android

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("GlassPay", style = MaterialTheme.typography.displaySmall)
        Text(
            "Cobra a tus amigos con la voz y un QR.",
            style = MaterialTheme.typography.titleMedium,
            color = GlassPayColors.MutedText,
        )
        (error ?: message)?.let {
            Text(it, color = GlassPayColors.Error, style = MaterialTheme.typography.bodyLarge)
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text("Tu nombre") },
            singleLine = true,
            enabled = !creating,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { createAccount() },
            enabled = name.isNotBlank() && !creating,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Crear mi cuenta") }
        if (creating) CircularProgressIndicator()
        if (waking) Text("Despertando el servidor…", color = GlassPayColors.Warning)
    }
}
