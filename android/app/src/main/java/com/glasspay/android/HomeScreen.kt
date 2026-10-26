package com.glasspay.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
fun SkeletonBox(modifier: Modifier = Modifier) {
    val alpha = if (animationsEnabled()) {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val value by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.6f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "skeletonAlpha",
        )
        value
    } else {
        0.4f
    }
    Box(modifier = modifier.background(Color(0xFF3A4260).copy(alpha = alpha), RoundedCornerShape(12.dp)))
}

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Loaded<T>(val value: T) : LoadState<T>
    data object Failed : LoadState<Nothing>
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
    onCharge: () -> Unit,
    onOpenMyQr: () -> Unit,
    locked: Boolean,
    amountsVisible: Boolean,
    onToggleAmounts: () -> Unit,
    showLockHint: Boolean = false,
) {
    var reload by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<LoadState<CollectorProfile>>(LoadState.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var incoming by remember { mutableStateOf<List<IncomingRequest>>(emptyList()) }
    var incomingMessage by remember { mutableStateOf<String?>(null) }
    var pendingReject by remember { mutableStateOf<IncomingRequest?>(null) }
    var rejecting by remember { mutableStateOf(false) }

    // "Por pagar": se recarga al mostrarse y cada 5 s mientras Inicio está visible.
    LaunchedEffect(reload, locked) {
        if (locked) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    incoming = api.incomingRequests(session)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: ApiException) {
                    Log.i("GlassPay", "incoming failure type=${exception.type} httpCode=${exception.httpCode}")
                    if (isSessionInvalid(exception.type)) onSessionInvalid(SESSION_INVALID_MESSAGE)
                } catch (_: Exception) {
                    Log.i("GlassPay", "incoming failure type=UNKNOWN httpCode=none")
                }
                delay(5_000)
            }
        }
    }

    fun approve(request: IncomingRequest) {
        if (!isAllowedApproveUrl(request.approveUrl)) {
            incomingMessage = "Enlace no válido."
            return
        }
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(request.approveUrl)).addCategory(Intent.CATEGORY_BROWSABLE),
            )
            incomingMessage = "Abriste PayPal. Cuando se confirme, desaparecerá de Por pagar."
        } catch (_: ActivityNotFoundException) {
            incomingMessage = "No hay navegador disponible."
        }
    }

    fun reject(request: IncomingRequest) {
        if (rejecting) return
        rejecting = true
        scope.launch {
            try {
                api.rejectIncoming(session, request.requestId)
                incomingMessage = null
                reload += 1
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                Log.i("GlassPay", "reject failure type=${exception.type} httpCode=${exception.httpCode}")
                when (exception.type) {
                    ApiFailureType.UNAUTHORIZED -> onSessionInvalid(SESSION_INVALID_MESSAGE)
                    ApiFailureType.NOT_FOUND -> reload += 1
                    else -> incomingMessage = "No pudimos rechazar el cobro. Revisa tu conexión."
                }
            } catch (_: Exception) {
                Log.i("GlassPay", "reject failure type=UNKNOWN httpCode=none")
                incomingMessage = "No pudimos rechazar el cobro. Revisa tu conexión."
            } finally {
                rejecting = false
            }
        }
    }

    pendingReject?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingReject = null },
            text = {
                Text(
                    "¿Rechazar el cobro de ${request.fromName ?: "un contacto"} por " +
                        "${formatMoney(request.amount, request.currency)}?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingReject = null
                    reject(request)
                }) { Text("Rechazar") }
            },
            dismissButton = { TextButton(onClick = { pendingReject = null }) { Text("Cancelar") } },
        )
    }

    LaunchedEffect(reload) {
        if (state is LoadState.Loaded) refreshing = true else state = LoadState.Loading
        try {
            state = LoadState.Loaded(api.getCollectorProfile(session))
            refreshing = false
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            Log.i("GlassPay", "profile failure type=${exception.type} httpCode=${exception.httpCode}")
            refreshing = false
            if (isSessionInvalid(exception.type)) {
                onSessionInvalid(SESSION_INVALID_MESSAGE)
            } else {
                state = LoadState.Failed
            }
        } catch (_: Exception) {
            Log.i("GlassPay", "profile failure type=UNKNOWN httpCode=none")
            refreshing = false
            state = LoadState.Failed
        }
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { reload += 1 }) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Hola, ${session.displayName}", style = MaterialTheme.typography.headlineMedium)
        if (showLockHint) {
            Text(
                "Configura un bloqueo de pantalla en tu teléfono para proteger GlassPay.",
                color = GlassPayColors.Warning,
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Actividad en GlassPay (Sandbox)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    EyeToggle(visible = amountsVisible, onToggle = onToggleAmounts)
                }
                when (val current = state) {
                    LoadState.Loading -> {
                        SkeletonBox(Modifier.fillMaxWidth(0.6f).height(40.dp))
                        SkeletonBox(Modifier.fillMaxWidth(0.4f).height(18.dp))
                        SkeletonBox(Modifier.fillMaxWidth(0.3f).height(18.dp))
                    }
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
                                    maskAmount(formatMoney(item.paidTotal, item.currency), amountsVisible),
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
        val visibleIncoming = incoming.filter {
            it.state == "pendiente_de_pagador" || it.state == "aprobada"
        }
        if (visibleIncoming.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Por pagar", style = MaterialTheme.typography.titleMedium)
                    visibleIncoming.forEach { request ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                formatMoney(request.amount, request.currency),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(request.concept, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Te cobra ${request.fromName ?: "un contacto"}",
                                color = GlassPayColors.MutedText,
                            )
                            if (request.state == "aprobada") {
                                Text("Procesando", color = GlassPayColors.Warning)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { approve(request) }) { Text("Aprobar con PayPal") }
                                    OutlinedButton(
                                        onClick = { pendingReject = request },
                                        enabled = !rejecting,
                                    ) { Text("Rechazar") }
                                }
                            }
                        }
                    }
                    incomingMessage?.let { Text(it, color = GlassPayColors.Warning) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCharge) { Text("Cobrar") }
            OutlinedButton(onClick = onOpenMyQr) { Text("Mi QR") }
            Button(onClick = {}, enabled = false) { Text("Enviar") }
        }
        Text("Enviar: Próximamente", color = GlassPayColors.MutedText)
    }
    }
}
