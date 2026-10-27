package com.glasspay.android

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<LoadState<List<ScheduledItem>>>(LoadState.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingCancel by remember { mutableStateOf<ScheduledItem?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(reload) {
        if (state is LoadState.Loaded) refreshing = true else state = LoadState.Loading
        try {
            state = LoadState.Loaded(api.getScheduled(session))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            Log.i("GlassPay", "scheduled failure type=${exception.type} httpCode=${exception.httpCode}")
            if (isSessionInvalid(exception.type)) onSessionInvalid(SESSION_INVALID_MESSAGE) else state = LoadState.Failed
        } catch (_: Exception) {
            Log.i("GlassPay", "scheduled failure type=UNKNOWN httpCode=none")
            state = LoadState.Failed
        } finally {
            refreshing = false
        }
    }

    fun act(item: ScheduledItem, run: Boolean) {
        if (busyId != null) return
        busyId = item.scheduledId
        message = null
        scope.launch {
            try {
                if (run) {
                    val result = api.runScheduled(session, item.scheduledId)
                    message = if (result.state == "completado") {
                        "Cobro creado. ${item.contactName} debe aprobarlo en PayPal."
                    } else {
                        scheduledFailureMessage(result.failure) ?: "No se pudo crear el cobro."
                    }
                } else {
                    api.cancelScheduled(session, item.scheduledId)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                Log.i("GlassPay", "scheduled action failure type=${exception.type} httpCode=${exception.httpCode}")
                if (isSessionInvalid(exception.type)) {
                    onSessionInvalid(SESSION_INVALID_MESSAGE)
                    return@launch
                }
                message = if (exception.type == ApiFailureType.NOT_FOUND) {
                    "Esa programación ya cambió de estado."
                } else {
                    assistantErrorMessage(exception.type)
                }
            } catch (_: Exception) {
                message = assistantErrorMessage(ApiFailureType.INVALID_RESPONSE)
            } finally {
                busyId = null
                reload += 1
            }
        }
    }

    pendingCancel?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            text = { Text("¿Cancelar el cobro a ${item.contactName} por ${formatMoney(item.amount, item.currency)}?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingCancel = null
                    act(item, run = false)
                }) { Text("Cancelar cobro") }
            },
            dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text("No") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Programados", onBack = onBack)
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { reload += 1 }, modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "notice") {
                    InlineNotice(
                        "Lo programado vive en el servidor de prueba: si se reinicia o se actualiza, se pierde.",
                        kind = StatusKind.PENDING,
                        icon = R.drawable.ic_clock,
                    )
                }
                message?.let { text -> item(key = "message") { InlineNotice(text) } }
                when (val current = state) {
                    LoadState.Loading -> items(3) { SkeletonBox(Modifier.fillMaxWidth().height(120.dp)) }
                    LoadState.Failed -> item(key = "failed") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            InlineNotice("No pudimos cargar lo programado.", kind = StatusKind.FAILURE)
                            GpButton("Reintentar", onClick = { reload += 1 }, kind = GpButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is LoadState.Loaded -> {
                        if (current.value.isEmpty()) {
                            item(key = "empty") { EmptyScheduled() }
                        }
                        items(current.value, key = { it.scheduledId }) { item ->
                            ScheduledCard(
                                item = item,
                                busy = busyId == item.scheduledId,
                                enabled = busyId == null,
                                onRun = { act(item, run = true) },
                                onCancel = { pendingCancel = item },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyScheduled() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(R.drawable.ic_clock, tint = GlassPayColors.Accent, size = 72.dp, background = GlassPayColors.AccentSoft)
        Text("No tienes nada programado", style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
        Text(
            "Pídeselo al asistente: «cóbrale a Ana 300 por la renta mañana a las 6».",
            style = MaterialTheme.typography.bodyMedium,
            color = GlassPayColors.MutedText,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ScheduledCard(
    item: ScheduledItem,
    busy: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    GpCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), spacing = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(item.contactName)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Cobrar a ${item.contactName}", style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
                Text(item.concept, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
            }
            Text(
                formatMoney(item.amount, item.currency).substringBeforeLast(" "),
                style = MaterialTheme.typography.titleMedium,
                color = GlassPayColors.Text,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_clock), contentDescription = null, tint = GlassPayColors.MutedText, modifier = Modifier.size(18.dp))
            Text(
                item.runAtLabel + if (item.autoExecute) " · automático" else " · con tu confirmación",
                style = MaterialTheme.typography.bodySmall,
                color = GlassPayColors.MutedText,
                modifier = Modifier.weight(1f),
            )
            StatusPill(scheduledStateLabel(item.state), scheduledStateKind(item.state))
        }
        scheduledFailureMessage(item.failure)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.Error)
        }
        if (busy) {
            Text("Procesando…", style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
        } else if (item.state == "esperando_confirmacion") {
            GpButton("Crear el cobro ahora", onClick = onRun, enabled = enabled, icon = R.drawable.ic_check, modifier = Modifier.fillMaxWidth())
            GpButton("Cancelar", onClick = onCancel, enabled = enabled, kind = GpButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
        } else if (item.state == "programado") {
            GpButton("Cancelar", onClick = onCancel, enabled = enabled, kind = GpButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
        }
    }
}
