package com.glasspay.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AssistantScreen(
    session: CollectorSession,
    api: PaymentApi,
    chatState: MutableState<ChatState>,
    onSessionInvalid: (message: String) -> Unit,
    onOpenScheduled: () -> Unit,
    // Nulo cuando el asistente es una pestaña de la barra inferior (no hay pantalla a la que volver).
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Lo guarda MainActivity solo en memoria para sobrevivir a la visita a Programados.
    var chat by chatState
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    BackHandler(enabled = onBack != null) { onBack?.invoke() }

    // El dictado solo llena el campo: el usuario revisa y envía. Nunca se envía solo.
    val recognizer = remember {
        VoiceRecognizer(
            context = context,
            onResult = { text ->
                listening = false
                voiceMessage = null
                input = text.take(MAX_ASSISTANT_MESSAGE_LENGTH)
            },
            onReady = { listening = true },
            onEnd = { listening = false },
            onPartial = { listening = true },
            reportError = { _, message ->
                listening = false
                voiceMessage = message
            },
        )
    }
    DisposableEffect(Unit) { onDispose { recognizer.destroy() } }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recognizer.start() else voiceMessage = "Sin permiso de micrófono. Escribe tu mensaje."
    }

    fun dictate() {
        voiceMessage = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recognizer.start()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun send(text: String) {
        if (!chat.canSend(text)) return
        chat = chat.userSent(text)
        input = ""
        scope.launch {
            chat = try {
                chat.replied(api.sendAssistantMessage(session, text.trim()))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                Log.i("GlassPay", "assistant failure type=${exception.type} httpCode=${exception.httpCode}")
                if (isSessionInvalid(exception.type)) {
                    onSessionInvalid(SESSION_INVALID_MESSAGE)
                    return@launch
                }
                chat.failed(assistantErrorMessage(exception.type))
            } catch (_: Exception) {
                Log.i("GlassPay", "assistant failure type=UNKNOWN httpCode=none")
                chat.failed(assistantErrorMessage(ApiFailureType.INVALID_RESPONSE))
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Para que el aviso de "esperando tu confirmación" llegue con la app cerrada (Android 13+).
    fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun sendConfirmation(proposal: AssistantProposal, autoExecute: Boolean) {
        if (!chat.canConfirm(proposal.proposalId)) return
        if (proposal.runAt != null && !autoExecute) askNotificationsIfNeeded()
        chat = chat.proposalConfirming(proposal.proposalId)
        scope.launch {
            chat = try {
                val outcome = api.confirmAssistantProposal(session, proposal.proposalId, autoExecute)
                chat.proposalConfirmed(proposal.proposalId, proposalOutcomeMessage(outcome, autoExecute))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                Log.i("GlassPay", "proposal failure type=${exception.type} httpCode=${exception.httpCode}")
                if (isSessionInvalid(exception.type)) {
                    onSessionInvalid(SESSION_INVALID_MESSAGE)
                    return@launch
                }
                chat.proposalFailed(proposal.proposalId, proposalErrorMessage(exception.type))
            } catch (_: Exception) {
                chat.proposalFailed(proposal.proposalId, proposalErrorMessage(ApiFailureType.INVALID_RESPONSE))
            }
        }
    }

    fun confirmProposal(proposal: AssistantProposal, autoExecute: Boolean) {
        if (!chat.canConfirm(proposal.proposalId)) return
        if (proposal.action == "schedule_payment") {
            // Sale dinero: siempre vista previa (la tarjeta) + huella o bloqueo del teléfono.
            val activity = context.findActivity() ?: return
            if (DeviceAuthenticator.availability(context) != DeviceAuthAvailability.AVAILABLE) {
                chat = chat.proposalFailed(proposal.proposalId, "Configura un bloqueo de pantalla para programar pagos.")
                return
            }
            requireDeviceAuth(
                activity,
                onSuccess = { sendConfirmation(proposal, autoExecute) },
                onFailure = { voiceMessage = "Necesitamos confirmar que eres tú para programar el pago." },
            )
            return
        }
        sendConfirmation(proposal, autoExecute)
    }

    LaunchedEffect(chat.messages.size, chat.sending) {
        val count = chat.messages.size + if (chat.sending) 1 else 0
        if (count > 0) listState.animateScrollToItem(count)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Asistente", onBack = onBack) {
            CircleIconButton(R.drawable.ic_clock, "Ver programados", onOpenScheduled, tint = GlassPayColors.Accent)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") { AssistantIntro() }
            items(chat.messages, key = { it.id }) { message ->
                if (message.fromUser) {
                    UserBubble(message.text)
                } else {
                    AssistantBubble(
                        message = message,
                        proposalStatus = { chat.proposalStatus(it) },
                        onConfirm = ::confirmProposal,
                        onDiscard = { chat = chat.proposalDiscarded(it.proposalId) },
                        onOpenScheduled = onOpenScheduled,
                    )
                }
            }
            item(key = "typing") {
                if (chat.sending) TypingBubble()
            }
        }
        HorizontalDivider(color = GlassPayColors.Line)
        Column(
            modifier = Modifier.fillMaxWidth().background(GlassPayColors.Card).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chat.suggestions.forEach { suggestion ->
                    SuggestionChip(suggestion, enabled = !chat.sending) { send(suggestion) }
                }
            }
            voiceMessage?.let { Text(it, color = GlassPayColors.Warning, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(MAX_ASSISTANT_MESSAGE_LENGTH) },
                    placeholder = { Text(if (listening) "Escuchando…" else "Escribe un mensaje") },
                    maxLines = 4,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = gpTextFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                CircleIconButton(
                    R.drawable.ic_mic,
                    if (listening) "Escuchando" else "Dictar mensaje",
                    onClick = {
                        if (listening) {
                            recognizer.cancel()
                            listening = false
                        } else {
                            dictate()
                        }
                    },
                    tint = if (listening) GlassPayColors.Accent else GlassPayColors.Text,
                )
                val canSend = chat.canSend(input)
                Surface(
                    onClick = { send(input) },
                    enabled = canSend,
                    shape = CircleShape,
                    color = if (canSend) GlassPayColors.Accent else GlassPayColors.Raised,
                    contentColor = if (canSend) GlassPayColors.OnAccent else GlassPayColors.MutedText,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_send_message),
                            contentDescription = "Enviar mensaje",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantIntro() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        Text("¿En qué te ayudo?", style = MaterialTheme.typography.headlineSmall, color = GlassPayColors.Text)
        Text(
            "Pregúntame cuánto cobraste o enviaste y quién te debe, o pídeme programar un cobro a un contacto. "
                + "Respondo con tu actividad en GlassPay (Sandbox).",
            style = MaterialTheme.typography.bodyMedium,
            color = GlassPayColors.MutedText,
        )
        InlineNotice(
            "La IA solo interpreta tu mensaje: nunca cobra ni envía dinero sin que tú lo confirmes.",
            icon = R.drawable.ic_lock,
        )
    }
}

private val BubbleShapeUser = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
private val BubbleShapeAssistant = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)

@Composable
private fun UserBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            color = GlassPayColors.OnAccent,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(GlassPayColors.Accent, BubbleShapeUser)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AssistantBubble(
    message: ChatMessage,
    proposalStatus: (String) -> ProposalStatus,
    onConfirm: (AssistantProposal, Boolean) -> Unit,
    onDiscard: (AssistantProposal) -> Unit,
    onOpenScheduled: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        IconBadge(R.drawable.ic_assistant, tint = GlassPayColors.Accent, size = 32.dp, background = GlassPayColors.AccentSoft)
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(if (message.isError) GlassPayColors.ErrorSoft else GlassPayColors.Card, BubbleShapeAssistant)
                .border(1.dp, if (message.isError) GlassPayColors.ErrorSoft else GlassPayColors.Line, BubbleShapeAssistant)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                message.text,
                color = if (message.isError) GlassPayColors.Error else GlassPayColors.Text,
                style = MaterialTheme.typography.bodyLarge,
            )
            message.reply?.summary?.let { SummaryCard(it) }
            message.reply?.debtors?.takeIf { it.isNotEmpty() }?.let { DebtorsCard(it) }
            message.reply?.proposal?.let { proposal ->
                ProposalCard(proposal, proposalStatus(proposal.proposalId), onConfirm, onDiscard, onOpenScheduled)
            }
            message.reply?.scheduled?.takeIf { it.isNotEmpty() }?.let { items ->
                items.forEach { ScheduledLine(it) }
                GpButton("Ver programados", onClick = onOpenScheduled, kind = GpButtonKind.SECONDARY, icon = R.drawable.ic_clock, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: AssistantSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Overline(assistantPeriodLabel(summary.period))
        MoneyText(formatMoney(summary.netTotal, summary.currency), style = MaterialTheme.typography.headlineLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryFigure("Cobrado", formatMoney(summary.paidTotal, summary.currency).substringBeforeLast(" "), "${summary.paidCount} pagos", Modifier.weight(1f))
            SummaryFigure("Enviado", formatMoney(summary.sentTotal, summary.currency).substringBeforeLast(" "), "${summary.sentCount} envíos", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryFigure(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
        Text(value, style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
    }
}

@Composable
private fun DebtorsCard(debtors: List<AssistantDebtor>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        debtors.forEach { debtor ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(debtor.name, size = 32.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(debtor.name, style = MaterialTheme.typography.labelLarge, color = GlassPayColors.Text)
                    Text(
                        if (debtor.count == 1) "1 cobro pendiente" else "${debtor.count} cobros pendientes",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassPayColors.MutedText,
                    )
                }
                Text(formatMoney(debtor.total, "MXN").substringBeforeLast(" "), style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(R.drawable.ic_assistant, tint = GlassPayColors.Accent, size = 32.dp, background = GlassPayColors.AccentSoft)
        Row(
            modifier = Modifier.background(GlassPayColors.Card, BubbleShapeAssistant).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = GlassPayColors.Accent)
            Text("Pensando…", style = MaterialTheme.typography.bodyMedium, color = GlassPayColors.MutedText)
        }
    }
}

@Composable
private fun SuggestionChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = GlassPayColors.Raised,
        contentColor = GlassPayColors.Text,
        border = BorderStroke(1.dp, GlassPayColors.Line),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

/** Tarjeta de confirmación: lo que el servidor guardó, con el interruptor de ejecución automática. */
@Composable
private fun ProposalCard(
    proposal: AssistantProposal,
    status: ProposalStatus,
    onConfirm: (AssistantProposal, Boolean) -> Unit,
    onDiscard: (AssistantProposal) -> Unit,
    onOpenScheduled: () -> Unit,
) {
    val cancelling = proposal.action == "cancel_scheduled"
    val splitting = proposal.action == "split_bill"
    val reminding = proposal.action == "reminder"
    val paying = proposal.action == "schedule_payment"
    // El interruptor solo aplica a lo que se ejecuta en el futuro.
    val scheduledForLater = !cancelling && proposal.runAt != null
    // En pagos lo automático va apagado por defecto (sale tu dinero); en cobros, encendido.
    var autoExecute by remember(proposal.proposalId) { mutableStateOf(!paying) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassPayColors.Raised, MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Overline(
            when (proposal.action) {
                "cancel_scheduled" -> "Cancelar programación"
                "split_bill" -> "Dividir cuenta"
                "recurring_charge" -> "Cobro recurrente"
                "reminder" -> "Recordatorio"
                "schedule_payment" -> "Pago programado"
                else -> "Cobro programado"
            },
        )
        if (!splitting) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(proposal.contactName, size = 32.dp)
                Text(proposal.contactName, style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text, modifier = Modifier.weight(1f))
            }
        }
        if (splitting) Text("Total", style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
        proposal.amount?.let { MoneyText(formatMoney(it, proposal.currency), style = MaterialTheme.typography.headlineLarge) }
        if (proposal.concept.isNotBlank()) {
            Text(proposal.concept, style = MaterialTheme.typography.bodyMedium, color = GlassPayColors.Text)
        }
        if (paying && proposal.balanceWarning) {
            InlineNotice(
                "Hoy tu saldo no alcanza para este pago. Se volverá a revisar a la hora; si no alcanza, no se envía.",
                kind = StatusKind.PENDING,
            )
        }
        if (reminding) {
            Text(
                "Le llega como aviso en su sección «Por pagar». No crea ningún cobro.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassPayColors.MutedText,
            )
        }
        if (splitting) {
            proposal.participants.forEach { share ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(share.contactName, size = 28.dp)
                    Text(share.contactName, style = MaterialTheme.typography.labelLarge, color = GlassPayColors.Text, modifier = Modifier.weight(1f))
                    Text(formatMoney(share.amount, proposal.currency).substringBeforeLast(" "), style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
                }
            }
            proposal.myShare?.let {
                Text(
                    "Tu parte: ${formatMoney(it, proposal.currency).substringBeforeLast(" ")} (no se cobra)",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassPayColors.MutedText,
                )
            }
        }
        recurrenceLabel(proposal.recurrence, proposal.occurrencesTotal)?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = GlassPayColors.Accent)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_clock), contentDescription = null, tint = GlassPayColors.Warning, modifier = Modifier.size(18.dp))
            Text(
                when {
                    proposal.runAtLabel == null && reminding -> "Se envía al confirmar"
                    proposal.runAtLabel == null -> "Se cobra al confirmar"
                    proposal.recurrence != null -> "Primero: ${proposal.runAtLabel}"
                    else -> proposal.runAtLabel
                },
                style = MaterialTheme.typography.labelLarge,
                color = GlassPayColors.Warning,
            )
        }
        when (status) {
            ProposalStatus.PENDING -> {
                if (scheduledForLater) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                when {
                                    paying -> "Enviar automáticamente a la hora (bajo tu riesgo)"
                                    reminding -> "Enviar automáticamente a la hora"
                                    else -> "Crear automáticamente a la hora"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = GlassPayColors.Text,
                            )
                            Text(
                                when {
                                    paying && autoExecute -> "Se enviará sin volver a pedirte la huella."
                                    paying -> "A la hora te pediré vista previa y huella para enviarlo."
                                    !autoExecute -> "A la hora te pediré confirmarlo."
                                    reminding -> "Se envía solo a la hora."
                                    else -> "Se crea solo; tu contacto lo aprueba en PayPal."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassPayColors.MutedText,
                            )
                        }
                        Switch(
                            checked = autoExecute,
                            onCheckedChange = { autoExecute = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GlassPayColors.OnAccent,
                                checkedTrackColor = GlassPayColors.Accent,
                                uncheckedTrackColor = GlassPayColors.Card,
                            ),
                        )
                    }
                }
                GpButton(
                    when {
                        cancelling -> "Sí, cancelar"
                        splitting && !scheduledForLater -> "Crear ${proposal.participants.size} cobros"
                        reminding && !scheduledForLater -> "Enviar recordatorio"
                        paying -> "Confirmar con huella"
                        else -> "Confirmar"
                    },
                    onClick = { onConfirm(proposal, autoExecute) },
                    kind = if (cancelling) GpButtonKind.DANGER else GpButtonKind.PRIMARY,
                    icon = R.drawable.ic_check,
                    modifier = Modifier.fillMaxWidth(),
                )
                GpButton("Descartar", onClick = { onDiscard(proposal) }, kind = GpButtonKind.GHOST, modifier = Modifier.fillMaxWidth())
            }
            ProposalStatus.CONFIRMING -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = GlassPayColors.Accent)
                Text("Confirmando…", style = MaterialTheme.typography.bodyMedium, color = GlassPayColors.MutedText)
            }
            ProposalStatus.CONFIRMED -> {
                StatusPill(
                    when {
                        cancelling -> "Cancelado"
                        scheduledForLater -> "Programado"
                        reminding -> "Enviado"
                        else -> "Cobros creados"
                    },
                    if (cancelling) StatusKind.NEUTRAL else StatusKind.SUCCESS,
                )
                if (scheduledForLater || cancelling) {
                    GpButton("Ver programados", onClick = onOpenScheduled, kind = GpButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
                }
            }
            ProposalStatus.DISCARDED -> StatusPill("Descartado", StatusKind.NEUTRAL)
            ProposalStatus.FAILED -> StatusPill("No se aplicó", StatusKind.FAILURE)
        }
    }
}

@Composable
private fun ScheduledLine(item: ScheduledItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(item.contactName, size = 32.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(item.contactName, style = MaterialTheme.typography.labelLarge, color = GlassPayColors.Text)
            Text(item.runAtLabel, style = MaterialTheme.typography.bodySmall, color = GlassPayColors.MutedText)
        }
        Text(formatMoney(item.amount, item.currency).substringBeforeLast(" "), style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text)
    }
}
