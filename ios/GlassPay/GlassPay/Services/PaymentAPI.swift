import Foundation
import OSLog

// Traducción de android/.../PaymentApi.kt. Mismos endpoints, timeouts y política de reintentos:
// /interpret reintenta una vez ante red/timeout; /confirm y los envíos nunca reintentan.

let appLog = Logger(subsystem: "com.glasspay.ios", category: "GlassPay")

/// Registra solo el tipo de fallo y el código HTTP; nunca tokens, ids, textos dictados ni cuerpos.
func logFailure(_ operation: String, _ error: Error) {
    if let api = error as? ApiError {
        appLog.info("\(operation, privacy: .public) failure type=\(String(describing: api.type), privacy: .public) httpCode=\(api.httpCode.map(String.init) ?? "none", privacy: .public)")
    } else {
        appLog.info("\(operation, privacy: .public) failure type=UNKNOWN httpCode=none")
    }
}

extension Error {
    /// Tipo de fallo para la UI; cualquier error desconocido se trata como problema de red.
    var apiFailure: ApiFailureType { (self as? ApiError)?.type ?? (self as? ApiFailureType) ?? .network }
    var isCancellation: Bool { self is CancellationError || (self as? URLError)?.code == .cancelled }
}

final class PaymentAPI: Sendable {
    let baseURL: URL
    private let urlSession: URLSession

    init(baseURL: URL = PaymentAPI.configuredBaseURL, urlSession: URLSession = .shared) {
        self.baseURL = baseURL
        self.urlSession = urlSession
    }

    /// Sale de la clave GlassPayAPIBaseURL del Info.plist (build setting GLASSPAY_API_BASE_URL). Solo HTTPS.
    static var configuredBaseURL: URL {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "GlassPayAPIBaseURL") as? String)
            .flatMap { $0.isEmpty ? nil : $0 } ?? "https://glasspay-1qdu.onrender.com"
        guard let url = URL(string: raw.trimmingCharacters(in: CharacterSet(charactersIn: "/"))),
              url.scheme == "https" else {
            preconditionFailure("GlassPayAPIBaseURL must use HTTPS")
        }
        return url
    }

    // MARK: - Servidor y cuenta

    func warmUp() async throws {
        _ = try await execute("GET", "/health", timeout: 60)
    }

    func createCollector(displayName: String) async throws -> CollectorSession {
        struct Body: Decodable { let collectorId: String; let sessionSecret: String; let displayName: String }
        let body: Body = try await decode(try await execute("POST", "/api/collectors", json: ["displayName": displayName], timeout: 60))
        return CollectorSession(collectorId: body.collectorId, sessionSecret: body.sessionSecret, displayName: body.displayName)
    }

    func collectorProfile(_ session: CollectorSession) async throws -> CollectorProfile {
        try await decode(try await execute("GET", "/api/collectors/me", headers: session.headers))
    }

    func movements(_ session: CollectorSession) async throws -> [Movement] {
        struct Body: Decodable { let movements: [Movement] }
        let body: Body = try await decode(try await execute("GET", "/api/collectors/me/movements", headers: session.headers))
        return body.movements
    }

    /// "Eliminar de mi lista": ocultado lógico en el servidor. 409 = el movimiento no se puede ocultar.
    func hideMovement(_ session: CollectorSession, movementId: String) async throws {
        _ = try await execute("POST", "/api/collectors/me/movements/\(try pathSegment(movementId))/hide", json: [:], headers: session.headers)
    }

    func setPayoutEmail(_ session: CollectorSession, email: String?) async throws -> String? {
        struct Body: Decodable { let payoutEmail: String? }
        let payload: [String: Any] = ["email": email ?? NSNull()]
        let body: Body = try await decode(try await execute("PUT", "/api/collectors/me/payout-email", json: payload, headers: session.headers))
        return body.payoutEmail.nonEmpty
    }

    // MARK: - QR y contactos

    func resolveQrToken(_ session: CollectorSession, token: String, timeout: TimeInterval) async throws -> PayerResolution {
        try await decode(try await execute(
            "POST", "/api/payers/resolve", json: ["token": "glasspay:\(token)"], timeout: timeout, headers: session.headers
        ))
    }

    func issueMyQr(_ session: CollectorSession) async throws -> MyQr {
        try await decode(try await execute("POST", "/api/collectors/me/qr", json: [:], headers: session.headers))
    }

    func incomingRequests(_ session: CollectorSession) async throws -> [IncomingRequest] {
        struct Body: Decodable { let requests: [IncomingRequest] }
        let body: Body = try await decode(try await execute("GET", "/api/collectors/me/incoming-requests", headers: session.headers))
        return body.requests
    }

    func rejectIncoming(_ session: CollectorSession, requestId: String) async throws {
        _ = try await execute("POST", "/api/collectors/me/incoming-requests/\(try pathSegment(requestId))/reject", json: [:], headers: session.headers)
    }

    func listContacts(_ session: CollectorSession) async throws -> [Contact] {
        struct Body: Decodable { let contacts: [Contact] }
        let body: Body = try await decode(try await execute("GET", "/api/collectors/me/contacts", headers: session.headers))
        return body.contacts
    }

    func saveContact(_ session: CollectorSession, resolutionId: String) async throws -> Contact {
        try await decode(try await execute("POST", "/api/collectors/me/contacts", json: ["resolutionId": resolutionId], headers: session.headers))
    }

    func deleteContact(_ session: CollectorSession, contactId: String) async throws {
        _ = try await execute("DELETE", "/api/collectors/me/contacts/\(try pathSegment(contactId))", headers: session.headers)
    }

    func contactResolution(_ session: CollectorSession, contactId: String) async throws -> PayerResolution {
        try await decode(try await execute(
            "POST", "/api/collectors/me/contacts/\(try pathSegment(contactId))/resolution", json: [:], headers: session.headers
        ))
    }

    // MARK: - Cobro por voz

    func interpret(_ session: CollectorSession, resolutionId: String, text: String) async throws -> InterpretResult {
        struct Body: Decodable {
            let needsClarification: Bool?
            let clarificationQuestion: String?
            let proposalId: String?
            let amount: FlexibleString?
            let currency: String?
            let concept: String?
            let expiresAt: String?
        }
        var attempt = 0
        while true {
            do {
                let data = try await execute(
                    "POST", "/api/agent/interpret", json: ["resolutionId": resolutionId, "text": text], timeout: 20, headers: session.headers
                )
                let body: Body = try await decode(data)
                if body.needsClarification == true {
                    return InterpretResult(proposal: nil, clarificationQuestion: body.clarificationQuestion ?? "")
                }
                guard let id = body.proposalId, let amount = body.amount, let currency = body.currency,
                      let concept = body.concept, let expiresAt = body.expiresAt else {
                    throw ApiError(type: .invalidResponse)
                }
                return InterpretResult(
                    proposal: ChargeProposal(proposalId: id, amount: amount.value, currency: currency, concept: concept, expiresAt: expiresAt),
                    clarificationQuestion: nil
                )
            } catch let error as ApiError where attempt == 0 && (error.type == .network || error.type == .timeout) {
                attempt += 1
            }
        }
    }

    func confirm(_ session: CollectorSession, proposalId: String) async throws -> PaymentRequestCreated {
        try await decode(try await execute("POST", "/api/agent/confirm", json: ["proposalId": proposalId], timeout: 20, headers: session.headers))
    }

    func paymentStatus(requestId: String, statusToken: String) async throws -> PaymentStatus {
        try await decode(try await execute(
            "GET", "/api/payers/payment-requests/\(try pathSegment(requestId))/status", headers: ["X-Status-Token": statusToken]
        ))
    }

    // MARK: - Envíos

    func previewTransfer(_ session: CollectorSession, contactId: String, amount: String, note: String?) async throws -> TransferPreview {
        guard let value = Double(amount) else { throw ApiError(type: .badRequest) }
        var payload: [String: Any] = ["contactId": contactId, "amount": value]
        if let note { payload["note"] = note }
        return try await decode(try await execute("POST", "/api/collectors/me/transfers/preview", json: payload, headers: session.headers))
    }

    /// Sin reintentos automáticos, igual que /agent/confirm.
    func confirmTransfer(_ session: CollectorSession, transferProposalId: String) async throws -> TransferResult {
        try await decode(try await execute(
            "POST", "/api/collectors/me/transfers/confirm", json: ["transferProposalId": transferProposalId], timeout: 30, headers: session.headers
        ))
    }

    // MARK: - Asistente, programados, recordatorios y estadísticas

    func sendAssistantMessage(_ session: CollectorSession, text: String) async throws -> AssistantResponse {
        // Margen para Gemini más un posible despertar del servidor.
        try await decode(try await execute("POST", "/api/collectors/me/assistant/messages", json: ["text": text], timeout: 25, headers: session.headers))
    }

    /// El servidor usa lo que guardó en la propuesta, no datos de la app.
    func confirmAssistantProposal(_ session: CollectorSession, proposalId: String, autoExecute: Bool) async throws -> ProposalOutcome {
        // Dividir crea hasta 10 órdenes de PayPal seguidas: margen amplio.
        try await decode(try await execute(
            "POST", "/api/collectors/me/assistant/proposals/\(try pathSegment(proposalId))/confirm",
            json: ["autoExecute": autoExecute], timeout: 60, headers: session.headers
        ))
    }

    func scheduled(_ session: CollectorSession) async throws -> [ScheduledItem] {
        struct Body: Decodable { let scheduled: [ScheduledItem] }
        let body: Body = try await decode(try await execute("GET", "/api/collectors/me/scheduled", headers: session.headers))
        return body.scheduled
    }

    func cancelScheduled(_ session: CollectorSession, scheduledId: String) async throws {
        _ = try await execute("POST", "/api/collectors/me/scheduled/\(try pathSegment(scheduledId))/cancel", json: [:], headers: session.headers)
    }

    func runScheduled(_ session: CollectorSession, scheduledId: String) async throws -> ScheduledItem {
        struct Body: Decodable { let scheduled: ScheduledItem }
        let body: Body = try await decode(try await execute(
            "POST", "/api/collectors/me/scheduled/\(try pathSegment(scheduledId))/run", json: [:], timeout: 30, headers: session.headers
        ))
        return body.scheduled
    }

    func reminders(_ session: CollectorSession) async throws -> [Reminder] {
        struct Body: Decodable { let reminders: [Reminder] }
        let body: Body = try await decode(try await execute("GET", "/api/collectors/me/reminders", headers: session.headers))
        return body.reminders
    }

    func dismissReminder(_ session: CollectorSession, reminderId: String) async throws {
        _ = try await execute("POST", "/api/collectors/me/reminders/\(try pathSegment(reminderId))/dismiss", json: [:], headers: session.headers)
    }

    func stats(_ session: CollectorSession, period: StatsPeriod) async throws -> Stats {
        try await decode(try await execute("GET", "/api/collectors/me/stats?period=\(period.rawValue)", timeout: 20, headers: session.headers))
    }

    // MARK: - Transporte

    private func execute(
        _ method: String,
        _ path: String,
        json: [String: Any]? = nil,
        timeout: TimeInterval = 15,
        headers: [String: String] = [:]
    ) async throws -> Data {
        guard let url = URL(string: baseURL.absoluteString + path) else { throw ApiError(type: .invalidResponse) }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        if let json {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: json)
        }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await urlSession.data(for: request)
        } catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            throw ApiError(type: error.code == .timedOut ? .timeout : .network)
        }
        guard let http = response as? HTTPURLResponse else { throw ApiError(type: .invalidResponse) }
        guard (200..<300).contains(http.statusCode) else {
            let type: ApiFailureType
            switch http.statusCode {
            case 400: type = .badRequest
            case 401: type = .unauthorized
            case 404: type = .notFound
            case 409: type = .conflict
            case 429: type = .rateLimited
            case 500..<600: type = .server
            default: type = .invalidResponse
            }
            struct ErrorBody: Decodable { let error: String? }
            let serverError = (try? JSONDecoder().decode(ErrorBody.self, from: data))?.error.nonEmpty
            throw ApiError(type: type, httpCode: http.statusCode, serverError: serverError)
        }
        return data
    }

    private func decode<T: Decodable>(_ data: Data) async throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw ApiError(type: .invalidResponse)
        }
    }

    /// Los ids del servidor son opacos; se rechaza cualquier carácter que pudiera cambiar la ruta.
    private func pathSegment(_ id: String) throws -> String {
        guard !id.isEmpty, id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) else {
            throw ApiError(type: .badRequest)
        }
        return id
    }
}
