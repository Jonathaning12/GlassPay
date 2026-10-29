import Foundation
import Observation

// Orquestación del cobro por QR + voz (en Android vivía dentro de ChargeScreen.kt). Las transiciones
// pasan siempre por ResolveStateMachine y ChargeStateMachine; la vista solo lee el estado y llama acciones.
// resolutionId, requestId y statusToken viven solo en memoria.

@MainActor
@Observable
final class ChargeViewModel {
    private(set) var resolveState: ResolveState = .scanning
    private(set) var chargeState: ChargeState = .ready()
    private(set) var voiceStatus: String?
    private(set) var lastRecognizedText: String?
    private(set) var savingContact = false
    private(set) var contactMessage: String?
    private(set) var confirmedCharge: ConfirmedCharge?
    private(set) var cameraError: String?
    private(set) var cameraGranted = QRScannerView.cameraAuthorized
    var textInput = ""
    /// Lo fija AppViewModel: bloqueada, la app no confirma nada (ni por toque ni por voz).
    var locked = false

    var accumulatedText: String? { inputSession?.text }
    var actions: Set<ChargeAction> { availableActions(chargeState) }

    @ObservationIgnored private let context: AppContext
    @ObservationIgnored private let onOpenMovements: () -> Void
    @ObservationIgnored private var resolveMachine = ResolveStateMachine()
    @ObservationIgnored private var chargeMachine = ChargeStateMachine()
    @ObservationIgnored private let voice = VoiceRecognizer()
    @ObservationIgnored private let speech = SpeechOutput()
    @ObservationIgnored private var inputSession: ChargeInputSession?
    @ObservationIgnored private var attemptCounter = 0
    @ObservationIgnored private var serverReady = false
    @ObservationIgnored private var rejectedToken: String?
    @ObservationIgnored private var announcedPaymentRequestId: String?
    @ObservationIgnored private var resolveTask: Task<Void, Never>?
    @ObservationIgnored private var chargeTask: Task<Void, Never>?
    @ObservationIgnored private var saveContactTask: Task<Void, Never>?
    @ObservationIgnored private var expiryTask: Task<Void, Never>?
    @ObservationIgnored private var countdownTask: Task<Void, Never>?
    @ObservationIgnored private var pollingTask: Task<Void, Never>?
    @ObservationIgnored private var expiryKey: String?
    @ObservationIgnored private var countdownKey: String?
    @ObservationIgnored private var pollingKey: String?

    init(context: AppContext, onOpenMovements: @escaping () -> Void) {
        self.context = context
        self.onOpenMovements = onOpenMovements
        voice.onResult = { [weak self] text in
            self?.voiceStatus = "Procesando voz…"
            self?.lastRecognizedText = text
            self?.handleRecognized(text)
        }
        voice.onReady = { [weak self] in self?.voiceStatus = "Escuchando…" }
        voice.onPartial = { [weak self] in self?.voiceStatus = "Escuchando…" }
        voice.onEnd = { [weak self] in self?.voiceStatus = "Procesando voz…" }
        voice.onError = { [weak self] kind in self?.voiceFailed(kind) }
    }

    // MARK: - Ciclo de vida

    func onAppear() async {
        if !cameraGranted {
            cameraGranted = await QRScannerView.requestCamera()
            if !cameraGranted { cameraError = "Necesitamos la cámara para leer el QR." }
        }
        do {
            try await context.api.warmUp()
            serverReady = true
        } catch {
            logFailure("health", error)
        }
    }

    /// Se llama al salir de la pestaña Cobrar: se descarta todo el flujo, como en Android.
    func tearDown() {
        [resolveTask, chargeTask, saveContactTask, expiryTask, countdownTask, pollingTask].forEach { $0?.cancel() }
        voice.release()
        speech.stop()
    }

    // MARK: - QR

    func resolveToken(_ token: String) {
        switch resolveState {
        case .identified, .failed: return
        case let .resolving(_, current, _) where current == token: return
        default: break
        }
        if token == rejectedToken { return }
        rejectedToken = nil
        resolveTask?.cancel()
        attemptCounter += 1
        let attemptId = attemptCounter
        let waking = !serverReady
        dispatch(.begin(attemptId: attemptId, token: token, waking: waking))
        resolveTask = Task {
            do {
                let resolution = try await context.api.resolveQrToken(context.session, token: token, timeout: waking ? 60 : 15)
                guard !Task.isCancelled else { return }
                serverReady = true
                inputSession = inputSessionAfter(inputSession, reason: .qrChanged)
                if chargeMachine.state.isReady { applyCharge(chargeMachine.resetForNewScan()) }
                dispatch(.success(attemptId: attemptId, resolution: resolution))
                Haptics.success()
            } catch {
                guard !Task.isCancelled, !error.isCancellation else { return }
                logFailure("resolve attempt=\(attemptId)", error)
                let type = error.apiFailure
                if type == .unauthorized { return invalidateSession(sessionInvalidMessage) }
                if type == .notFound || type == .conflict { rejectedToken = token }
                dispatch(.failure(attemptId: attemptId, type: ResolveFailureType(type)))
            }
        }
    }

    func retryResolve() {
        if case let .retryableError(_, token, _) = resolveState { resolveToken(token) }
    }

    func resetScan() { dispatch(.reset) }

    func applyContactResolution(_ resolution: PayerResolution) {
        scanAnotherQr()
        dispatch(.contactResolved(resolution))
    }

    func scanAnotherQr() {
        resolveTask?.cancel()
        chargeTask?.cancel()
        voice.release()
        speech.stop()
        inputSession = inputSessionAfter(inputSession, reason: .restarted)
        announcedPaymentRequestId = nil
        textInput = ""
        lastRecognizedText = nil
        voiceStatus = nil
        contactMessage = nil
        saveContactTask?.cancel()
        saveContactTask = nil
        savingContact = false
        confirmedCharge = nil
        applyCharge(chargeMachine.resetForNewScan())
        dispatch(.reset)
    }

    func saveContact() {
        guard case let .identified(_, resolutionId, _, contactId) = resolveState,
              !savingContact, contactId == nil else { return }
        savingContact = true
        contactMessage = nil
        saveContactTask = Task {
            defer { savingContact = false }
            do {
                let saved = try await context.api.saveContact(context.session, resolutionId: resolutionId)
                guard !Task.isCancelled else { return }
                dispatch(.contactSaved(contactId: saved.contactId))
                contactMessage = "Guardado en tus contactos"
                Haptics.success()
            } catch {
                guard !Task.isCancelled, !error.isCancellation else { return }
                logFailure("saveContact", error)
                switch error.apiFailure {
                case .unauthorized: invalidateSession(sessionInvalidMessage)
                case .notFound: contactMessage = "No se pudo guardar. Escanea su QR de nuevo."
                case .conflict: contactMessage = "Llegaste al máximo de contactos."
                case .rateLimited: contactMessage = "Demasiados intentos. Espera unos segundos."
                default: contactMessage = "No se pudo guardar. Revisa tu conexión."
                }
            }
        }
    }

    // MARK: - Voz y texto

    func startChargeInput() {
        if chargeMachine.state.isReady { lastRecognizedText = nil }
        applyCharge(chargeMachine.beginListening())
        speech.speak("Dicta el monto y el concepto del cobro.") { [weak self] in self?.startVoiceAfterSpeech() }
    }

    func retry() {
        applyCharge(chargeMachine.startOver())
        startChargeInput()
    }

    func interpretTextInput() {
        if chargeState.isReady || chargeState.isClarification { applyCharge(chargeMachine.beginListening()) }
        interpret(textInput)
    }

    private func startVoiceAfterSpeech() {
        guard chargeMachine.state.isListening || chargeMachine.state.isPreview else { return }
        if !VoiceRecognizer.hasPermissions {
            Task {
                let granted = await VoiceRecognizer.requestPermissions()
                guard chargeMachine.state.isListening || chargeMachine.state.isPreview else { return }
                if granted {
                    startVoiceAfterSpeech()
                } else {
                    applyCharge(chargeMachine.inputError("Permiso de micrófono denegado. Usa la entrada por texto."))
                }
            }
            return
        }
        guard voice.isAvailable else {
            applyCharge(chargeMachine.inputError("El reconocimiento de voz no está disponible. Usa la entrada por texto."))
            return
        }
        voiceStatus = "Preparando micrófono…"
        voice.start()
    }

    private func voiceFailed(_ kind: VoiceErrorKind) {
        guard shouldAcceptVoiceError(chargeState) else { return }
        voiceStatus = nil
        inputSession = inputSessionAfterDecision(inputSession, reason: .voiceError)
        applyCharge(chargeMachine.inputError(kind.message))
    }

    private func handleRecognized(_ text: String) {
        switch voiceCommand(text, state: chargeState) {
        case .cancel: cancel()
        case .startOver: startOver()
        case .confirm: if !locked { confirm() }
        case .none: if chargeState.isListening { interpret(text) }
        }
    }

    private func interpret(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, chargeState.isListening,
              case let .identified(_, resolutionId, _, _) = resolveState else { return }
        let next: ChargeInputSession?
        if let session = inputSession, session.resolutionId == resolutionId {
            switch session.appendClarification(text) {
            case let .accepted(updated): next = updated
            case .tooManyRounds:
                inputSession = nil
                applyCharge(chargeMachine.restartInput("No pude completar la aclaración. Empieza el cobro otra vez."))
                return
            case .tooLong:
                inputSession = nil
                applyCharge(chargeMachine.restartInput("La frase es demasiado larga. Empieza el cobro otra vez."))
                return
            case .invalid:
                return
            }
        } else {
            next = ChargeInputSession.start(resolutionId: resolutionId, text: text)
        }
        guard let next else {
            inputSession = nil
            applyCharge(chargeMachine.restartInput("La frase es demasiado larga. Empieza el cobro otra vez."))
            return
        }
        inputSession = next
        chargeTask?.cancel()
        applyCharge(chargeMachine.beginInterpretation())
        chargeTask = Task {
            do {
                let result = try await context.api.interpret(context.session, resolutionId: resolutionId, text: next.text)
                guard !Task.isCancelled else { return }
                if let proposal = result.proposal {
                    inputSession = inputSessionAfter(inputSession, reason: .proposalCreated)
                    lastRecognizedText = nil
                    applyCharge(chargeMachine.preview(proposal, remainingSeconds: remainingSeconds(proposal.expiresAt)))
                    speech.speak("Cobro de \(proposal.amount) \(proposal.currency) por \(proposal.concept). ¿Confirmas?") {
                        self.startVoiceAfterSpeech()
                    }
                } else {
                    let question = result.clarificationQuestion.nonEmpty ?? "¿Qué dato debo aclarar?"
                    inputSession = inputSessionAfterDecision(inputSession, reason: .serverClarification)
                    applyCharge(chargeMachine.clarification(question))
                    speech.speak(question)
                }
            } catch {
                guard !Task.isCancelled, !error.isCancellation else { return }
                logFailure("interpret", error)
                let type = error.apiFailure
                if type == .unauthorized { return invalidateSession(sessionInvalidMessage) }
                let reason: InputSessionDecisionReason
                switch type {
                case .notFound, .invalidResponse: reason = .interpretDefinitiveError
                case .network: reason = .interpretNetwork
                case .timeout: reason = .interpretTimeout
                case .rateLimited: reason = .interpretRateLimit
                default: reason = .serverClarification
                }
                inputSession = inputSessionAfterDecision(inputSession, reason: reason)
                applyCharge(chargeMachine.failure(chargeFailureType(type)))
            }
        }
    }

    // MARK: - Vista previa y confirmación

    func confirm() {
        guard !locked, case let .preview(proposal, remaining, _) = chargeState, remaining > 0 else { return }
        voice.release()
        chargeTask?.cancel()
        lastRecognizedText = nil
        if case let .identified(name, _, _, _) = resolveState {
            confirmedCharge = ConfirmedCharge(amount: proposal.amount, currency: proposal.currency, concept: proposal.concept, payerName: name)
        }
        applyCharge(chargeMachine.beginConfirmation())
        chargeTask = Task {
            do {
                // Solo viaja el id de la propuesta; el servidor usa el monto y el concepto que guardó.
                let created = try await context.api.confirm(context.session, proposalId: proposal.proposalId)
                applyCharge(chargeMachine.created(requestId: created.requestId, statusToken: created.statusToken))
                speech.speak("Solicitud enviada al pagador.")
            } catch {
                guard !error.isCancellation else { return }
                logFailure("confirm", error)
                switch error.apiFailure {
                case .unauthorized:
                    // El servidor autentica antes de consumir la propuesta: el cobro no se envió.
                    invalidateSession(sessionInvalidConfirmMessage)
                case .notFound, .invalidResponse:
                    applyCharge(chargeMachine.failure(.proposalInvalid))
                default:
                    applyCharge(chargeMachine.confirmationFailure())
                }
            }
        }
    }

    func cancel() {
        voice.cancel()
        chargeTask?.cancel()
        inputSession = inputSessionAfter(inputSession, reason: .cancelled)
        lastRecognizedText = nil
        applyCharge(chargeMachine.cancel())
        speech.speak("Cobro cancelado.")
    }

    func startOver() {
        voice.release()
        chargeTask?.cancel()
        inputSession = inputSessionAfter(inputSession, reason: .restarted)
        textInput = ""
        lastRecognizedText = nil
        voiceStatus = nil
        speech.stop()
        confirmedCharge = nil
        applyCharge(chargeMachine.startOver())
    }

    func back() {
        voice.release()
        speech.stop()
        voiceStatus = nil
        lastRecognizedText = nil
        applyCharge(chargeMachine.back())
    }

    func stopWaiting() {
        speech.stop()
        lastRecognizedText = nil
        confirmedCharge = nil
        applyCharge(chargeMachine.stopWaiting())
    }

    func openMovements() { onOpenMovements() }

    // MARK: - Efectos con tiempo (equivalentes a los LaunchedEffect de Android)

    private func dispatch(_ event: ResolveEvent) {
        resolveState = resolveMachine.dispatch(event)
        syncEffects()
    }

    private func applyCharge(_ state: ChargeState) {
        chargeState = state
        syncEffects()
    }

    private func syncEffects() {
        syncResolutionExpiry()
        syncCountdown()
        syncPolling()
    }

    private var holdsPaymentRequest: Bool {
        switch chargeState {
        case .confirming, .created: return true
        case let .error(_, recoverable): return !recoverable
        default: return false
        }
    }

    /// Al vencer la resolución (5 min) se vuelve a pedir un QR, salvo que haya un cobro en curso.
    private func syncResolutionExpiry() {
        let key: String?
        if case let .identified(_, resolutionId, _, _) = resolveState { key = "\(resolutionId)|\(holdsPaymentRequest)" } else { key = nil }
        guard key != expiryKey else { return }
        expiryKey = key
        expiryTask?.cancel()
        guard case let .identified(_, _, expiresAt, _) = resolveState else { return }
        let identified = resolveState
        let holds = holdsPaymentRequest
        expiryTask = Task {
            let remaining = remainingSeconds(expiresAt)
            if remaining > 0 { try? await Task.sleep(for: .seconds(remaining)) }
            guard !Task.isCancelled, !holds, resolveMachine.state == identified else { return }
            resolveTask?.cancel()
            chargeTask?.cancel()
            saveContactTask?.cancel()
            saveContactTask = nil
            savingContact = false
            contactMessage = nil
            voice.release()
            inputSession = inputSessionAfter(inputSession, reason: .resolutionExpired)
            textInput = ""
            lastRecognizedText = nil
            voiceStatus = nil
            let kept: String?
            if case let .ready(message) = chargeMachine.state { kept = message } else { kept = nil }
            applyCharge(chargeMachine.resetForNewScan(message: kept ?? "La resolución venció. Escanea otro QR."))
            dispatch(.reset)
        }
    }

    private func syncCountdown() {
        let proposalId: String?
        if case let .preview(proposal, _, _) = chargeState { proposalId = proposal.proposalId } else { proposalId = nil }
        guard proposalId != countdownKey else { return }
        countdownKey = proposalId
        countdownTask?.cancel()
        guard let proposalId else { return }
        countdownTask = Task {
            while !Task.isCancelled {
                guard case let .preview(proposal, _, _) = chargeMachine.state, proposal.proposalId == proposalId else { break }
                let remaining = remainingSeconds(proposal.expiresAt)
                applyCharge(chargeMachine.tick(remainingSeconds: remaining))
                if remaining <= 0 {
                    inputSession = inputSessionAfter(inputSession, reason: .proposalExpired)
                    break
                }
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    /// Consulta el estado cada 2 s. Solo el servidor (vía webhook verificado de PayPal) marca "pagada".
    private func syncPolling() {
        let requestId: String?
        if case let .created(id, _, _) = chargeState { requestId = id } else { requestId = nil }
        guard requestId != pollingKey else { return }
        pollingKey = requestId
        pollingTask?.cancel()
        guard let requestId else { return }
        pollingTask = Task {
            let startedAt = Date()
            while !Task.isCancelled {
                guard case let .created(currentId, token, payment) = chargeMachine.state,
                      currentId == requestId, !isTerminal(payment) else { break }
                let elapsed = Int(Date().timeIntervalSince(startedAt))
                do {
                    let status = try await context.api.paymentStatus(requestId: requestId, statusToken: token)
                    guard !Task.isCancelled else { break }
                    var tracking = paymentTrackingFor(serverState: status.state)
                    if tracking == .waiting { tracking = .waitingWithMessage(waitingPaymentMessage(elapsedSeconds: elapsed)) }
                    let updated = chargeMachine.paymentUpdated(tracking)
                    applyCharge(updated)
                    guard case let .created(updatedId, _, updatedPayment) = updated, updatedId == requestId else { break }
                    if updatedPayment == .paid && announcedPaymentRequestId != requestId {
                        announcedPaymentRequestId = requestId
                        Haptics.success()
                        speech.speak("Pago recibido.")
                    }
                    if isTerminal(tracking) { break }
                } catch {
                    guard !error.isCancellation else { break }
                    logFailure("payment status", error)
                    let type = error.apiFailure
                    if type == .rateLimited {
                        applyCharge(chargeMachine.paymentUpdated(.error("Demasiados intentos. Espera unos segundos.")))
                        break
                    }
                    if type != .network && type != .timeout {
                        applyCharge(chargeMachine.paymentUpdated(.error("No pudimos consultar el estado del pago.")))
                        break
                    }
                    if payment != .approved {
                        applyCharge(chargeMachine.paymentUpdated(.waitingWithMessage(waitingPaymentMessage(elapsedSeconds: elapsed))))
                    }
                }
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    private func invalidateSession(_ message: String) {
        scanAnotherQr()
        context.invalidate(message)
    }

    private func chargeFailureType(_ type: ApiFailureType) -> ChargeFailureType {
        switch type {
        case .unauthorized, .badRequest, .conflict, .server: return .server
        case .network: return .network
        case .timeout: return .timeout
        case .rateLimited: return .rateLimited
        case .notFound, .invalidResponse: return .proposalInvalid
        }
    }
}
