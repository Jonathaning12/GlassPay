package com.glasspay.android

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
    onSessionInvalid: (message: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chat by remember { mutableStateOf(ChatState()) }
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    BackHandler { onBack() }

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

    LaunchedEffect(chat.messages.size, chat.sending) {
        val count = chat.messages.size + if (chat.sending) 1 else 0
        if (count > 0) listState.animateScrollToItem(count)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Asistente", onBack = onBack) {
            IconBadge(R.drawable.ic_assistant, tint = GlassPayColors.Accent, size = 40.dp, background = GlassPayColors.AccentSoft)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") { AssistantIntro() }
            items(chat.messages, key = { it.id }) { message ->
                if (message.fromUser) UserBubble(message.text) else AssistantBubble(message)
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
            "Pregúntame cuánto cobraste o enviaste y quién te debe. Respondo con tu actividad en GlassPay (Sandbox).",
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
private fun AssistantBubble(message: ChatMessage) {
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
