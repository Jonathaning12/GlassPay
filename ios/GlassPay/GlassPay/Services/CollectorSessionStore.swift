import Foundation
import Security

// Equivalente iOS de CollectorSession.kt: en Android el secreto se cifra con AndroidKeyStore; aquí vive
// en el Llavero, solo en este dispositivo y solo mientras está desbloqueado (no viaja en respaldos).

protocol SecureStore {
    func read(_ key: String) -> Data?
    func write(_ values: [String: Data]) throws
    func clear()
}

final class CollectorSessionStore {
    private let store: SecureStore

    init(store: SecureStore = KeychainStore()) {
        self.store = store
    }

    func load() -> CollectorSession? {
        let id = store.read(Key.id).flatMap { String(data: $0, encoding: .utf8) }
        let name = store.read(Key.name).flatMap { String(data: $0, encoding: .utf8) }
        let secret = store.read(Key.secret).flatMap { String(data: $0, encoding: .utf8) }
        guard let id, let name, let secret, !id.isEmpty, !secret.isEmpty else {
            // Datos a medias: se limpian; si no hay nada guardado, no hay nada que limpiar.
            if id != nil || name != nil || secret != nil { clear() }
            return nil
        }
        return CollectorSession(collectorId: id, sessionSecret: secret, displayName: name)
    }

    func save(_ session: CollectorSession) throws {
        try store.write([
            Key.id: Data(session.collectorId.utf8),
            Key.name: Data(session.displayName.utf8),
            Key.secret: Data(session.sessionSecret.utf8),
        ])
    }

    func clear() { store.clear() }

    private enum Key {
        static let id = "collectorId"
        static let name = "displayName"
        static let secret = "sessionSecret"
    }
}

struct KeychainStore: SecureStore {
    var service = "com.glasspay.ios.collector"

    func read(_ key: String) -> Data? {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    func write(_ values: [String: Data]) throws {
        for (key, data) in values {
            SecItemDelete(baseQuery(key) as CFDictionary)
            var item = baseQuery(key)
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            let status = SecItemAdd(item as CFDictionary, nil)
            guard status == errSecSuccess else {
                clear()
                throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
            }
        }
    }

    func clear() {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        SecItemDelete(query as CFDictionary)
    }

    private func baseQuery(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }
}
