package com.glasspay.android

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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

@Composable
fun MovementsScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
) {
    var reload by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<LoadState<List<Movement>>>(LoadState.Loading) }
    var selected by remember { mutableStateOf<Movement?>(null) }

    LaunchedEffect(reload) {
        state = LoadState.Loading
        try {
            state = LoadState.Loaded(api.getMovements(session))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            Log.i("GlassPay", "movements failure type=${exception.type} httpCode=${exception.httpCode}")
            if (isSessionInvalid(exception.type)) {
                onSessionInvalid(SESSION_INVALID_MESSAGE)
            } else {
                state = LoadState.Failed
            }
        } catch (_: Exception) {
            Log.i("GlassPay", "movements failure type=UNKNOWN httpCode=none")
            state = LoadState.Failed
        }
    }

    BackHandler(enabled = selected != null) { selected = null }

    val detail = selected
    if (detail != null) {
        MovementDetail(detail, onBack = { selected = null })
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Movimientos", style = MaterialTheme.typography.headlineMedium)
        Text("Actividad en GlassPay (Sandbox)", color = GlassPayColors.MutedText)
        when (val current = state) {
            LoadState.Loading -> CircularProgressIndicator()
            LoadState.Failed -> {
                Text("No pudimos cargar tus movimientos.", color = GlassPayColors.Error)
                Button(onClick = { reload += 1 }) { Text("Reintentar") }
            }
            is LoadState.Loaded -> {
                OutlinedButton(onClick = { reload += 1 }) { Text("Actualizar") }
                if (current.value.isEmpty()) {
                    Text("Aún no tienes movimientos.", color = GlassPayColors.MutedText)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(current.value, key = { it.movementId }) { movement ->
                        MovementCard(movement, onClick = { selected = movement })
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(state: String) {
    val color = statusColor(movementStatusKind(state))
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.2f)) {
        Text(
            movementStatusLabel(state),
            color = color,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun MovementCard(movement: Movement, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(movement.concept, style = MaterialTheme.typography.titleMedium)
            Text("a ${movement.payerName}", color = GlassPayColors.MutedText)
            Text(formatMoney(movement.amount, movement.currency), style = MaterialTheme.typography.titleLarge)
            StatusChip(movement.state)
            Text(formatMovementDate(movement.createdAt), color = GlassPayColors.MutedText)
        }
    }
}

@Composable
private fun MovementDetail(movement: Movement, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onBack) { Text("Volver") }
        Text(
            formatMoney(movement.amount, movement.currency),
            style = MaterialTheme.typography.displaySmall,
        )
        StatusChip(movement.state)
        Text("Pagador: ${movement.payerName}", style = MaterialTheme.typography.bodyLarge)
        Text("Concepto: ${movement.concept}", style = MaterialTheme.typography.bodyLarge)
        Text("Creado: ${formatMovementDate(movement.createdAt)}", style = MaterialTheme.typography.bodyLarge)
        Text("Actualizado: ${formatMovementDate(movement.updatedAt)}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Actividad en GlassPay (Sandbox). Solo el servidor confirma un pago.",
            color = GlassPayColors.MutedText,
        )
    }
}
