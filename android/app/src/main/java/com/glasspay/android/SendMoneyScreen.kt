package com.glasspay.android

import android.util.Log
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 3_000L
private const val POLL_MAX_ATTEMPTS = 20

private enum class SendStep { PICK, AMOUNT, PREVIEW, RESULT }

@Composable
fun SendMoneyScreen(
    session: CollectorSession,
    api: PaymentApi,
    initialContact: Contact?,
    locked: Boolean,
    onSessionInvalid: (message: String) -> Unit,
    onBack: () -> Unit,
    onOpenMovements: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (initialContact != null) SendStep.AMOUNT else SendStep.PICK) }
    var contact by remember { mutableStateOf(initialContact) }
    var contacts by remember { mutableStateOf<LoadState<List<Contact>>>(LoadState.Loading) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<TransferPreview?>(null) }
    var remaining by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var confirmBlocked by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<TransferResult?>(null) }
    var pollAttempts by remember { mutableStateOf(0) }
    var pollGaveUp by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    LaunchedEffect(step == SendStep.PICK) {
        if (step != SendStep.PICK) return@LaunchedEffect
        contacts = LoadState.Loading
        try {
            contacts = LoadState.Loaded(api.listContacts(session))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            Log.i("GlassPay", "sendContacts failure type=${exception.type} httpCode=${exception.httpCode}")
            if (isSessionInvalid(exception.type)) onSessionInvalid(SESSION_INVALID_MESSAGE) else contacts = LoadState.Failed
        } catch (_: Exception) {
            contacts = LoadState.Failed
        }
    }

    // Cuenta regresiva de la vista previa.
    LaunchedEffect(preview) {
        val current = preview ?: return@LaunchedEffect
        while (true) {
            remaining = remainingSeconds(current.expiresAt)
            if (remaining <= 0) break
            delay(1_000)
        }
    }

    // Consulta de movimientos mientras el envío sigue en proceso (pausada si la app está bloqueada).
    val resultState = result?.state
    LaunchedEffect(resultState, locked) {
        val current = result ?: return@LaunchedEffect
        if (locked || current.state == "completada" || current.state == "fallida") return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (pollAttempts < POLL_MAX_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            pollAttempts += 1
            try {
                val movements = api.getMovements(session)
                val match = movements.firstOrNull {
                    it.kind == "envio" && it.movementId == current.transferId
                }
                if (match != null && match.state != current.state) {
                    result = current.copy(state = match.state)
                    return@repeatOnLifecycle
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                if (isSessionInvalid(exception.type)) {
                    onSessionInvalid(SESSION_INVALID_MESSAGE)
                    return@repeatOnLifecycle
                }
            } catch (_: Exception) {
            }
        }
        pollGaveUp = true
        }
    }

    fun requestPreview() {
        val target = contact ?: return
        val amount = parseAmountInput(amountText)
        if (amount == null) {
            message = "Escribe un monto válido (máximo 1,000.00)."
            return
        }
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                val created = api.previewTransfer(session, target.contactId, amount, note.trim().ifEmpty { null })
                preview = created
                remaining = remainingSeconds(created.expiresAt)
                confirmBlocked = false
                step = SendStep.PREVIEW
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                Log.i("GlassPay", "transferPreview failure type=${exception.type} httpCode=${exception.httpCode}")
                message = when (exception.type) {
                    ApiFailureType.UNAUTHORIZED -> {
                        onSessionInvalid(SESSION_INVALID_MESSAGE)
                        null
                    }
                    ApiFailureType.NOT_FOUND -> "Este contacto ya no existe."
                    ApiFailureType.NETWORK, ApiFailureType.TIMEOUT -> "Revisa tu conexión."
                    else -> transferErrorMessage(exception.type, exception.serverError, target.displayName)
                }
            } catch (_: Exception) {
                Log.i("GlassPay", "transferPreview failure type=UNKNOWN httpCode=none")
                message = "Revisa tu conexión."
            } finally {
                busy = false
            }
        }
    }

    fun doConfirm(proposal: TransferPreview) {
        scope.launch {
            try {
                val outcome = api.confirmTransfer(session, proposal.transferProposalId)
                result = outcome
                pollAttempts = 0
                pollGaveUp = false
                step = SendStep.RESULT
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                Log.i("GlassPay", "transferConfirm failure type=${exception.type} httpCode=${exception.httpCode}")
                busy = false
                when (exception.type) {
                    ApiFailureType.UNAUTHORIZED -> onSessionInvalid(SESSION_INVALID_MESSAGE)
                    ApiFailureType.NOT_FOUND -> {
                        message = transferErrorMessage(exception.type, exception.serverError, proposal.recipientName)
                        preview = null
                        step = SendStep.AMOUNT
                    }
                    ApiFailureType.CONFLICT, ApiFailureType.BAD_REQUEST, ApiFailureType.RATE_LIMITED ->
                        message = transferErrorMessage(exception.type, exception.serverError, proposal.recipientName)
                    else -> {
                        // Resultado incierto: no se reintenta automáticamente.
                        confirmBlocked = true
                        message = transferErrorMessage(exception.type, exception.serverError, proposal.recipientName)
                    }
                }
            } catch (_: Exception) {
                Log.i("GlassPay", "transferConfirm failure type=UNKNOWN httpCode=none")
                busy = false
                confirmBlocked = true
                message = transferErrorMessage(ApiFailureType.NETWORK, null, proposal.recipientName)
            }
        }
    }

    fun confirmWithAuth() {
        val proposal = preview ?: return
        if (busy || confirmBlocked || locked || remaining <= 0) return
        val activity = context.findActivity() ?: return
        if (DeviceAuthenticator.availability(context) != DeviceAuthAvailability.AVAILABLE) {
            message = "Configura un bloqueo de pantalla para enviar dinero."
            return
        }
        busy = true
        message = null
        requireDeviceAuth(
            activity,
            onSuccess = { doConfirm(proposal) },
            onFailure = {
                busy = false
                message = "Necesitamos confirmar que eres tú."
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Enviar dinero", onBack = if (step != SendStep.RESULT) onBack else null)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        when (step) {
            SendStep.PICK -> {
                Overline("¿A quién le envías?")
                when (val current = contacts) {
                    LoadState.Loading -> repeat(3) { SkeletonBox(Modifier.fillMaxWidth().height(72.dp)) }
                    LoadState.Failed -> InlineNotice("No pudimos cargar tus contactos.", kind = StatusKind.FAILURE)
                    is LoadState.Loaded -> {
                        if (current.value.isEmpty()) {
                            InlineNotice("Aún no tienes contactos. Agrégalos desde Contactos.", icon = R.drawable.ic_contacts)
                        }
                        current.value.forEach { item ->
                            Surface(
                                onClick = {
                                    contact = item
                                    step = SendStep.AMOUNT
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = GlassPayColors.Card,
                                border = BorderStroke(1.dp, GlassPayColors.Line),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Avatar(item.displayName)
                                    Text(item.displayName, style = MaterialTheme.typography.titleMedium, color = GlassPayColors.Text, modifier = Modifier.weight(1f))
                                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = GlassPayColors.MutedText)
                                }
                            }
                        }
                    }
                }
            }
            SendStep.AMOUNT -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(contact?.displayName.orEmpty(), size = 48.dp)
                    Column {
                        Overline("Enviar a")
                        Text(contact?.displayName.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = GlassPayColors.Text)
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.take(12) },
                    label = { Text("Monto (MXN)") },
                    prefix = { Text("$") },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = MaterialTheme.typography.headlineMedium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = MaterialTheme.shapes.medium,
                    colors = gpTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(MAX_TRANSFER_NOTE_LENGTH) },
                    label = { Text("Nota (opcional)") },
                    enabled = !busy,
                    shape = MaterialTheme.shapes.medium,
                    colors = gpTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { InlineNotice(it, kind = StatusKind.FAILURE) }
                GpButton(
                    if (busy) "Revisando…" else "Revisar",
                    onClick = { requestPreview() },
                    enabled = amountText.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SendStep.PREVIEW -> {
                val proposal = preview
                if (proposal != null) {
                    val expired = remaining <= 0
                    GpCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), spacing = 16.dp) {
                        Overline("Revisa el envío")
                        MoneyText(formatMoney(proposal.amount, proposal.currency), style = MaterialTheme.typography.displaySmall)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Destinatario", color = GlassPayColors.MutedText, style = MaterialTheme.typography.bodySmall)
                            Text(proposal.recipientName, style = MaterialTheme.typography.bodyLarge, color = GlassPayColors.Text)
                        }
                        proposal.note?.let {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Nota", color = GlassPayColors.MutedText, style = MaterialTheme.typography.bodySmall)
                                Text(it, style = MaterialTheme.typography.bodyLarge, color = GlassPayColors.Text)
                            }
                        }
                        HorizontalDivider(color = GlassPayColors.Line)
                        val timeColor = if (expired) GlassPayColors.Error else GlassPayColors.Warning
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_clock), contentDescription = null, tint = timeColor, modifier = Modifier.size(20.dp))
                            Text(
                                if (!expired) "Vence en ${remaining} s" else "La vista previa venció. Revísala de nuevo.",
                                color = timeColor,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    InlineNotice("Para enviar te pediremos tu huella o el bloqueo del teléfono.", icon = R.drawable.ic_lock)
                    message?.let { InlineNotice(it, kind = StatusKind.FAILURE) }
                    GpButton(
                        "Confirmar envío",
                        onClick = { confirmWithAuth() },
                        enabled = !expired && !busy && !confirmBlocked && !locked,
                        icon = R.drawable.ic_lock,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (expired) {
                        GpButton(
                            "Revisar de nuevo",
                            onClick = {
                                preview = null
                                message = null
                                step = SendStep.AMOUNT
                            },
                            kind = GpButtonKind.SECONDARY,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (confirmBlocked) {
                        GpButton("Ver en Movimientos", onClick = onOpenMovements, kind = GpButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            SendStep.RESULT -> ResultView(result, pollGaveUp, onBack, onOpenMovements)
        }
        }
    }
}

@Composable
private fun ResultView(
    result: TransferResult?,
    gaveUp: Boolean,
    onDone: () -> Unit,
    onOpenMovements: () -> Unit,
) {
    val outcome = result ?: return
    val animate = animationsEnabled()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (outcome.state) {
            "completada" -> {
                var entered by remember { mutableStateOf(!animate) }
                LaunchedEffect(Unit) { entered = true }
                val scale by animateFloatAsState(
                    targetValue = if (entered) 1f else 0.6f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
                    label = "sentScale",
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(156.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .background(GlassPayColors.SuccessSoft, CircleShape)
                        .semantics { contentDescription = "Envío completado" },
                ) {
                    Box(Modifier.size(104.dp).background(GlassPayColors.Success, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = GlassPayColors.Background, modifier = Modifier.size(56.dp))
                    }
                }
                Text("¡Enviado!", style = MaterialTheme.typography.headlineMedium, color = GlassPayColors.Success)
                MoneyText(formatMoney(outcome.amount, outcome.currency), style = MaterialTheme.typography.displaySmall)
                Text("a ${outcome.recipientName}", color = GlassPayColors.MutedText, style = MaterialTheme.typography.bodyLarge)
            }
            "fallida" -> {
                IconBadge(R.drawable.ic_close, tint = GlassPayColors.Error, size = 88.dp, background = GlassPayColors.ErrorSoft)
                Text(
                    "El envío no se pudo completar. Tu saldo no se descontó.",
                    color = GlassPayColors.Error,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
            else -> {
                if (animate && !gaveUp) {
                    CircularProgressIndicator(
                        color = GlassPayColors.Accent,
                        modifier = Modifier.size(56.dp).semantics { contentDescription = "Enviando" },
                    )
                } else {
                    IconBadge(R.drawable.ic_clock, tint = GlassPayColors.Warning, size = 88.dp, background = GlassPayColors.WarningSoft)
                }
                Text("Enviando a ${outcome.recipientName}…", style = MaterialTheme.typography.headlineSmall, color = GlassPayColors.Text)
                MoneyText(formatMoney(outcome.amount, outcome.currency), style = MaterialTheme.typography.headlineLarge)
                if (gaveUp) {
                    InlineNotice("Tu envío sigue en proceso. Revísalo en Movimientos.", kind = StatusKind.PENDING)
                }
            }
        }
        GpButton("Listo", onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        GpButton("Ver en Movimientos", onClick = onOpenMovements, kind = GpButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
    }
}
