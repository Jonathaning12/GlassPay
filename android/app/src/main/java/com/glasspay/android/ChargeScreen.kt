package com.glasspay.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.Executors

const val SESSION_INVALID_MESSAGE = "Tu sesión ya no es válida. Crea tu cuenta de nuevo."
const val SESSION_INVALID_CONFIRM_MESSAGE =
    "Tu sesión ya no es válida. El cobro no se envió. Crea tu cuenta de nuevo."

@Composable
fun ChargeScreen(
    session: CollectorSession,
    api: PaymentApi,
    onSessionInvalid: (message: String) -> Unit,
    onFlowStateChange: (ResolveState, ChargeState) -> Unit,
) {
    val collector = session
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val stateMachine = remember { ResolveStateMachine() }
    val chargeMachine = remember { ChargeStateMachine() }
    val speechOutput = remember { SpeechOutput(context) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var uiState by remember { mutableStateOf<ResolveState>(ResolveState.Scanning) }
    var chargeState by remember { mutableStateOf<ChargeState>(ChargeState.Ready()) }
    var attemptCounter by remember { mutableStateOf(0L) }
    var serverReady by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var chargeJob by remember { mutableStateOf<Job?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var rejectedToken by remember { mutableStateOf<String?>(null) }
    var textInput by remember { mutableStateOf("") }
    var microphoneRequested by remember { mutableStateOf(false) }
    var recognizedText by remember { mutableStateOf<String?>(null) }
    var lastRecognizedText by remember { mutableStateOf<String?>(null) }
    var startVoiceWhenPermissionGranted by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var announcedPaymentRequestId by remember { mutableStateOf<String?>(null) }
    var inputSession by remember { mutableStateOf<ChargeInputSession?>(null) }
    var invalidMessage by remember { mutableStateOf<String?>(null) }

    fun dispatch(event: ResolveEvent) {
        uiState = stateMachine.dispatch(event)
    }

    fun dispatchCharge(next: ChargeState) {
        chargeState = next
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (!granted) cameraError = "Necesitamos la cámara para leer el QR."
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        audioGranted = granted
        microphoneRequested = false
        if (!granted) {
            startVoiceWhenPermissionGranted = false
            dispatchCharge(
                chargeMachine.inputError("Permiso de micrófono denegado. Usa la entrada por texto."),
            )
        } else if (startVoiceWhenPermissionGranted) {
            startVoiceWhenPermissionGranted = false
        }
    }

    val voiceRecognizer = remember(audioGranted) {
        VoiceRecognizer(
            context = context,
            onResult = { text ->
                voiceStatus = "Procesando voz…"
                lastRecognizedText = text
                recognizedText = text
            },
            onReady = { voiceStatus = "Escuchando…" },
            onEnd = { voiceStatus = "Procesando voz…" },
            onPartial = { voiceStatus = "Escuchando…" },
            reportError = { errorCode, message ->
                if (!shouldAcceptVoiceError(chargeState)) {
                    return@VoiceRecognizer
                }
                voiceStatus = null
                inputSession = inputSessionAfterVoiceError(inputSession, errorCode)
                dispatchCharge(chargeMachine.inputError(message))
            },
        )
    }

    DisposableEffect(voiceRecognizer) {
        onDispose {
            voiceRecognizer.destroy()
        }
    }

    DisposableEffect(Unit) {
        onDispose { speechOutput.shutdown() }
    }

    fun startVoiceAfterSpeech(action: () -> Unit) {
        if (chargeMachine.state !is ChargeState.Listening &&
            chargeMachine.state !is ChargeState.Preview
        ) return
        if (!audioGranted) {
            microphoneRequested = true
            startVoiceWhenPermissionGranted = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!voiceRecognizer.isAvailable()) {
            dispatchCharge(
                chargeMachine.inputError("El reconocimiento de voz no está disponible. Usa la entrada por texto."),
            )
            return
        }
        voiceStatus = "Preparando micrófono…"
        action()
    }

    fun startChargeInput() {
        if (chargeMachine.state is ChargeState.Ready) {
            lastRecognizedText = null
            recognizedText = null
        }
        dispatchCharge(chargeMachine.beginListening())
        speechOutput.speak(
            "Dicta el monto y el concepto del cobro.",
            "charge_prompt",
        ) {
            startVoiceAfterSpeech { voiceRecognizer.start() }
        }
    }

    fun interpretText(text: String) {
        if (text.isBlank() || chargeState !is ChargeState.Listening) return
        val resolution = uiState as? ResolveState.Identified ?: return
        val session = inputSession
        val nextSession = if (session == null || session.resolutionId != resolution.resolutionId) {
            ChargeInputSession.start(resolution.resolutionId, text)
        } else {
            when (val result = session.appendClarification(text)) {
                is ChargeInputAppendResult.Accepted -> result.session
                ChargeInputAppendResult.TooManyRounds -> {
                    inputSession = null
                    dispatchCharge(
                        chargeMachine.restartInput("No pude completar la aclaración. Empieza el cobro otra vez."),
                    )
                    return
                }
                ChargeInputAppendResult.TooLong -> {
                    inputSession = null
                    dispatchCharge(chargeMachine.restartInput("La frase es demasiado larga. Empieza el cobro otra vez."))
                    return
                }
                ChargeInputAppendResult.Invalid -> return
            }
        }
        if (nextSession == null) {
            inputSession = null
            dispatchCharge(chargeMachine.restartInput("La frase es demasiado larga. Empieza el cobro otra vez."))
            return
        }
        inputSession = nextSession
        chargeJob?.cancel()
        dispatchCharge(chargeMachine.beginInterpretation())
        chargeJob = scope.launch {
            try {
                val result = api.interpret(collector, resolution.resolutionId, nextSession.text)
                val next = result.proposal
                if (next == null) {
                    inputSession = inputSessionAfterDecision(
                        inputSession,
                        InputSessionDecisionReason.SERVER_CLARIFICATION,
                    )
                    dispatchCharge(chargeMachine.clarification(result.clarificationQuestion ?: "¿Qué dato debo aclarar?"))
                    speechOutput.speak(result.clarificationQuestion ?: "¿Qué dato debo aclarar?", "clarification")
                } else {
                    inputSession = inputSessionAfter(inputSession, ChargeInputClearReason.PROPOSAL_CREATED)
                    lastRecognizedText = null
                    recognizedText = null
                    val remaining = remainingSeconds(next.expiresAt)
                    dispatchCharge(chargeMachine.preview(next, remaining))
                    speechOutput.speak(
                        "Cobro de ${next.amount} ${next.currency} por ${next.concept}. ¿Confirmas?",
                        "charge_preview",
                    ) {
                        startVoiceAfterSpeech { voiceRecognizer.start() }
                    }
                }
            } catch (exception: ApiException) {
                Log.i("GlassPay", "interpret failure type=${exception.type} httpCode=${exception.httpCode}")
                if (isSessionInvalid(exception.type)) {
                    invalidMessage = SESSION_INVALID_MESSAGE
                    return@launch
                }
                if (exception.type == ApiFailureType.NOT_FOUND ||
                    exception.type == ApiFailureType.INVALID_RESPONSE
                ) {
                    inputSession = inputSessionAfterDecision(
                        inputSession,
                        InputSessionDecisionReason.INTERPRET_DEFINITIVE_ERROR,
                    )
                } else {
                    inputSession = inputSessionAfterDecision(
                        inputSession,
                        when (exception.type) {
                            ApiFailureType.NETWORK -> InputSessionDecisionReason.INTERPRET_NETWORK
                            ApiFailureType.TIMEOUT -> InputSessionDecisionReason.INTERPRET_TIMEOUT
                            ApiFailureType.RATE_LIMITED -> InputSessionDecisionReason.INTERPRET_RATE_LIMIT
                            else -> InputSessionDecisionReason.SERVER_CLARIFICATION
                        },
                    )
                }
                dispatchCharge(chargeMachine.failure(exception.type.toChargeFailureType()))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                Log.i("GlassPay", "interpret failure type=UNKNOWN httpCode=none")
                inputSession = inputSessionAfterDecision(
                    inputSession,
                    InputSessionDecisionReason.INTERPRET_NETWORK,
                )
                dispatchCharge(chargeMachine.failure(ChargeFailureType.NETWORK))
            }
        }
    }

    fun confirmCharge() {
        val preview = chargeState as? ChargeState.Preview ?: return
        if (preview.remainingSeconds <= 0 || chargeJob?.isActive == true) return
        voiceRecognizer.release()
        chargeJob?.cancel()
        lastRecognizedText = null
        recognizedText = null
        dispatchCharge(chargeMachine.beginConfirmation())
        chargeJob = scope.launch {
            try {
                val result = api.confirm(collector, preview.proposal.proposalId)
                dispatchCharge(chargeMachine.created(result.requestId, result.statusToken))
                speechOutput.speak("Solicitud enviada al pagador.", "request_created")
            } catch (exception: ApiException) {
                Log.i("GlassPay", "confirm failure type=${exception.type} httpCode=${exception.httpCode}")
                if (isSessionInvalid(exception.type)) {
                    // El servidor autentica antes de consumir la propuesta: el cobro no se envió.
                    invalidMessage = SESSION_INVALID_CONFIRM_MESSAGE
                    return@launch
                }
                if (exception.type == ApiFailureType.NOT_FOUND || exception.type == ApiFailureType.INVALID_RESPONSE) {
                    dispatchCharge(chargeMachine.failure(ChargeFailureType.PROPOSAL_INVALID))
                } else {
                    dispatchCharge(chargeMachine.confirmationFailure())
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                Log.i("GlassPay", "confirm failure type=UNKNOWN httpCode=none")
                dispatchCharge(chargeMachine.confirmationFailure())
            }
        }
    }

    fun cancelCharge() {
        voiceRecognizer.cancel()
        chargeJob?.cancel()
        inputSession = inputSessionAfter(inputSession, ChargeInputClearReason.CANCELLED)
        lastRecognizedText = null
        recognizedText = null
        dispatchCharge(chargeMachine.cancel())
        speechOutput.speak("Cobro cancelado.", "charge_cancelled")
    }

    fun startOverCharge() {
        voiceRecognizer.release()
        chargeJob?.cancel()
        inputSession = inputSessionAfter(inputSession, ChargeInputClearReason.RESTARTED)
        textInput = ""
        lastRecognizedText = null
        recognizedText = null
        voiceStatus = null
        speechOutput.stop()
        dispatchCharge(chargeMachine.startOver())
    }

    fun interpretTextInput(text: String) {
        if (chargeState is ChargeState.Ready || chargeState is ChargeState.Clarification) {
            dispatchCharge(chargeMachine.beginListening())
        }
        interpretText(text)
    }

    LaunchedEffect(recognizedText) {
        val text = recognizedText ?: return@LaunchedEffect
        recognizedText = null
        when (voiceCommand(text, chargeState)) {
            VoiceCommand.CANCEL -> cancelCharge()
            VoiceCommand.START_OVER -> startOverCharge()
            VoiceCommand.CONFIRM -> confirmCharge()
            VoiceCommand.NONE -> if (chargeState is ChargeState.Listening) interpretText(text)
        }
    }

    LaunchedEffect(startVoiceWhenPermissionGranted, audioGranted, chargeState) {
        if (startVoiceWhenPermissionGranted && audioGranted &&
            (chargeState is ChargeState.Preview || chargeState is ChargeState.Listening)
        ) {
            startVoiceWhenPermissionGranted = false
            voiceRecognizer.start()
        }

    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        launch {
            try {
                api.warmUp()
                serverReady = true
            } catch (exception: ApiException) {
                Log.i("GlassPay", "health failure type=${exception.type} httpCode=${exception.httpCode}")
            }
        }
    }

    fun cancelActiveRequest() {
        activeJob?.cancel()
        activeJob = null
    }

    fun scanAnotherQr() {
        cancelActiveRequest()
        chargeJob?.cancel()
        voiceRecognizer.release()
        speechOutput.stop()
        inputSession = inputSessionAfter(inputSession, ChargeInputClearReason.RESTARTED)
        announcedPaymentRequestId = null
        textInput = ""
        recognizedText = null
        lastRecognizedText = null
        voiceStatus = null
        microphoneRequested = false
        startVoiceWhenPermissionGranted = false
        dispatchCharge(chargeMachine.resetForNewScan())
        dispatch(ResolveEvent.Reset)
    }

    LaunchedEffect(invalidMessage) {
        val message = invalidMessage ?: return@LaunchedEffect
        invalidMessage = null
        scanAnotherQr()
        onSessionInvalid(message)
    }

    LaunchedEffect(uiState, chargeState) {
        onFlowStateChange(uiState, chargeState)
    }

    fun resolveToken(token: String) {
        if (uiState is ResolveState.Identified || uiState is ResolveState.Failed) return
        if (token == rejectedToken) return
        rejectedToken = null
        if (uiState is ResolveState.Resolving &&
            (uiState as ResolveState.Resolving).token == token
        ) return
        cancelActiveRequest()
        attemptCounter += 1
        val attemptId = attemptCounter
        val waking = !serverReady
        dispatch(ResolveEvent.Begin(attemptId, token, waking))
        activeJob = scope.launch {
            try {
                val resolution = api.resolveQrToken(collector, token, if (waking) 60_000 else 15_000)
                serverReady = true
                inputSession = inputSessionAfter(inputSession, ChargeInputClearReason.QR_CHANGED)
                if (chargeMachine.state is ChargeState.Ready) {
                    dispatchCharge(chargeMachine.resetForNewScan())
                }
                dispatch(
                    ResolveEvent.Success(
                        attemptId,
                        resolution.displayName,
                        resolution.resolutionId,
                        resolution.expiresAt,
                    ),
                )
                vibrate(context)
            } catch (exception: ApiException) {
                if (isActive) {
                    Log.i("GlassPay", "resolve attempt=$attemptId failure type=${exception.type} httpCode=${exception.httpCode}")
                    if (isSessionInvalid(exception.type)) {
                        invalidMessage = SESSION_INVALID_MESSAGE
                        return@launch
                    }
                    if (exception.type == ApiFailureType.NOT_FOUND) rejectedToken = token
                    dispatch(ResolveEvent.Failure(attemptId, exception.type.toResolveFailureType()))
                }
            } catch (_: Exception) {
                if (isActive) {
                    Log.i("GlassPay", "resolve attempt=$attemptId failure type=UNKNOWN httpCode=none")
                    dispatch(ResolveEvent.Failure(attemptId, ResolveFailureType.NETWORK))
                }
            } finally {
                if (isActive) activeJob = null
            }
        }
    }

    val holdsPaymentRequest = chargeState is ChargeState.Confirming ||
        chargeState is ChargeState.Created ||
        (chargeState as? ChargeState.Error)?.recoverable == false
    LaunchedEffect(uiState, holdsPaymentRequest) {
        val identified = uiState as? ResolveState.Identified ?: return@LaunchedEffect
        val remaining = remainingSeconds(identified.expiresAt)
        if (remaining > 0) delay(remaining * 1_000)
        if (holdsPaymentRequest) return@LaunchedEffect
        if (stateMachine.state == identified) {
            cancelActiveRequest()
            chargeJob?.cancel()
            voiceRecognizer.release()
            inputSession = inputSessionAfter(inputSession, ChargeInputClearReason.RESOLUTION_EXPIRED)
            textInput = ""
            recognizedText = null
            lastRecognizedText = null
            voiceStatus = null
            dispatchCharge(chargeMachine.resetForNewScan(
                    (chargeMachine.state as? ChargeState.Ready)?.message ?: "La resolución venció. Escanea otro QR.",
                ))
            dispatch(ResolveEvent.Reset)
        }
    }

    LaunchedEffect(chargeState) {
        val proposalId = (chargeState as? ChargeState.Preview)?.proposal?.proposalId ?: return@LaunchedEffect
        while (isActive) {
            val preview = chargeMachine.state as? ChargeState.Preview ?: break
            if (preview.proposal.proposalId != proposalId) break
            val remaining = remainingSeconds(preview.proposal.expiresAt)
            dispatchCharge(chargeMachine.tick(remaining))
            if (remaining <= 0) {
                inputSession = inputSessionAfter(inputSession, ChargeInputClearReason.PROPOSAL_EXPIRED)
                break
            }
            delay(1_000)
        }
    }

    LaunchedEffect((chargeState as? ChargeState.Created)?.requestId) {
        val created = chargeState as? ChargeState.Created ?: return@LaunchedEffect
        val requestId = created.requestId
        val startedAt = System.currentTimeMillis()
        while (isActive) {
            val current = chargeMachine.state as? ChargeState.Created ?: break
            if (current.requestId != requestId || isTerminal(current.payment)) break
            val elapsed = (System.currentTimeMillis() - startedAt) / 1_000
            try {
                val status = api.getPaymentStatus(requestId, current.statusToken)
                val tracking = status.state.toPaymentTracking().let {
                    if (it == PaymentTracking.Waiting) {
                        PaymentTracking.WaitingWithMessage(waitingPaymentMessage(elapsed))
                    } else {
                        it
                    }
                }
                val updated = chargeMachine.paymentUpdated(tracking)
                dispatchCharge(updated)
                if (updated !is ChargeState.Created || updated.requestId != requestId) break
                if (updated.payment == PaymentTracking.Paid && announcedPaymentRequestId != requestId) {
                    announcedPaymentRequestId = requestId
                    vibrate(context)
                    speechOutput.speak("Pago recibido.", "payment_received")
                }
                if (isTerminal(tracking)) break
            } catch (exception: ApiException) {
                Log.i("GlassPay", "payment status failure type=${exception.type} httpCode=${exception.httpCode}")
                if (exception.type == ApiFailureType.RATE_LIMITED) {
                    dispatchCharge(
                        chargeMachine.paymentUpdated(
                            PaymentTracking.Error("Demasiados intentos. Espera unos segundos."),
                        ),
                    )
                    break
                }
                if (exception.type != ApiFailureType.NETWORK && exception.type != ApiFailureType.TIMEOUT) {
                    dispatchCharge(
                        chargeMachine.paymentUpdated(
                            PaymentTracking.Error("No pudimos consultar el estado del pago."),
                        ),
                    )
                    break
                }
                dispatchCharge(
                    chargeMachine.paymentUpdated(
                        PaymentTracking.WaitingWithMessage(waitingPaymentMessage(elapsed)),
                    ),
                )
            }
            delay(2_000)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1020))) {
        if (cameraGranted && uiState !is ResolveState.Identified) {
            CameraPreview(lifecycleOwner = lifecycleOwner, onTokenDetected = ::resolveToken)
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val state = uiState) {
                is ResolveState.Identified -> IdentifiedContent(
                    state = state,
                    chargeState = chargeState,
                    textInput = textInput,
                    onTextInput = { textInput = it },
                    onStartCharge = { startChargeInput() },
                    onInterpretText = { interpretTextInput(textInput) },
                    onConfirm = { confirmCharge() },
                    onCancel = { cancelCharge() },
                    onStartOver = { startOverCharge() },
                    onBack = {
                        voiceRecognizer.release()
                        speechOutput.stop()
                        voiceStatus = null
                        lastRecognizedText = null
                        recognizedText = null
                        dispatchCharge(chargeMachine.back())
                    },
                    onStopWaiting = {
                        speechOutput.stop()
                        lastRecognizedText = null
                        recognizedText = null
                        dispatchCharge(chargeMachine.stopWaiting())
                    },
                    onRetry = {
                        dispatchCharge(chargeMachine.startOver())
                        startChargeInput()
                    },
                    onReset = { scanAnotherQr() },
                    voiceStatus = voiceStatus,
                    lastRecognizedText = lastRecognizedText,
                    accumulatedText = inputSession?.text,
                )
                is ResolveState.Resolving -> {
                    Text(
                        if (state.waking) "Despertando el servidor…" else "Resolviendo QR…",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (state.waking) CircularProgressIndicator()
                }
                else -> ResolveContent(
                    state = uiState,
                    onRetry = { (uiState as? ResolveState.RetryableError)?.let { resolveToken(it.token) } },
                    cameraError = cameraError,
                )
            }
        }
    }
}

@Composable
private fun IdentifiedContent(
    state: ResolveState.Identified,
    chargeState: ChargeState,
    textInput: String,
    onTextInput: (String) -> Unit,
    onStartCharge: () -> Unit,
    onInterpretText: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onStartOver: () -> Unit,
    onBack: () -> Unit,
    onStopWaiting: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    voiceStatus: String?,
    lastRecognizedText: String?,
    accumulatedText: String?,
) {
    Text("Pagador identificado", color = Color.White, style = MaterialTheme.typography.headlineSmall)
    Text("Pagador: ${state.displayName}", color = Color.White, style = MaterialTheme.typography.titleLarge)
    val actions = availableActions(chargeState)
    when (val charge = chargeState) {
        is ChargeState.Ready -> {
            charge.message?.let { Text(it, color = Color(0xFFFFB4AB)) }
        }
        ChargeState.Listening -> Text(voiceStatus ?: "Preparando micrófono…", color = Color.White)
        ChargeState.Interpreting -> {
            Text("Interpretando…", color = Color.White)
            CircularProgressIndicator()
        }
        is ChargeState.Clarification -> {
            Text(charge.question, color = Color(0xFFFFDDB3))
        }
        is ChargeState.Preview -> {
            Text("Vista previa", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text("Monto: ${charge.proposal.amount}", color = Color.White)
            Text("Moneda: ${charge.proposal.currency}", color = Color.White)
            Text("Concepto: ${charge.proposal.concept}", color = Color.White)
            Text("Pagador: ${state.displayName}", color = Color.White)
            Text("Vence en ${charge.remainingSeconds}s", color = Color(0xFFFFDDB3))
            charge.message?.let { Text(it, color = Color(0xFFFFB4AB)) }
        }
        ChargeState.Confirming -> {
            Text("Confirmando…", color = Color.White)
            CircularProgressIndicator()
        }
        is ChargeState.Created -> {
            when (val payment = charge.payment) {
                PaymentTracking.Waiting -> Text("Esperando al pagador.", color = Color.White)
                is PaymentTracking.WaitingWithMessage -> Text(payment.message, color = Color.White)
                PaymentTracking.Paid -> Text("Pago recibido.", color = Color(0xFFB7F7C0))
                PaymentTracking.Rejected -> Text("El pagador rechazó la solicitud.", color = Color(0xFFFFB4AB))
                PaymentTracking.Expired -> Text("La solicitud expiró.", color = Color(0xFFFFDDB3))
                PaymentTracking.Failed -> Text("El pago falló.", color = Color(0xFFFFB4AB))
                is PaymentTracking.Error -> Text(payment.message, color = Color(0xFFFFB4AB))
            }
        }
        is ChargeState.Error -> {
            Text(charge.message, color = Color(0xFFFFB4AB))
        }
    }
    if (ChargeAction.CONFIRM in actions) Button(onClick = onConfirm) { Text("Confirmar") }
    if (ChargeAction.DICTATE in actions) Button(onClick = onStartCharge) { Text("Dictar cobro") }
    if (ChargeAction.ANSWER in actions) Button(onClick = onStartCharge) { Text("Responder") }
    if (ChargeAction.RETRY in actions) Button(onClick = onRetry) { Text("Intentar de nuevo") }
    if (ChargeAction.CANCEL in actions) Button(onClick = onCancel) { Text("Cancelar") }
    if (ChargeAction.START_OVER in actions) Button(onClick = onStartOver) { Text("Empezar de nuevo") }
    if (ChargeAction.BACK in actions) Button(onClick = onBack) { Text("Volver") }
    if (ChargeAction.STOP_WAITING in actions) Button(onClick = onStopWaiting) { Text("Dejar de esperar") }
    if (ChargeAction.SCAN_ANOTHER in actions) Button(onClick = onReset) { Text("Escanear otro QR") }
    lastRecognizedText?.takeIf { showsRecognizedText(chargeState) }?.let {
        Text("Entendí: ${displayText(it, 120)}", color = Color(0xFFB7D8FF))
    }
    if (showsAccumulatedText(chargeState)) {
        accumulatedText?.let {
            Text("Frase acumulada: ${displayText(it, 240)}", color = Color(0xFFB7D8FF))
        }
    }
    if (showsTextInput(chargeState)) {
        OutlinedTextField(
            value = textInput,
            onValueChange = onTextInput,
            label = { Text("Frase del cobro (opcional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onInterpretText,
            enabled = textInput.isNotBlank(),
        ) {
            Text("Interpretar texto")
        }
    }
}

@Composable
private fun ResolveContent(
    state: ResolveState,
    onRetry: () -> Unit,
    cameraError: String?,
) {
    Text(
        when (state) {
            is ResolveState.Failed -> state.message
            is ResolveState.RetryableError -> state.message
            else -> "Busca un QR de GlassPay"
        },
        color = if (state is ResolveState.Failed || state is ResolveState.RetryableError) Color(0xFFFFB4AB) else Color.White,
        style = MaterialTheme.typography.headlineSmall,
    )
    if (state is ResolveState.RetryableError) Button(onClick = onRetry) { Text("Reintentar") }
    cameraError?.let { Text(it, color = Color(0xFFFFB4AB)) }
}

@Composable
private fun CameraPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onTokenDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val latestCallback = androidx.compose.runtime.rememberUpdatedState(onTokenDetected)
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val boundProvider = remember { arrayOfNulls<ProcessCameraProvider>(1) }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            boundProvider[0]?.unbindAll()
            executor.shutdown()
            scanner.close()
        }
    }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context).get()
        if (disposed.get()) return@LaunchedEffect
        boundProvider[0] = provider
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { imageProxy ->
            if (disposed.get()) {
                imageProxy.close()
            } else {
                analyzeFrame(imageProxy, scanner) { token ->
                    if (!disposed.get()) latestCallback.value(token)
                }
            }
        }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun analyzeFrame(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onTokenDetected: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    try {
        scanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { QrTokenParser.extractToken(it.rawValue) }?.let(onTokenDetected)
            }
            .addOnCompleteListener { imageProxy.close() }
    } catch (_: Exception) {
        imageProxy.close()
    }
}

private fun remainingSeconds(expiresAt: String): Long {
    val expiration = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull() ?: return 0
    return ((expiration - System.currentTimeMillis()) / 1_000).coerceAtLeast(0)
}

private fun String.toPaymentTracking(): PaymentTracking {
    return when (this) {
        "pendiente_de_pagador",
        "aprobada" -> PaymentTracking.Waiting
        "pagada" -> PaymentTracking.Paid
        "rechazada" -> PaymentTracking.Rejected
        "expirada" -> PaymentTracking.Expired
        "fallida" -> PaymentTracking.Failed
        else -> PaymentTracking.Error("No pudimos consultar el estado del pago.")
    }
}

private fun waitingPaymentMessage(elapsedSeconds: Long): String {
    return when {
        elapsedSeconds < 4 -> "Confirmando tu pago con PayPal…"
        elapsedSeconds < 10 -> "Preparando tu comprobante…"
        elapsedSeconds < 20 -> "Terminando detalles…"
        elapsedSeconds < 30 -> "Esto está tardando un poco más de lo normal…"
        else -> "Tu pago está en proceso…"
    }
}

private fun vibrate(context: android.content.Context) {
    if (context.checkSelfPermission(android.Manifest.permission.VIBRATE) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) return
    val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    runCatching {
        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

private fun ApiFailureType.toResolveFailureType(): ResolveFailureType {
    return when (this) {
        ApiFailureType.UNAUTHORIZED -> ResolveFailureType.INVALID_RESPONSE
        ApiFailureType.NETWORK -> ResolveFailureType.NETWORK
        ApiFailureType.TIMEOUT -> ResolveFailureType.TIMEOUT
        ApiFailureType.NOT_FOUND -> ResolveFailureType.NOT_FOUND
        ApiFailureType.RATE_LIMITED -> ResolveFailureType.RATE_LIMITED
        ApiFailureType.SERVER -> ResolveFailureType.SERVER
        ApiFailureType.INVALID_RESPONSE -> ResolveFailureType.INVALID_RESPONSE
    }
}

private fun ApiFailureType.toChargeFailureType(): ChargeFailureType {
    return when (this) {
        ApiFailureType.UNAUTHORIZED -> ChargeFailureType.SERVER
        ApiFailureType.NETWORK -> ChargeFailureType.NETWORK
        ApiFailureType.TIMEOUT -> ChargeFailureType.TIMEOUT
        ApiFailureType.RATE_LIMITED -> ChargeFailureType.RATE_LIMITED
        ApiFailureType.NOT_FOUND,
        ApiFailureType.INVALID_RESPONSE -> ChargeFailureType.PROPOSAL_INVALID
        ApiFailureType.SERVER -> ChargeFailureType.SERVER
    }
}
