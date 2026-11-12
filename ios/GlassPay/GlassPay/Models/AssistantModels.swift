import Foundation

// Traducción de AssistantChat.kt. Las respuestas las redacta el servidor; la app solo las muestra
// y la IA nunca ejecuta nada: cada propuesta requiere la confirmación del cobrador.

struct AssistantSummary: Decodable, Equatable {
    let period: String
    let currency: String
    let paidTotal: String
    let paidCount: Int
    let sentTotal: String
    let sentCount: Int
    let netTotal: String
}

struct AssistantDebtor: Decodable, Equatable, Identifiable {
    let name: String
    let total: String
    let count: Int
    var id: String { name }
}

struct SplitShare: Decodable, Equatable, Identifiable {
    let contactName: String
    let amount: String
    var id: String { contactName }
}

struct AssistantProposal: Decodable, Equatable {
    let proposalId: String
    let action: String
    let contactName: String
    /// Nulo en un recordatorio sin monto.
    let amount: String?
    let currency: String
    let concept: String
    /// Nulo en una división que se cobra al confirmar.
    let runAt: String?
    let runAtLabel: String?
    let expiresAt: String
    let recurrence: String?
    let occurrencesTotal: Int?
    let participants: [SplitShare]
    let myShare: String?
    /// Aviso: hoy el saldo no alcanza (se vuelve a verificar al enviar).
    let balanceWarning: Bool

    private enum CodingKeys: String, CodingKey {
        case proposalId, action, contactName, amount, currency, concept, runAt, runAtLabel, expiresAt
        case recurrence, occurrencesTotal, participants, myShare, balanceWarning
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        proposalId = try c.decode(String.self, forKey: .proposalId)
        action = try c.decode(String.self, forKey: .action)
        contactName = try c.decode(String.self, forKey: .contactName)
        amount = try c.decodeIfPresent(FlexibleString.self, forKey: .amount)?.value.nonEmptyValue
        currency = try c.decode(String.self, forKey: .currency)
        concept = try c.decode(String.self, forKey: .concept)
        runAt = try c.decodeIfPresent(String.self, forKey: .runAt).nonEmpty
        runAtLabel = try c.decodeIfPresent(String.self, forKey: .runAtLabel).nonEmpty
        expiresAt = try c.decode(String.self, forKey: .expiresAt)
        recurrence = try c.decodeIfPresent(String.self, forKey: .recurrence).nonEmpty
        occurrencesTotal = try c.decodeIfPresent(Int.self, forKey: .occurrencesTotal)
        participants = try c.decodeIfPresent([SplitShare].self, forKey: .participants) ?? []
        myShare = try c.decodeIfPresent(FlexibleString.self, forKey: .myShare)?.value.nonEmptyValue
        balanceWarning = try c.decodeIfPresent(Bool.self, forKey: .balanceWarning) ?? false
    }
}

struct ScheduledItem: Decodable, Equatable, Identifiable {
    let scheduledId: String
    /// "charge", "reminder" o "payment".
    let kind: String
    let contactName: String
    let amount: String
    let currency: String
    let concept: String
    let runAt: String
    let runAtLabel: String
    let autoExecute: Bool
    let state: String
    let failure: String?
    let recurrence: String?
    let occurrencesTotal: Int
    let occurrencesDone: Int
    var id: String { scheduledId }

    private enum CodingKeys: String, CodingKey {
        case scheduledId, kind, contactName, amount, currency, concept, runAt, runAtLabel, autoExecute
        case state, failure, recurrence, occurrencesTotal, occurrencesDone
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        scheduledId = try c.decode(String.self, forKey: .scheduledId)
        kind = try c.decodeIfPresent(String.self, forKey: .kind).nonEmpty ?? "charge"
        contactName = try c.decode(String.self, forKey: .contactName)
        amount = try c.decode(FlexibleString.self, forKey: .amount).value
        currency = try c.decode(String.self, forKey: .currency)
        concept = try c.decode(String.self, forKey: .concept)
        runAt = try c.decode(String.self, forKey: .runAt)
        runAtLabel = try c.decode(String.self, forKey: .runAtLabel)
        autoExecute = try c.decode(Bool.self, forKey: .autoExecute)
        state = try c.decode(String.self, forKey: .state)
        failure = try c.decodeIfPresent(String.self, forKey: .failure).nonEmpty
        recurrence = try c.decodeIfPresent(String.self, forKey: .recurrence).nonEmpty
        occurrencesTotal = try c.decodeIfPresent(Int.self, forKey: .occurrencesTotal) ?? 1
        occurrencesDone = try c.decodeIfPresent(Int.self, forKey: .occurrencesDone) ?? 0
    }
}

struct AssistantReply: Decodable, Equatable {
    let kind: String
    let text: String
    let summary: AssistantSummary?
    let debtors: [AssistantDebtor]
    let proposal: AssistantProposal?
    let scheduled: [ScheduledItem]

    private enum CodingKeys: String, CodingKey { case kind, text, summary, debtors, proposal, scheduled }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kind = try c.decode(String.self, forKey: .kind)
        text = try c.decode(String.self, forKey: .text)
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw ApiFailureType.invalidResponse }
        summary = try c.decodeIfPresent(AssistantSummary.self, forKey: .summary)
        debtors = try c.decodeIfPresent([AssistantDebtor].self, forKey: .debtors) ?? []
        proposal = try c.decodeIfPresent(AssistantProposal.self, forKey: .proposal)
        scheduled = try c.decodeIfPresent([ScheduledItem].self, forKey: .scheduled) ?? []
    }
}

struct AssistantResponse: Decodable {
    let reply: AssistantReply
    let suggestions: [String]

    private enum CodingKeys: String, CodingKey { case reply, suggestions }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        reply = try c.decode(AssistantReply.self, forKey: .reply)
        suggestions = try c.decodeIfPresent([String].self, forKey: .suggestions) ?? []
    }
}

struct Reminder: Decodable, Equatable, Identifiable {
    let reminderId: String
    let text: String
    let createdAt: String
    var id: String { reminderId }
}

struct SplitResult: Decodable, Equatable {
    let contactName: String
    let amount: String
    let ok: Bool
    let failure: String?
}

/// Lo que pasó al confirmar una propuesta.
enum ProposalOutcome: Equatable {
    case scheduled([ScheduledItem])
    case cancelled
    case split([SplitResult])
    case reminderSent(contactName: String)
}

extension ProposalOutcome: Decodable {
    private enum CodingKeys: String, CodingKey { case split, scheduledItems, scheduled, cancelled, reminder }
    private struct ReminderBody: Decodable { let contactName: String? }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let split = try c.decodeIfPresent([SplitResult].self, forKey: .split) {
            self = .split(split)
        } else if let items = try c.decodeIfPresent([ScheduledItem].self, forKey: .scheduledItems) {
            self = .scheduled(items)
        } else if let item = try c.decodeIfPresent(ScheduledItem.self, forKey: .scheduled) {
            self = .scheduled([item])
        } else if try c.decodeIfPresent(Bool.self, forKey: .cancelled) == true {
            self = .cancelled
        } else if let reminder = try c.decodeIfPresent(ReminderBody.self, forKey: .reminder) {
            self = .reminderSent(contactName: reminder.contactName ?? "")
        } else {
            throw ApiFailureType.invalidResponse
        }
    }
}

func recurrenceLabel(_ recurrence: String?, total: Int?) -> String? {
    let every: String
    switch recurrence {
    case "weekly": every = L("Cada semana")
    case "monthly": every = L("Cada mes")
    default: return nil
    }
    if let total, total > 0 { return L("%@ · %@ veces", "\(every)", "\(total)") }
    return every
}

private func shares(_ results: [SplitResult]) -> String {
    results.map { "\($0.contactName) \(withoutCurrency(formatMoney($0.amount, "MXN")))" }.joined(separator: ", ")
}

/// Mensaje del chat tras confirmar; nunca dice "pagado": el pagador aún debe aprobar en PayPal.
func proposalOutcomeMessage(_ outcome: ProposalOutcome, autoExecute: Bool) -> String {
    switch outcome {
    case .cancelled:
        return L("Listo, cancelé la programación.")
    case let .reminderSent(name):
        return L("Listo, le envié el recordatorio a %@. Lo verá en su sección «Por pagar».", "\(name)")
    case let .split(results):
        let ok = results.filter(\.ok)
        let failed = results.filter { !$0.ok }
        var text = ok.isEmpty
            ? L("No pude crear ningún cobro.")
            : ok.count == 1
                ? L("Creé 1 cobro: %@. Cada quien lo aprueba en PayPal.", shares(ok))
                : L("Creé %@ cobros: %@. Cada quien lo aprueba en PayPal.", "\(ok.count)", shares(ok))
        if !failed.isEmpty { text += " " + L("No se creó para: %@.", failed.map(\.contactName).joined(separator: ", ")) }
        return text
    case let .scheduled(items):
        let first = items.first
        let who = items.count > 1 ? L("%@ personas", "\(items.count)") : (first?.contactName ?? "")
        let series = recurrenceLabel(first?.recurrence, total: first?.occurrencesTotal).map { " (\($0))" } ?? ""
        let when = first?.runAtLabel ?? ""
        return autoExecute
            ? L("Listo. El %@ crearé el cobro a %@%@; luego lo aprueban en PayPal.", "\(when)", "\(who)", "\(series)")
            : L("Listo. El %@ te pediré confirmar el cobro a %@%@.", "\(when)", "\(who)", "\(series)")
    }
}

let maxAssistantMessageLength = 500

enum ProposalStatus { case pending, confirming, confirmed, discarded, failed }

struct ChatMessage: Identifiable, Equatable {
    let id: Int
    let fromUser: Bool
    let text: String
    var reply: AssistantReply? = nil
    var isError = false
}

var defaultAssistantSuggestions: [String] { [
    L("¿Cuánto cobré esta semana?"),
    L("¿Quién me debe?"),
    L("¿Qué tengo programado?"),
    L("¿Qué puedes hacer?"),
] }

/// Conversación solo en memoria: se pierde al salir del asistente y nunca se guarda en disco.
struct ChatState: Equatable {
    private(set) var messages: [ChatMessage] = []
    private(set) var sending = false
    private(set) var suggestions = defaultAssistantSuggestions
    private(set) var proposals: [String: ProposalStatus] = [:]
    private var nextId = 1

    func proposalStatus(_ proposalId: String) -> ProposalStatus { proposals[proposalId] ?? .pending }

    /// Solo una propuesta pendiente puede confirmarse, y solo una vez.
    func canConfirm(_ proposalId: String) -> Bool { proposalStatus(proposalId) == .pending }

    func canSend(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return !sending && !trimmed.isEmpty && trimmed.count <= maxAssistantMessageLength
    }

    mutating func proposalConfirming(_ proposalId: String) {
        if canConfirm(proposalId) { proposals[proposalId] = .confirming }
    }

    mutating func proposalDiscarded(_ proposalId: String) {
        if canConfirm(proposalId) { proposals[proposalId] = .discarded }
    }

    mutating func proposalConfirmed(_ proposalId: String, message: String) {
        proposals[proposalId] = .confirmed
        append(ChatMessage(id: nextId, fromUser: false, text: message))
    }

    mutating func proposalFailed(_ proposalId: String, message: String) {
        proposals[proposalId] = .failed
        append(ChatMessage(id: nextId, fromUser: false, text: message, isError: true))
    }

    mutating func userSent(_ text: String) {
        append(ChatMessage(id: nextId, fromUser: true, text: text.trimmingCharacters(in: .whitespacesAndNewlines)))
        sending = true
    }

    mutating func replied(_ response: AssistantResponse) {
        append(ChatMessage(id: nextId, fromUser: false, text: response.reply.text, reply: response.reply))
        sending = false
        if !response.suggestions.isEmpty { suggestions = response.suggestions }
    }

    mutating func failed(_ message: String) {
        append(ChatMessage(id: nextId, fromUser: false, text: message, isError: true))
        sending = false
    }

    private mutating func append(_ message: ChatMessage) {
        messages.append(message)
        nextId += 1
    }
}

func scheduledStateLabel(_ state: String, kind: String = "charge") -> String {
    switch state {
    case "completado":
        switch kind {
        case "reminder": return L("Enviado")
        case "payment": return L("Envío creado")
        default: return L("Cobro creado")
        }
    case "programado": return L("Programado")
    case "esperando_confirmacion": return L("Espera tu confirmación")
    case "ejecutando": return L("Creando cobro")
    case "fallido": return L("No se creó")
    case "cancelado": return L("Cancelado")
    case "omitido": return L("Omitido")
    default: return state
    }
}

func scheduledStateKind(_ state: String) -> StatusKind {
    switch state {
    case "programado", "esperando_confirmacion", "ejecutando": return .pending
    case "completado": return .success
    case "fallido": return .failure
    default: return .neutral
    }
}

func scheduledFailureMessage(_ failure: String?) -> String? {
    switch failure {
    case nil, "": return nil
    case "demasiados_pendientes": return L("Tu contacto ya tenía demasiados cobros pendientes.")
    case "paypal_no_disponible": return L("PayPal no respondió a tiempo.")
    case "contacto_eliminado": return L("Eliminaste a este contacto antes de la hora.")
    case "atrasado": return L("El servidor estaba inactivo a esa hora, así que no se creó.")
    case "interrumpido": return L("Se interrumpió. Revisa Movimientos antes de volver a cobrar.")
    case "sin_confirmar": return L("No lo confirmaste dentro de las 2 horas siguientes.")
    case "limite_recordatorios": return L("Ya le enviaste 2 recordatorios hoy a este contacto.")
    case "saldo_insuficiente": return L("No se envió: tu saldo no alcanzaba a esa hora.")
    case "sin_correo": return L("No se envió: tu contacto ya no tiene correo de cobro.")
    default: return L("No se pudo crear el cobro.")
    }
}

func isScheduledActive(_ state: String) -> Bool {
    state == "programado" || state == "esperando_confirmacion" || state == "ejecutando"
}

func proposalErrorMessage(_ type: ApiFailureType) -> String {
    switch type {
    case .notFound: return L("La propuesta ya venció o se usó. Pídemela de nuevo.")
    case .conflict:
        return L("No se pudo: ya pasó la hora, llegaste al máximo de programaciones o ya enviaste 2 recordatorios hoy a ese contacto.")
    default: return assistantErrorMessage(type)
    }
}

func assistantErrorMessage(_ type: ApiFailureType) -> String {
    switch type {
    case .network: return L("Sin conexión. Revisa tu red e inténtalo de nuevo.")
    case .timeout: return L("El servidor tardó demasiado. Si estaba dormido, inténtalo de nuevo en unos segundos.")
    case .rateLimited: return L("Demasiados mensajes seguidos. Espera un momento.")
    case .badRequest: return L("Ese mensaje no es válido. Escríbelo de otra forma.")
    default: return L("No pude responder ahora. Inténtalo de nuevo.")
    }
}

func assistantPeriodLabel(_ period: String) -> String {
    switch period {
    case "month": return L("Este mes")
    case "year": return L("Este año")
    default: return L("Esta semana")
    }
}

private extension String {
    var nonEmptyValue: String? { isEmpty ? nil : self }
}
