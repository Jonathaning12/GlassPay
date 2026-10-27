package com.glasspay.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.annotation.DrawableRes
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.MaterialTheme
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
    Box(modifier = modifier.background(GlassPayColors.Raised.copy(alpha = (alpha + 0.4f).coerceAtMost(1f)), RoundedCornerShape(12.dp)))
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
    onSend: () -> Unit,
    onPayoutEmail: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenScheduled: () -> Unit,
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
    var awaitingConfirmation by remember { mutableStateOf(0) }

    // Cobros programados que esperan la confirmación del cobrador (sin notificaciones del sistema en C2).
    LaunchedEffect(reload, locked) {
        if (locked) return@LaunchedEffect
        try {
            awaitingConfirmation = api.getScheduled(session).count { it.state == "esperando_confirmacion" }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            Log.i("GlassPay", "scheduled summary failure type=${exception.type} httpCode=${exception.httpCode}")
        } catch (_: Exception) {
            Log.i("GlassPay", "scheduled summary failure type=UNKNOWN httpCode=none")
        }
    }

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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Hola, ${session.displayName}", style = MaterialTheme.typography.headlineMedium, color = GlassPayColors.Text)
                Text("Actividad en GlassPay (Sandbox)", style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
            }
            CircleIconButton(R.drawable.ic_assistant, "Asistente de IA", onOpenAssistant, tint = GlassPayColors.Accent)
            Avatar(session.displayName, highlighted = true)
        }
        if (showLockHint) {
            InlineNotice(
                "Configura un bloqueo de pantalla en tu teléfono para proteger GlassPay.",
                kind = StatusKind.PENDING,
                icon = R.drawable.ic_lock,
            )
        }
        GpCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), spacing = 20.dp) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Overline("Saldo disponible")
                    when (val current = state) {
                        LoadState.Loading -> SkeletonBox(Modifier.fillMaxWidth(0.7f).height(48.dp))
                        LoadState.Failed -> Text("No pudimos cargar tu actividad.", color = GlassPayColors.Error)
                        is LoadState.Loaded -> {
                            if (current.value.activity.isEmpty()) {
                                Text("Aún no has cobrado. Empieza con Cobrar.", color = GlassPayColors.MutedText)
                            }
                            current.value.activity.forEach { item ->
                                MoneyText(
                                    maskAmount(formatMoney(item.availableTotal, item.currency), amountsVisible),
                                    style = MaterialTheme.typography.displaySmall,
                                )
                            }
                        }
                    }
                }
                EyeToggle(visible = amountsVisible, onToggle = onToggleAmounts)
            }
            when (val current = state) {
                LoadState.Loading -> {
                    HorizontalDivider(color = GlassPayColors.Line)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonBox(Modifier.weight(1f).height(40.dp))
                        SkeletonBox(Modifier.weight(1f).height(40.dp))
                    }
                }
                LoadState.Failed -> GpButton(
                    "Reintentar",
                    onClick = { reload += 1 },
                    kind = GpButtonKind.SECONDARY,
                    modifier = Modifier.fillMaxWidth(),
                )
                is LoadState.Loaded -> current.value.activity.forEach { item ->
                    HorizontalDivider(color = GlassPayColors.Line)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TotalItem(
                            label = "Cobrado",
                            value = maskAmount(formatMoney(item.paidTotal, item.currency), amountsVisible),
                            icon = R.drawable.ic_receive,
                            tint = GlassPayColors.Success,
                            modifier = Modifier.weight(1f),
                        )
                        TotalItem(
                            label = "Enviado",
                            value = maskAmount(formatMoney(item.sentTotal, item.currency), amountsVisible),
                            icon = R.drawable.ic_send,
                            tint = GlassPayColors.Text,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${item.paidCount} pagos recibidos",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassPayColors.MutedText,
                        )
                        if (item.inProgressCount > 0) {
                            StatusPill("${item.inProgressCount} en proceso", StatusKind.PENDING)
                        }
                    }
                }
            }
        }
        if (awaitingConfirmation > 0) {
            GpCard(modifier = Modifier.fillMaxWidth(), spacing = 12.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(R.drawable.ic_clock, tint = GlassPayColors.Warning, size = 40.dp, background = GlassPayColors.WarningSoft)
                    Text(
                        if (awaitingConfirmation == 1) {
                            "1 cobro programado espera tu confirmación"
                        } else {
                            "$awaitingConfirmation cobros programados esperan tu confirmación"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = GlassPayColors.Text,
                        modifier = Modifier.weight(1f),
                    )
                }
                GpButton("Revisar", onClick = onOpenScheduled, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickAction("Cobrar", R.drawable.ic_charge, onCharge, primary = true, modifier = Modifier.weight(1f))
            QuickAction("Enviar", R.drawable.ic_send, onSend, modifier = Modifier.weight(1f))
            QuickAction("Mi QR", R.drawable.ic_qr, onOpenMyQr, modifier = Modifier.weight(1f))
        }
        val visibleIncoming = incoming.filter {
            it.state == "pendiente_de_pagador" || it.state == "aprobada"
        }
        if (visibleIncoming.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Por pagar", style = MaterialTheme.typography.headlineSmall, color = GlassPayColors.Text, modifier = Modifier.weight(1f))
                Text(
                    if (visibleIncoming.size == 1) "1 solicitud" else "${visibleIncoming.size} solicitudes",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassPayColors.MutedText,
                )
            }
            visibleIncoming.forEach { request ->
                GpCard(modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(request.fromName ?: "?")
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Te cobra ${request.fromName ?: "un contacto"}",
                                style = MaterialTheme.typography.titleMedium,
                                color = GlassPayColors.Text,
                            )
                            Text(request.concept, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
                        }
                        Text(
                            formatMoney(request.amount, request.currency).substringBeforeLast(" "),
                            style = MaterialTheme.typography.headlineSmall,
                            color = GlassPayColors.Text,
                        )
                    }
                    if (request.state == "aprobada") {
                        StatusPill("Procesando", StatusKind.PENDING)
                    } else {
                        GpButton(
                            "Aprobar con PayPal",
                            onClick = { approve(request) },
                            icon = R.drawable.ic_check,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        GpButton(
                            "Rechazar",
                            onClick = { pendingReject = request },
                            kind = GpButtonKind.SECONDARY,
                            enabled = !rejecting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            incomingMessage?.let { InlineNotice(it, kind = StatusKind.PENDING) }
        }
        (state as? LoadState.Loaded)?.let { loaded ->
            val email = loaded.value.payoutEmail
            GpCard(modifier = Modifier.fillMaxWidth(), spacing = 14.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(R.drawable.ic_mail, tint = GlassPayColors.Accent, size = 40.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Recibir envíos", style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
                        Text(
                            if (email == null) "Registra tu correo de PayPal Sandbox para recibir envíos." else "Recibes en $email",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassPayColors.MutedText,
                        )
                    }
                }
                GpButton(
                    if (email == null) "Registrar correo" else "Cambiar",
                    onClick = onPayoutEmail,
                    kind = if (email == null) GpButtonKind.PRIMARY else GpButtonKind.SECONDARY,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            GpButton(
                "Actualizar",
                onClick = { reload += 1 },
                kind = GpButtonKind.GHOST,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
    }
}

/** Monto grande con la moneda en pequeño al lado ("$350.00" + "MXN"). */
@Composable
fun MoneyText(formatted: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = GlassPayColors.Text) {
    val currency = formatted.substringAfterLast(" ", "")
    val amount = if (currency.isEmpty()) formatted else formatted.substringBeforeLast(" ")
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(amount, style = style, color = color, maxLines = 1)
        if (currency.isNotEmpty()) {
            Text(
                currency,
                style = MaterialTheme.typography.titleMedium,
                color = GlassPayColors.MutedText,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun TotalItem(label: String, value: String, @DrawableRes icon: Int, tint: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
            Text(value, style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text, maxLines = 1)
        }
    }
}

@Composable
private fun QuickAction(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (primary) GlassPayColors.Accent else GlassPayColors.Card,
        contentColor = if (primary) GlassPayColors.OnAccent else GlassPayColors.Text,
        border = if (primary) null else BorderStroke(1.dp, GlassPayColors.Line),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (primary) GlassPayColors.OnAccent else GlassPayColors.Accent,
                modifier = Modifier.size(28.dp),
            )
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
