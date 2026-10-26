package com.glasspay.android

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Loaded<T>(val value: T) : LoadState<T>
    data object Failed : LoadState<Nothing>
}

@Composable
fun HomeScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
    onCharge: () -> Unit,
) {
    var reload by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<LoadState<CollectorProfile>>(LoadState.Loading) }

    LaunchedEffect(reload) {
        state = LoadState.Loading
        try {
            state = LoadState.Loaded(api.getCollectorProfile(session))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            Log.i("GlassPay", "profile failure type=${exception.type} httpCode=${exception.httpCode}")
            if (isSessionInvalid(exception.type)) {
                onSessionInvalid(SESSION_INVALID_MESSAGE)
            } else {
                state = LoadState.Failed
            }
        } catch (_: Exception) {
            Log.i("GlassPay", "profile failure type=UNKNOWN httpCode=none")
            state = LoadState.Failed
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Hola, ${session.displayName}", style = MaterialTheme.typography.headlineMedium)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Actividad en GlassPay (Sandbox)", style = MaterialTheme.typography.titleMedium)
                when (val current = state) {
                    LoadState.Loading -> CircularProgressIndicator()
                    LoadState.Failed -> {
                        Text("No pudimos cargar tu actividad.", color = GlassPayColors.Error)
                        Button(onClick = { reload += 1 }) { Text("Reintentar") }
                    }
                    is LoadState.Loaded -> {
                        if (current.value.activity.isEmpty()) {
                            Text("Aún no has cobrado. Empieza con Cobrar.", color = GlassPayColors.MutedText)
                        }
                        current.value.activity.forEach { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    formatMoney(item.paidTotal, item.currency),
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = GlassPayColors.Success,
                                )
                                Text("${item.paidCount} pagos recibidos", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${item.inProgressCount} en proceso",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = GlassPayColors.Warning,
                                )
                            }
                        }
                        OutlinedButton(onClick = { reload += 1 }) { Text("Actualizar") }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCharge) { Text("Cobrar") }
            Button(onClick = {}, enabled = false) { Text("Enviar") }
        }
        Text("Enviar: Próximamente", color = GlassPayColors.MutedText)
    }
}
