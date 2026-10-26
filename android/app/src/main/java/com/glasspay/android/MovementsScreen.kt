package com.glasspay.android

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovementsScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
    amountsVisible: Boolean,
    onToggleAmounts: (() -> Unit)? = null,
) {
    var reload by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<LoadState<List<Movement>>>(LoadState.Loading) }
    var selected by remember { mutableStateOf<Movement?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        if (state is LoadState.Loaded) refreshing = true else state = LoadState.Loading
        try {
            state = LoadState.Loaded(api.getMovements(session))
            refreshing = false
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            Log.i("GlassPay", "movements failure type=${exception.type} httpCode=${exception.httpCode}")
            refreshing = false
            if (isSessionInvalid(exception.type)) {
                onSessionInvalid(SESSION_INVALID_MESSAGE)
            } else {
                state = LoadState.Failed
            }
        } catch (_: Exception) {
            Log.i("GlassPay", "movements failure type=UNKNOWN httpCode=none")
            refreshing = false
            state = LoadState.Failed
        }
    }

    BackHandler(enabled = selected != null) { selected = null }

    val detail = selected
    if (detail != null) {
        MovementDetail(detail, amountsVisible, onBack = { selected = null })
        return
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { reload += 1 }) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Movimientos", style = MaterialTheme.typography.headlineMedium, color = GlassPayColors.Text)
                Text("Actividad en GlassPay (Sandbox)", style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
            }
            if (onToggleAmounts != null) EyeToggle(visible = amountsVisible, onToggle = onToggleAmounts)
        }
        when (val current = state) {
            LoadState.Loading -> repeat(4) {
                SkeletonBox(Modifier.fillMaxWidth().height(72.dp))
            }
            LoadState.Failed -> {
                InlineNotice("No pudimos cargar tus movimientos.", kind = StatusKind.FAILURE)
                GpButton("Reintentar", onClick = { reload += 1 }, kind = GpButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
            }
            is LoadState.Loaded -> {
                if (current.value.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconBadge(R.drawable.ic_movements, tint = GlassPayColors.MutedText, size = 64.dp)
                        Text("Aún no tienes movimientos.", style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
                        Text("Tus cobros y envíos aparecerán aquí.", style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(current.value, key = { it.movementId }) { movement ->
                        MovementRow(movement, amountsVisible, onClick = { selected = movement })
                    }
                    item {
                        GpButton(
                            "Actualizar",
                            onClick = { reload += 1 },
                            kind = GpButtonKind.GHOST,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
    }
}

private fun movementTitle(movement: Movement): String =
    if (movement.kind == "envio") "Enviado a ${movement.payerName}" else movement.payerName

@Composable
private fun MovementRow(movement: Movement, amountsVisible: Boolean, onClick: () -> Unit) {
    val outgoing = movement.kind == "envio"
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = GlassPayColors.Card,
        border = BorderStroke(1.dp, GlassPayColors.Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                if (outgoing) R.drawable.ic_send else R.drawable.ic_receive,
                tint = if (outgoing) GlassPayColors.Text else GlassPayColors.Success,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(movementTitle(movement), style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text, maxLines = 1)
                Text(
                    listOf(movement.concept, formatMovementDate(movement.createdAt)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassPayColors.MutedText,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    signedAmount(movement, amountsVisible).substringBeforeLast(" "),
                    style = MaterialTheme.typography.titleMedium,
                    color = GlassPayColors.Text,
                )
                StatusPill(movementStatusLabel(movement.state, movement.kind), movementStatusKind(movement.state, movement.kind))
            }
        }
    }
}

@Composable
private fun MovementDetail(movement: Movement, amountsVisible: Boolean, onBack: () -> Unit) {
    val outgoing = movement.kind == "envio"
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(if (outgoing) "Detalle del envío" else "Detalle del cobro", onBack = onBack)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GpCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), spacing = 14.dp) {
                StatusPill(movementStatusLabel(movement.state, movement.kind), movementStatusKind(movement.state, movement.kind))
                MoneyText(signedAmount(movement, amountsVisible), style = MaterialTheme.typography.displaySmall)
                HorizontalDivider(color = GlassPayColors.Line)
                DetailLine(if (outgoing) "Destinatario" else "Pagador", movement.payerName)
                DetailLine(if (outgoing) "Nota" else "Concepto", movement.concept.ifBlank { "—" })
                DetailLine("Creado", formatMovementDate(movement.createdAt))
                DetailLine("Actualizado", formatMovementDate(movement.updatedAt))
            }
            InlineNotice("Actividad en GlassPay (Sandbox). Solo el servidor confirma un pago.", icon = R.drawable.ic_lock)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = GlassPayColors.Text)
    }
}

private fun signedAmount(movement: Movement, visible: Boolean): String {
    val text = maskAmount(formatMoney(movement.amount, movement.currency), visible)
    return if (movement.kind == "envio") "−$text" else text
}
