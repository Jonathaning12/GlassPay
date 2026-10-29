import Foundation

// Contratos del servidor (los mismos que consume android/.../PaymentApi.kt). Nada aquí es un secreto
// salvo CollectorSession.sessionSecret, que solo vive en memoria y en el Llavero.

enum ApiFailureType: Error, Equatable {
    case unauthorized, badRequest, conflict, network, timeout, notFound, rateLimited, server, invalidResponse
}

struct ApiError: Error {
    let type: ApiFailureType
    var httpCode: Int?
    var serverError: String?
}

/// El servidor manda algunos montos como texto y otros como número; aquí siempre son texto decimal.
struct FlexibleString: Decodable, Equatable {
    let value: String

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let text = try? container.decode(String.self) {
            value = text
        } else if let integer = try? container.decode(Int.self) {
            value = String(integer)
        } else {
            value = String(try container.decode(Double.self))
        }
    }
}

struct CollectorSession: Equatable, CustomStringConvertible {
    let collectorId: String
    let sessionSecret: String
    let displayName: String

    var headers: [String: String] {
        ["X-Collector-Id": collectorId, "X-Collector-Session": sessionSecret]
    }

    // Nunca incluir el secreto ni el id en texto de depuración.
    var description: String { "CollectorSession(displayName=\(displayName))" }
}

struct PayerResolution: Decodable, Equatable {
    let displayName: String
    let resolutionId: String
    let expiresAt: String
    let contactId: String?
}

struct MyQr: Decodable, CustomStringConvertible {
    let token: String
    let expiresAt: String
    var description: String { "MyQr(expiresAt=\(expiresAt))" }
}

struct IncomingRequest: Decodable, Identifiable, Equatable {
    let requestId: String
    let state: String
    let amount: String
    let currency: String
    let concept: String
    let approveUrl: String?
    let expiresAt: String?
    let createdAt: String?
    let fromName: String?
    var id: String { requestId }

    private enum CodingKeys: String, CodingKey {
        case requestId, state, amount, currency, concept, approveUrl, expiresAt, createdAt, fromName
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        requestId = try c.decode(String.self, forKey: .requestId)
        state = try c.decode(String.self, forKey: .state)
        amount = try c.decode(FlexibleString.self, forKey: .amount).value
        currency = try c.decode(String.self, forKey: .currency)
        concept = try c.decode(String.self, forKey: .concept)
        approveUrl = try c.decodeIfPresent(String.self, forKey: .approveUrl).nonEmpty
        expiresAt = try c.decodeIfPresent(String.self, forKey: .expiresAt)
        createdAt = try c.decodeIfPresent(String.self, forKey: .createdAt)
        fromName = try c.decodeIfPresent(String.self, forKey: .fromName).nonEmpty
    }
}

struct Contact: Decodable, Identifiable, Hashable {
    let contactId: String
    let displayName: String
    let createdAt: String
    var id: String { contactId }
}

struct InterpretResult {
    let proposal: ChargeProposal?
    let clarificationQuestion: String?
}

struct PaymentRequestCreated: Decodable {
    let requestId: String
    let statusToken: String
}

struct PaymentStatus: Decodable {
    let state: String
    let amount: String
    let currency: String
    let concept: String
}

struct CurrencyActivity: Decodable, Equatable, Identifiable {
    let currency: String
    let paidTotal: String
    let paidCount: Int
    let inProgressCount: Int
    let sentTotal: String
    let availableTotal: String
    var id: String { currency }

    private enum CodingKeys: String, CodingKey {
        case currency, paidTotal, paidCount, inProgressCount, sentTotal, availableTotal
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        currency = try c.decode(String.self, forKey: .currency)
        paidTotal = try c.decode(FlexibleString.self, forKey: .paidTotal).value
        paidCount = try c.decode(Int.self, forKey: .paidCount)
        inProgressCount = try c.decode(Int.self, forKey: .inProgressCount)
        sentTotal = (try? c.decodeIfPresent(FlexibleString.self, forKey: .sentTotal))??.value ?? "0.00"
        availableTotal = (try? c.decodeIfPresent(FlexibleString.self, forKey: .availableTotal))??.value ?? "0.00"
    }
}

struct CollectorProfile: Decodable, Equatable {
    let displayName: String
    let activity: [CurrencyActivity]
    let payoutEmail: String?
}

struct TransferPreview: Decodable, Equatable, CustomStringConvertible {
    let transferProposalId: String
    let amount: String
    let currency: String
    let note: String?
    let recipientName: String
    let expiresAt: String
    var description: String { "TransferPreview(expiresAt=\(expiresAt))" }

    private enum CodingKeys: String, CodingKey {
        case transferProposalId, amount, currency, note, recipientName, expiresAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        transferProposalId = try c.decode(String.self, forKey: .transferProposalId)
        amount = try c.decode(FlexibleString.self, forKey: .amount).value
        currency = try c.decode(String.self, forKey: .currency)
        note = try c.decodeIfPresent(String.self, forKey: .note).nonEmpty
        recipientName = try c.decode(String.self, forKey: .recipientName)
        expiresAt = try c.decode(String.self, forKey: .expiresAt)
    }
}

struct TransferResult: Decodable, Equatable {
    let transferId: String
    var state: String
    let amount: String
    let currency: String
    let recipientName: String

    private enum CodingKeys: String, CodingKey { case transferId, state, amount, currency, recipientName }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        transferId = try c.decode(String.self, forKey: .transferId)
        state = try c.decode(String.self, forKey: .state)
        amount = try c.decode(FlexibleString.self, forKey: .amount).value
        currency = try c.decode(String.self, forKey: .currency)
        recipientName = try c.decode(String.self, forKey: .recipientName)
    }
}

struct Movement: Decodable, Identifiable, Equatable {
    let movementId: String
    let state: String
    let amount: String
    let currency: String
    let concept: String
    let payerName: String
    let createdAt: String
    let updatedAt: String
    /// "cobro" o "envio".
    let kind: String
    var id: String { movementId }

    private enum CodingKeys: String, CodingKey {
        case movementId, state, amount, currency, concept, payerName, createdAt, updatedAt, kind
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        movementId = try c.decode(String.self, forKey: .movementId)
        state = try c.decode(String.self, forKey: .state)
        amount = try c.decode(FlexibleString.self, forKey: .amount).value
        currency = try c.decode(String.self, forKey: .currency)
        concept = try c.decode(String.self, forKey: .concept)
        payerName = try c.decode(String.self, forKey: .payerName)
        createdAt = try c.decode(String.self, forKey: .createdAt)
        updatedAt = try c.decode(String.self, forKey: .updatedAt)
        kind = try c.decodeIfPresent(String.self, forKey: .kind).nonEmpty ?? "cobro"
    }
}

extension Optional where Wrapped == String {
    /// Equivale a `optString(...).ifEmpty { null }` del cliente Android.
    var nonEmpty: String? {
        guard let value = self, !value.isEmpty else { return nil }
        return value
    }
}
