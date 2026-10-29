import Foundation
import Observation

// Envíos, asistente, programados y estadísticas (SendMoneyScreen, AssistantScreen, ScheduledScreen y
// StatsScreen en Android). Todo lo que mueve dinero pide vista previa + Face ID / código del teléfono.

@MainActor
@Observable
final class SendMoneyViewModel {
    enum Step { case pick, amount, preview, result }

    private(set) var step: Step
    private(set) var contact: Contact?
    private(set) var contacts: LoadState<[Contact]> = .loading
    var amountText = "" { didSet { if amountText.count > 12 { amountText = String(amountText.prefix(12)) } } }
    var note = "" { didSet { if note.count > maxTransferNoteLength { note = String(note.prefix(maxTransferNoteLength)) } } }
    private(set) var preview: TransferPreview?
    private(set) var remaining = 0
    private(set) var busy = false
    /// Tras un resultado incierto no se permite reintentar: se manda a revisar Movimientos.
    private(set) var confirmBlocked = false
    private(set) var message: String?
    private(set) var result: TransferResult?
    private(set) var pollGaveUp = false
    var locked = false

    @ObservationIgnored private let context: AppContext
    @ObservationIgnored private var countdownTask: Task<Void, Never>?

    init(context: AppContext, initialContact: Contact?) {
        self.context = context
        contact = initialContact
        step = initialContact == nil ? .pick : .amount
    }

    var canConfirm: Bool { remaining > 0 && !busy && !confirmBlocked && !locked }

    func loadContacts() async {
        guard step == .pick else { return }
        contacts = .loading
        do {
            contacts = .loaded(try await context.api.listContacts(context.session))
        } catch {
            guard !error.isCancellation else { return }
            logFailure("sendContacts", error)
            if error.apiFailure == .unauthorized { return context.invalidate(sessionInvalidMessage) }
            contacts = .failed
        }
    }

    func pick(_ contact: Contact) {
        self.contact = contact
        step = .amount
    }

    func requestPreview() {
        guard let target = contact else { return }
        guard let amount = parseAmountInput(amountText) else {
            message = "Escribe un monto válido (máximo 1,000.00)."
            return
        }
        guard !busy else { return }
        busy = true
        message = nil
        let trimmedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            defer { busy = false }
            do {
                let created = try await context.api.previewTransfer(
                    context.session, contactId: target.contactId, amount: amount, note: trimmedNote.isEmpty ? nil : trimmedNote
                )
                preview = created
                confirmBlocked = false
                step = .preview
                startCountdown(created.expiresAt)
            } catch {
                logFailure("transferPreview", error)
                switch error.apiFailure {
                case .unauthorized: context.invalidate(sessionInvalidMessage)
                case .notFound: message = "Este contacto ya no existe."
                case .network, .timeout: message = "Revisa tu conexión."
                case let type: message = transferErrorMessage(type, serverError: (error as? ApiError)?.serverError, recipientName: target.displayName)
                }
            }
        }
    }

    func reviewAgain() {
        preview = nil
        message = nil
        step = .amount
    }

    /// Siempre con Face ID / código: el servidor recibe solo el id de la propuesta, una sola vez.
    func confirmWithAuth() {
        guard let proposal = preview, canConfirm else { return }
        guard DeviceAuthenticator.availability == .available else {
            message = "Configura un bloqueo de pantalla para enviar dinero."
            return
        }
        busy = true
        message = nil
        Task {
            guard await DeviceAuthenticator.authenticate(reason: "Confirma el envío de \(formatMoney(proposal.amount, proposal.currency))") else {
                busy = false
                message = "Necesitamos confirmar que eres tú."
                return
            }
            do {
                result = try await context.api.confirmTransfer(context.session, transferProposalId: proposal.transferProposalId)
                pollGaveUp = false
                step = .result
            } catch {
                logFailure("transferConfirm", error)
                busy = false
                let type = error.apiFailure
                let serverError = (error as? ApiError)?.serverError
                switch type {
                case .unauthorized:
                    context.invalidate(sessionInvalidMessage)
                case .notFound:
                    message = transferErrorMessage(type, serverError: serverError, recipientName: proposal.recipientName)
                    preview = nil
                    step = .amount
                case .conflict, .badRequest, .rateLimited:
                    message = transferErrorMessage(type, serverError: serverError, recipientName: proposal.recipientName)
                default:
                    // Resultado incierto: no se reintenta automáticamente.
                    confirmBlocked = true
                    message = transferErrorMessage(type, serverError: serverError, recipientName: proposal.recipientName)
                }
            }
        }
    }

    /// Sigue el envío por su id en Movimientos, cada 3 s hasta 60 s, solo con la app visible y desbloqueada.
    func pollResult() async {
        guard let current = result, !locked, current.state != "completada", current.state != "fallida" else { return }
        for _ in 0..<20 {
            try? await Task.sleep(for: .seconds(3))
            if Task.isCancelled { return }
            do {
                let match = try await context.api.movements(context.session)
                    .first { $0.kind == "envio" && $0.movementId == current.transferId }
                if let match, match.state != current.state {
                    result?.state = match.state
                    return
                }
            } catch {
                if error.isCancellation { return }
                if error.apiFailure == .unauthorized { return context.invalidate(sessionInvalidMessage) }
            }
        }
        pollGaveUp = true
    }

    private func startCountdown(_ expiresAt: String) {
        countdownTask?.cancel()
        remaining = remainingSeconds(expiresAt)
        countdownTask = Task {
            while !Task.isCancelled, remaining > 0 {
                try? await Task.sleep(for: .seconds(1))
                remaining = remainingSeconds(expiresAt)
            }
        }
    }
}

@MainActor
@Observable
final class AssistantViewModel {
    private(set) var chat = ChatState()
    var input = ""
    private(set) var listening = false
    private(set) var voiceMessage: String?
    /// Interruptor por propuesta: los cobros se crean solos por defecto; los pagos NUNCA salen solos por defecto.
    private var autoExecute: [String: Bool] = [:]

    @ObservationIgnored private let context: AppContext
    @ObservationIgnored private let voice = VoiceRecognizer()

    init(context: AppContext) {
        self.context = context
        voice.onResult = { [weak self] text in
            self?.listening = false
            self?.input = String(text.prefix(maxAssistantMessageLength))
        }
        voice.onReady = { [weak self] in self?.listening = true }
        voice.onError = { [weak self] kind in
            self?.listening = false
            self?.voiceMessage = kind.message
        }
    }

    func autoExecute(for proposal: AssistantProposal) -> Bool {
        autoExecute[proposal.proposalId] ?? (proposal.action != "schedule_payment")
    }

    func setAutoExecute(_ value: Bool, for proposal: AssistantProposal) {
        autoExecute[proposal.proposalId] = value
    }

    /// El dictado solo llena el campo; el usuario decide enviarlo.
    func dictate() {
        voiceMessage = nil
        Task {
            if !VoiceRecognizer.hasPermissions, !(await VoiceRecognizer.requestPermissions()) {
                voiceMessage = "Sin permiso de micrófono. Escribe tu mensaje."
                return
            }
            voice.start()
        }
    }

    func stopDictation() {
        voice.release()
        listening = false
    }

    func send(_ text: String) {
        guard chat.canSend(text) else { return }
        chat.userSent(text)
        input = ""
        Task {
            do {
                chat.replied(try await context.api.sendAssistantMessage(context.session, text: text.trimmingCharacters(in: .whitespacesAndNewlines)))
            } catch {
                logFailure("assistant", error)
                if error.apiFailure == .unauthorized { return context.invalidate(sessionInvalidMessage) }
                chat.failed(assistantErrorMessage(error.apiFailure))
            }
        }
    }

    func discard(_ proposal: AssistantProposal) { chat.proposalDiscarded(proposal.proposalId) }

    func confirm(_ proposal: AssistantProposal) {
        guard chat.canConfirm(proposal.proposalId) else { return }
        let auto = autoExecute(for: proposal)
        guard proposal.action == "schedule_payment" else { return sendConfirmation(proposal, autoExecute: auto) }
        // Sale dinero: siempre vista previa (la tarjeta) + Face ID o código del teléfono.
        guard DeviceAuthenticator.availability == .available else {
            chat.proposalFailed(proposal.proposalId, message: "Configura un bloqueo de pantalla para programar pagos.")
            return
        }
        Task {
            if await DeviceAuthenticator.authenticate(reason: "Confirma el pago programado") {
                sendConfirmation(proposal, autoExecute: auto)
            } else {
                voiceMessage = "Necesitamos confirmar que eres tú para programar el pago."
            }
        }
    }

    private func sendConfirmation(_ proposal: AssistantProposal, autoExecute: Bool) {
        guard chat.canConfirm(proposal.proposalId) else { return }
        chat.proposalConfirming(proposal.proposalId)
        Task {
            // Para que el aviso de "espera tu confirmación" llegue con la app cerrada.
            if proposal.runAt != nil && !autoExecute { await ScheduledCheck.requestNotificationPermission() }
            do {
                let outcome = try await context.api.confirmAssistantProposal(
                    context.session, proposalId: proposal.proposalId, autoExecute: autoExecute
                )
                chat.proposalConfirmed(proposal.proposalId, message: proposalOutcomeMessage(outcome, autoExecute: autoExecute))
            } catch {
                logFailure("proposal", error)
                if error.apiFailure == .unauthorized { return context.invalidate(sessionInvalidMessage) }
                chat.proposalFailed(proposal.proposalId, message: proposalErrorMessage(error.apiFailure))
            }
        }
    }
}

@MainActor
@Observable
final class ScheduledViewModel {
    private(set) var state: LoadState<[ScheduledItem]> = .loading
    private(set) var busyId: String?
    private(set) var message: String?
    var pendingCancel: ScheduledItem?
    @ObservationIgnored private let context: AppContext

    init(context: AppContext) { self.context = context }

    func reload() async {
        if state.value == nil { state = .loading }
        do {
            state = .loaded(try await context.api.scheduled(context.session))
        } catch {
            guard !error.isCancellation else { return }
            logFailure("scheduled", error)
            if error.apiFailure == .unauthorized { return context.invalidate(sessionInvalidMessage) }
            state = .failed
        }
    }

    func run(_ item: ScheduledItem) {
        guard item.kind == "payment" else { return act(item, run: true) }
        guard DeviceAuthenticator.availability == .available else {
            message = "Configura un bloqueo de pantalla para enviar dinero."
            return
        }
        Task {
            if await DeviceAuthenticator.authenticate(reason: "Confirma el envío a \(item.contactName)") {
                act(item, run: true)
            } else {
                message = "Necesitamos confirmar que eres tú para enviar el pago."
            }
        }
    }

    func cancel(_ item: ScheduledItem) { act(item, run: false) }

    private func act(_ item: ScheduledItem, run: Bool) {
        guard busyId == nil else { return }
        busyId = item.scheduledId
        message = nil
        Task {
            defer { busyId = nil }
            do {
                if run {
                    let result = try await context.api.runScheduled(context.session, scheduledId: item.scheduledId)
                    if result.state == "completado" {
                        message = item.kind == "payment"
                            ? "Envío creado a \(item.contactName). Revisa su estado en Movimientos."
                            : "Cobro creado. \(item.contactName) debe aprobarlo en PayPal."
                    } else {
                        message = scheduledFailureMessage(result.failure) ?? "No se pudo crear el cobro."
                    }
                } else {
                    try await context.api.cancelScheduled(context.session, scheduledId: item.scheduledId)
                }
                await reload()
            } catch {
                logFailure(run ? "runScheduled" : "cancelScheduled", error)
                switch error.apiFailure {
                case .unauthorized: return context.invalidate(sessionInvalidMessage)
                case .notFound: message = "Esa programación ya cambió de estado."
                case let type: message = assistantErrorMessage(type)
                }
                await reload()
            }
        }
    }
}

@MainActor
@Observable
final class StatsViewModel {
    private(set) var state: LoadState<Stats> = .loading
    var period: StatsPeriod = .week
    var selectedBucket = 0
    @ObservationIgnored private let context: AppContext

    init(context: AppContext) { self.context = context }

    func reload() async {
        state = .loading
        do {
            let stats = try await context.api.stats(context.session, period: period)
            selectedBucket = defaultSelectedBucket(stats.buckets)
            state = .loaded(stats)
        } catch {
            guard !error.isCancellation else { return }
            logFailure("stats", error)
            if error.apiFailure == .unauthorized { return context.invalidate(sessionInvalidMessage) }
            state = .failed
        }
    }
}
