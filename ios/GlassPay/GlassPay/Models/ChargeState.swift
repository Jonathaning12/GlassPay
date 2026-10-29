import Foundation

// Traducción de android/.../ChargeState.kt. Las reglas son las mismas: toda transición del cobro
// pasa por ChargeStateMachine y los comandos de voz exigen coincidencia exacta de la frase.

struct ChargeProposal: Equatable {
    let proposalId: String
    let amount: String
    let currency: String
    let concept: String
    let expiresAt: String
}

enum PaymentTracking: Equatable {
    case waiting
    case waitingWithMessage(String)
    case approved
    case paid
    case rejected
    case expired
    case failed
    case error(String)
}

enum ChargeState: Equatable {
    case ready(message: String? = nil)
    case listening
    case interpreting
    case clarification(question: String)
    case preview(proposal: ChargeProposal, remainingSeconds: Int, message: String? = nil)
    case confirming
    case created(requestId: String, statusToken: String, payment: PaymentTracking = .waiting)
    case error(message: String, recoverable: Bool)

    var isReady: Bool { if case .ready = self { return true }; return false }
    var isListening: Bool { self == .listening }
    var isClarification: Bool { if case .clarification = self { return true }; return false }
    var isPreview: Bool { if case .preview = self { return true }; return false }
    var isCreated: Bool { if case .created = self { return true }; return false }
}

let maxClarificationRounds = 3
let maxAccumulatedTextLength = 480

struct ChargeInputSession: Equatable {
    let resolutionId: String
    let text: String
    var clarificationRounds: Int = 0

    static func start(resolutionId: String, text: String) -> ChargeInputSession? {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if normalized.isEmpty || normalized.count > maxAccumulatedTextLength { return nil }
        return ChargeInputSession(resolutionId: resolutionId, text: normalized)
    }

    func appendClarification(_ reply: String) -> ChargeInputAppendResult {
        let normalized = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        if normalized.isEmpty { return .invalid }
        if startsWithChargeVerb(normalized) {
            return .accepted(ChargeInputSession(resolutionId: resolutionId, text: normalized))
        }
        if clarificationRounds >= maxClarificationRounds { return .tooManyRounds }
        let combined = "\(text) \(normalized)"
        if combined.count > maxAccumulatedTextLength { return .tooLong }
        return .accepted(ChargeInputSession(
            resolutionId: resolutionId,
            text: combined,
            clarificationRounds: clarificationRounds + 1
        ))
    }
}

enum ChargeInputAppendResult: Equatable {
    case accepted(ChargeInputSession)
    case tooManyRounds
    case tooLong
    case invalid
}

private let chargeVerbs: Set<String> = ["cobrale", "cobrales", "cobra", "cobrar"]

func startsWithChargeVerb(_ text: String) -> Bool {
    guard let firstWord = normalizeCommandText(text).split(whereSeparator: \.isWhitespace).first else {
        return false
    }
    return chargeVerbs.contains(String(firstWord))
}

enum VoiceCommand { case cancel, startOver, confirm, none }

func voiceCommand(_ text: String, state: ChargeState) -> VoiceCommand {
    if isCancellation(text) && (state.isListening || state.isClarification || state.isPreview) {
        return .cancel
    }
    if normalizeCommandText(text) == "empezar de nuevo" && (state.isClarification || state.isPreview) {
        return .startOver
    }
    if isConfirmation(text) && state.isPreview { return .confirm }
    return .none
}

func isConfirmation(_ text: String) -> Bool {
    let normalized = normalizeCommandText(text)
    return normalized == "confirmar" || normalized == "si"
}

func isCancellation(_ text: String) -> Bool {
    let normalized = normalizeCommandText(text)
    return normalized == "no" || normalized == "cancelar"
}

/// Solo para comparar comandos: minúsculas es-MX, sin diacríticos, sin puntuación en los bordes
/// y espacios colapsados. El texto que se envía a /interpret no se modifica.
func normalizeCommandText(_ text: String) -> String {
    let lowered = text.trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased(with: Locale(identifier: "es_MX"))
    let scalars = lowered.decomposedStringWithCanonicalMapping.unicodeScalars
        .filter { $0.properties.generalCategory != .nonspacingMark }
    var characters = Array(String(String.UnicodeScalarView(scalars)).unicodeScalars)
    while let first = characters.first, isCommandBoundaryPunctuation(first) { characters.removeFirst() }
    while let last = characters.last, isCommandBoundaryPunctuation(last) { characters.removeLast() }
    let stripped = String(String.UnicodeScalarView(characters))
    return stripped.split(whereSeparator: \.isWhitespace).joined(separator: " ")
}

private func isCommandBoundaryPunctuation(_ scalar: Unicode.Scalar) -> Bool {
    switch scalar.properties.generalCategory {
    case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation,
         .initialPunctuation, .finalPunctuation, .otherPunctuation:
        return true
    default:
        return false
    }
}

func displayText(_ text: String, maxLength: Int) -> String {
    String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxLength))
}

enum InputSessionDecisionReason {
    case serverClarification
    case voiceError
    case interpretNetwork
    case interpretTimeout
    case interpretRateLimit
    case interpretDefinitiveError
}

func inputSessionAfterDecision(_ session: ChargeInputSession?, reason: InputSessionDecisionReason) -> ChargeInputSession? {
    reason == .interpretDefinitiveError ? nil : session
}

enum ChargeInputClearReason {
    case proposalCreated, cancelled, restarted, qrChanged, resolutionExpired, proposalExpired
}

/// La sesión solo se limpia ante transiciones del flujo; los errores se deciden con inputSessionAfterDecision.
func inputSessionAfter(_ session: ChargeInputSession?, reason: ChargeInputClearReason) -> ChargeInputSession? {
    nil
}

func shouldAcceptVoiceError(_ state: ChargeState) -> Bool {
    state.isListening || state.isPreview
}

enum ChargeFailureType { case network, timeout, rateLimited, proposalInvalid, server }

let stopWaitingMessage =
    "Dejaste de seguir esta solicitud. El pagador todavía puede aprobarla desde su teléfono."
let stopWaitingApprovedMessage =
    "Dejaste de seguir esta solicitud. El pagador ya la aprobó; revisa Movimientos para ver cuándo se complete."

struct ChargeStateMachine {
    private(set) var state: ChargeState = .ready()

    @discardableResult
    mutating func beginListening() -> ChargeState {
        if state.isReady || state.isClarification { state = .listening }
        return state
    }

    @discardableResult
    mutating func beginInterpretation() -> ChargeState {
        if state == .listening { state = .interpreting }
        return state
    }

    @discardableResult
    mutating func clarification(_ question: String) -> ChargeState {
        if state == .interpreting { state = .clarification(question: question) }
        return state
    }

    @discardableResult
    mutating func preview(_ proposal: ChargeProposal, remainingSeconds: Int) -> ChargeState {
        if state == .interpreting { state = .preview(proposal: proposal, remainingSeconds: remainingSeconds) }
        return state
    }

    @discardableResult
    mutating func previewMessage(_ message: String) -> ChargeState {
        if case let .preview(proposal, remaining, _) = state {
            state = .preview(proposal: proposal, remainingSeconds: remaining, message: message)
        }
        return state
    }

    @discardableResult
    mutating func tick(remainingSeconds: Int) -> ChargeState {
        guard case let .preview(proposal, _, message) = state else { return state }
        state = remainingSeconds <= 0
            ? .ready(message: "La propuesta venció. Dicta el cobro otra vez.")
            : .preview(proposal: proposal, remainingSeconds: remainingSeconds, message: message)
        return state
    }

    @discardableResult
    mutating func beginConfirmation() -> ChargeState {
        if case let .preview(_, remaining, _) = state, remaining > 0 { state = .confirming }
        return state
    }

    @discardableResult
    mutating func created(requestId: String, statusToken: String) -> ChargeState {
        if state == .confirming { state = .created(requestId: requestId, statusToken: statusToken) }
        return state
    }

    @discardableResult
    mutating func paymentUpdated(_ tracking: PaymentTracking) -> ChargeState {
        if case let .created(requestId, token, _) = state {
            state = .created(requestId: requestId, statusToken: token, payment: tracking)
        }
        return state
    }

    @discardableResult
    mutating func failure(_ type: ChargeFailureType) -> ChargeState {
        let allowed = state == .interpreting || (state == .confirming && type == .proposalInvalid)
        guard allowed else { return state }
        switch type {
        case .network, .timeout:
            state = .error(message: "No pudimos interpretar. Revisa tu conexión e inténtalo de nuevo.", recoverable: true)
        case .rateLimited:
            state = .error(message: "Demasiados intentos. Espera unos segundos.", recoverable: true)
        case .proposalInvalid:
            state = .ready(message: "La propuesta ya venció o no está disponible. Dicta el cobro otra vez.")
        case .server:
            state = .error(message: "GlassPay no pudo procesar la solicitud.", recoverable: true)
        }
        return state
    }

    @discardableResult
    mutating func confirmationFailure() -> ChargeState {
        guard state == .confirming else { return state }
        state = .error(
            message: "No pudimos confirmar. Revisa con el pagador antes de intentarlo de nuevo.",
            recoverable: false
        )
        return state
    }

    @discardableResult
    mutating func cancel() -> ChargeState {
        state = .ready(message: "Cobro cancelado.")
        return state
    }

    @discardableResult
    mutating func back() -> ChargeState {
        if state.isPreview { state = .ready() }
        return state
    }

    @discardableResult
    mutating func startOver() -> ChargeState {
        switch state {
        case .ready, .clarification, .preview, .error: state = .ready()
        default: break
        }
        return state
    }

    @discardableResult
    mutating func stopWaiting() -> ChargeState {
        guard case let .created(_, _, payment) = state, !isTerminal(payment) else { return state }
        state = .ready(message: payment == .approved ? stopWaitingApprovedMessage : stopWaitingMessage)
        return state
    }

    @discardableResult
    mutating func resetForNewScan(message: String? = nil) -> ChargeState {
        state = .ready(message: message)
        return state
    }

    @discardableResult
    mutating func inputError(_ message: String) -> ChargeState {
        switch state {
        case .ready, .listening, .clarification, .preview: state = .error(message: message, recoverable: true)
        default: break
        }
        return state
    }

    @discardableResult
    mutating func restartInput(_ message: String) -> ChargeState {
        if state == .listening || state == .interpreting { state = .ready(message: message) }
        return state
    }
}

func paymentTrackingFor(serverState: String) -> PaymentTracking {
    switch serverState {
    case "pendiente_de_pagador": return .waiting
    case "aprobada": return .approved
    case "pagada": return .paid
    case "rechazada": return .rejected
    case "expirada": return .expired
    case "fallida": return .failed
    default: return .error("No pudimos consultar el estado del pago.")
    }
}

func isTerminal(_ payment: PaymentTracking) -> Bool {
    switch payment {
    case .paid, .rejected, .expired, .failed, .error: return true
    case .waiting, .waitingWithMessage, .approved: return false
    }
}

enum PaymentStep: Int { case sent, awaitingPayer, confirming, paid, stopped }

func paymentStep(_ tracking: PaymentTracking) -> PaymentStep {
    switch tracking {
    case .waiting, .waitingWithMessage: return .awaitingPayer
    case .approved: return .confirming
    case .paid: return .paid
    case .rejected, .expired, .failed, .error: return .stopped
    }
}

func showsRecognizedText(_ state: ChargeState) -> Bool { state == .interpreting || state.isClarification }
func showsTextInput(_ state: ChargeState) -> Bool { state.isReady || state.isListening || state.isClarification }
func showsAccumulatedText(_ state: ChargeState) -> Bool { state.isClarification }

enum ChargeAction { case dictate, answer, confirm, cancel, startOver, back, scanAnother, stopWaiting, retry }

func availableActions(_ state: ChargeState) -> Set<ChargeAction> {
    switch state {
    case .ready: return [.dictate, .scanAnother]
    case .listening, .interpreting, .confirming: return []
    case .clarification: return [.answer, .startOver, .scanAnother]
    case let .preview(_, remaining, _): return remaining > 0 ? [.confirm, .cancel, .startOver, .back] : [.back]
    case let .created(_, _, payment): return isTerminal(payment) ? [.scanAnother] : [.stopWaiting]
    case let .error(_, recoverable): return recoverable ? [.retry, .scanAnother] : [.scanAnother]
    }
}

func waitingPaymentMessage(elapsedSeconds: Int) -> String {
    switch elapsedSeconds {
    case ..<4: return "Confirmando tu pago con PayPal…"
    case ..<10: return "Preparando tu comprobante…"
    case ..<20: return "Terminando detalles…"
    case ..<30: return "Esto está tardando un poco más de lo normal…"
    default: return "Tu pago está en proceso…"
    }
}

/// Datos del cobro confirmado, solo en memoria, para la pantalla de espera.
struct ConfirmedCharge: Equatable {
    let amount: String
    let currency: String
    let concept: String
    let payerName: String
}
