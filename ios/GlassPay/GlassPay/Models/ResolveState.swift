import Foundation

// Traducción de android/.../ResolveState.kt: los resultados atrasados (otro attemptId) no cambian el estado.

enum ResolveState: Equatable {
    case scanning
    case resolving(attemptId: Int, token: String, waking: Bool)
    case retryableError(attemptId: Int, token: String, message: String)
    case failed(message: String)
    case identified(displayName: String, resolutionId: String, expiresAt: String, contactId: String?)

    var isIdentified: Bool { if case .identified = self { return true }; return false }
    var isResolving: Bool { if case .resolving = self { return true }; return false }
}

enum ResolveFailureType {
    case ownQr, network, timeout, notFound, rateLimited, server, invalidResponse

    var message: String {
        switch self {
        case .network, .timeout: return "No pudimos conectar con GlassPay. Revisa tu conexión."
        case .rateLimited: return "Demasiados intentos. Espera unos segundos."
        case .server: return "GlassPay no pudo procesar el QR. Intenta de nuevo."
        case .invalidResponse: return "Recibimos una respuesta inválida de GlassPay."
        case .notFound: return "El QR no es válido o ya venció."
        case .ownQr: return "Ese es tu propio QR."
        }
    }

    init(_ api: ApiFailureType) {
        switch api {
        case .unauthorized, .badRequest, .invalidResponse: self = .invalidResponse
        case .conflict: self = .ownQr
        case .network: self = .network
        case .timeout: self = .timeout
        case .notFound: self = .notFound
        case .rateLimited: self = .rateLimited
        case .server: self = .server
        }
    }
}

enum ResolveEvent {
    case begin(attemptId: Int, token: String, waking: Bool)
    case success(attemptId: Int, resolution: PayerResolution)
    case contactResolved(PayerResolution)
    case contactSaved(contactId: String)
    case failure(attemptId: Int, type: ResolveFailureType)
    case reset
}

struct ResolveStateMachine {
    private(set) var state: ResolveState = .scanning

    @discardableResult
    mutating func dispatch(_ event: ResolveEvent) -> ResolveState {
        switch event {
        case let .begin(attemptId, token, waking):
            if case let .resolving(_, current, _) = state, current == token { break }
            state = .resolving(attemptId: attemptId, token: token, waking: waking)
        case let .success(attemptId, resolution):
            if matches(attemptId) { state = resolution.identifiedState }
        case let .failure(attemptId, type):
            guard matches(attemptId) else { break }
            switch type {
            case .notFound: state = .scanning
            case .network, .timeout: state = .retryableError(attemptId: attemptId, token: currentToken, message: type.message)
            default: state = .failed(message: type.message)
            }
        case let .contactResolved(resolution):
            if !state.isResolving { state = resolution.identifiedState }
        case let .contactSaved(contactId):
            if case let .identified(name, resolutionId, expiresAt, _) = state {
                state = .identified(displayName: name, resolutionId: resolutionId, expiresAt: expiresAt, contactId: contactId)
            }
        case .reset:
            state = .scanning
        }
        return state
    }

    private var currentToken: String {
        switch state {
        case let .resolving(_, token, _), let .retryableError(_, token, _): return token
        default: return ""
        }
    }

    private func matches(_ attemptId: Int) -> Bool {
        switch state {
        case let .resolving(id, _, _), let .retryableError(id, _, _): return id == attemptId
        default: return false
        }
    }
}

private extension PayerResolution {
    var identifiedState: ResolveState {
        .identified(displayName: displayName, resolutionId: resolutionId, expiresAt: expiresAt, contactId: contactId)
    }
}

enum QrTokenParser {
    private static let prefix = "glasspay:"

    /// Solo se aceptan QR "glasspay:<token>"; cualquier otro se ignora sin llamar al servidor.
    static func extractToken(_ rawValue: String?) -> String? {
        guard let rawValue, rawValue.hasPrefix(prefix) else { return nil }
        let token = String(rawValue.dropFirst(prefix.count))
        guard (20...256).contains(token.count), token.allSatisfy(isTokenCharacter) else { return nil }
        return token
    }

    private static func isTokenCharacter(_ character: Character) -> Bool {
        character.isLetter || character.isNumber || character == "-" || character == "_" || character == "="
    }
}
