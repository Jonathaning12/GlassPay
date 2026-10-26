package com.glasspay.android

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PayoutEmailScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    fun save() {
        if (saving) return
        if (!isValidPayoutEmail(email)) {
            message = "Escribe un correo válido."
            return
        }
        saving = true
        message = null
        scope.launch {
            try {
                api.setPayoutEmail(session, email.trim())
                onBack()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                Log.i("GlassPay", "payoutEmail failure type=${exception.type} httpCode=${exception.httpCode}")
                message = when (exception.type) {
                    ApiFailureType.UNAUTHORIZED -> {
                        onSessionInvalid(SESSION_INVALID_MESSAGE)
                        null
                    }
                    ApiFailureType.BAD_REQUEST -> "Ese correo no es válido."
                    ApiFailureType.RATE_LIMITED -> "Demasiados intentos. Espera unos segundos."
                    else -> "No pudimos guardar tu correo. Revisa tu conexión."
                }
            } catch (_: Exception) {
                Log.i("GlassPay", "payoutEmail failure type=UNKNOWN httpCode=none")
                message = "No pudimos guardar tu correo. Revisa tu conexión."
            } finally {
                saving = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Recibir envíos", onBack = onBack)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconBadge(R.drawable.ic_mail, tint = GlassPayColors.Accent, size = 56.dp, background = GlassPayColors.AccentSoft)
            Text(
                "Escribe el correo de tu cuenta de PayPal Sandbox.",
                style = MaterialTheme.typography.titleMedium,
                color = GlassPayColors.Text,
            )
            InlineNotice(
                "Tu correo se guarda solo en el servidor de GlassPay y nadie más lo ve.",
                icon = R.drawable.ic_lock,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(254) },
                label = { Text("Correo de PayPal Sandbox") },
                singleLine = true,
                enabled = !saving,
                isError = message != null,
                supportingText = message?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = MaterialTheme.shapes.medium,
                colors = gpTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            GpButton(
                if (saving) "Guardando…" else "Guardar",
                onClick = { save() },
                enabled = email.isNotBlank() && !saving,
                icon = R.drawable.ic_check,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
