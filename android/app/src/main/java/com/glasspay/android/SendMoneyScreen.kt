package com.glasspay.android

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (step != SendStep.RESULT) {
            OutlinedButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) { Text("Volver") }
        }
        Text("Enviar dinero", style = MaterialTheme.typography.headlineMedium)
        when (step) {
            SendStep.PICK -> {
                Text("¿A quién le envías?", color = GlassPayColors.MutedText)
                when (val current = contacts) {
                    LoadState.Loading -> CircularProgressIndicator()
                    LoadState.Failed -> Text("No pudimos cargar tus contactos.", color = GlassPayColors.Error)
                    is LoadState.Loaded -> {
                        if (current.value.isEmpty()) {
                            Text("Aún no tienes contactos. Agrégalos desde Contactos.", color = GlassPayColors.MutedText)
                        }
                        current.value.forEach { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    contact = item
                                    step = SendStep.AMOUNT
                                },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(
                                        modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(item.displayName.trim().take(1).uppercase()) }
                                    Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
            SendStep.AMOUNT -> {
                Text("Para ${contact?.displayName.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.take(12) },
                    label = { Text("Monto (MXN)") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(MAX_TRANSFER_NOTE_LENGTH) },
                    label = { Text("Nota (opcional)") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { Text(it, color = GlassPayColors.Error) }
                Button(
                    onClick = { requestPreview() },
                    enabled = amountText.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Revisar") }
                if (busy) CircularProgressIndicator()
            }
            SendStep.PREVIEW -> {
                val proposal = preview
                if (proposal != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Vista previa", color = GlassPayColors.MutedText, style = MaterialTheme.typography.labelLarge)
                            Text(
                                formatMoney(proposal.amount, proposal.currency),
                                style = MaterialTheme.typography.displaySmall,
                            )
                            HorizontalDivider(color = Color(0xFF3A4260))
                            Text("Destinatario", color = GlassPayColors.MutedText, style = MaterialTheme.typography.labelLarge)
                            Text(proposal.recipientName, style = MaterialTheme.typography.titleMedium)
                            proposal.note?.let {
                                Text("Nota", color = GlassPayColors.MutedText, style = MaterialTheme.typography.labelLarge)
                                Text(it, style = MaterialTheme.typography.bodyLarge)
                            }
                            Text(
                                if (remaining > 0) "Vence en ${remaining}s" else "La vista previa venció. Revísala de nuevo.",
                                color = GlassPayColors.Warning,
                            )
                        }
                    }
                    message?.let { Text(it, color = GlassPayColors.Error) }
                    Button(
                        onClick = { confirmWithAuth() },
                        enabled = remaining > 0 && !busy && !confirmBlocked && !locked,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Confirmar envío") }
                    if (remaining <= 0) {
                        OutlinedButton(onClick = {
                            preview = null
                            message = null
                            step = SendStep.AMOUNT
                        }) { Text("Revisar de nuevo") }
                    }
                    if (confirmBlocked) {
                        OutlinedButton(onClick = onOpenMovements) { Text("Ver en Movimientos") }
                    }
                }
            }
            SendStep.RESULT -> ResultView(result, pollGaveUp, onBack, onOpenMovements)
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
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (outcome.state) {
            "completada" -> {
                var entered by remember { mutableStateOf(!animate) }
                LaunchedEffect(Unit) { entered = true }
                val scale by animateFloatAsState(
                    targetValue = if (entered) 1f else 0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                    label = "sentScale",
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(96.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .background(GlassPayColors.Success, CircleShape)
                        .semantics { contentDescription = "Envío completado" },
                ) { Text("✓", color = Color(0xFF0B1020), style = MaterialTheme.typography.displayMedium) }
                Text("¡Enviado!", style = MaterialTheme.typography.headlineLarge, color = GlassPayColors.Success)
                Text(formatMoney(outcome.amount, outcome.currency), style = MaterialTheme.typography.displaySmall)
                Text("a ${outcome.recipientName}", color = GlassPayColors.MutedText)
            }
            "fallida" -> {
                Text("El envío no se pudo completar. Tu saldo no se descontó.", color = GlassPayColors.Error,
                    style = MaterialTheme.typography.titleMedium)
            }
            else -> {
                if (animate && !gaveUp) {
                    CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Enviando" })
                }
                Text("Enviando a ${outcome.recipientName}…", style = MaterialTheme.typography.titleLarge)
                Text(formatMoney(outcome.amount, outcome.currency), style = MaterialTheme.typography.headlineMedium)
                if (gaveUp) {
                    Text("Tu envío sigue en proceso. Revísalo en Movimientos.", color = GlassPayColors.Warning)
                }
            }
        }
        Button(onClick = onDone) { Text("Listo") }
        OutlinedButton(onClick = onOpenMovements) { Text("Ver en Movimientos") }
    }
}
