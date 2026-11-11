import Foundation
import Observation

// Idioma de la app (español por defecto, inglés opcional), elegido en Perfil y guardado solo en este
// teléfono. Los textos se escriben en español y `L(...)` los traduce con el diccionario de
// `EnglishStrings.swift`. Como `L` lee `AppLanguageStore.shared.current`, cualquier vista que lo use
// se vuelve a dibujar al cambiar de idioma (Observation registra la lectura).

enum AppLanguage: String, CaseIterable, Identifiable {
    case es, en
    var id: String { rawValue }

    /// Nombre del idioma en su propio idioma, para el selector.
    var nativeName: String {
        switch self {
        case .es: "Español"
        case .en: "English"
        }
    }

    /// Reconocimiento y síntesis de voz.
    var speechLocaleIdentifier: String {
        switch self {
        case .es: "es-MX"
        case .en: "en-US"
        }
    }

    /// Fechas y textos con formato regional.
    var locale: Locale {
        switch self {
        case .es: Locale(identifier: "es_MX")
        case .en: Locale(identifier: "en_US")
        }
    }
}

@Observable
final class AppLanguageStore: @unchecked Sendable {
    static let shared = AppLanguageStore()
    private static let preferenceKey = "glasspay.language"

    var current: AppLanguage {
        didSet { UserDefaults.standard.set(current.rawValue, forKey: Self.preferenceKey) }
    }

    private init() {
        // En las pruebas la app siempre arranca en español, sin importar lo elegido en el simulador.
        let runningTests = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
        let saved = runningTests ? nil : UserDefaults.standard.string(forKey: Self.preferenceKey)
        current = saved.flatMap(AppLanguage.init(rawValue:)) ?? .es
    }
}

var currentLanguage: AppLanguage { AppLanguageStore.shared.current }

/// Traduce un texto escrito en español. Con argumentos, la clave es un formato (`%@`, `%d`) y los
/// valores se insertan después de traducir. Si falta la traducción se muestra el español.
func L(_ spanish: String, _ arguments: CVarArg...) -> String {
    localized(spanish, language: currentLanguage, arguments)
}

func localized(_ spanish: String, language: AppLanguage, _ arguments: [CVarArg] = []) -> String {
    let template = language == .en ? (englishStrings[spanish] ?? spanish) : spanish
    guard !arguments.isEmpty else { return template }
    return String(format: template, locale: language.locale, arguments: arguments)
}
