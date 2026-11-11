import Foundation

// Traducción de AppState.kt, AppLock.kt y Transfers.kt.

enum AppTab: Hashable, CaseIterable { case home, movements, assistant, contacts }

enum StatusKind { case success, pending, failure, neutral }

let lockTimeoutSeconds: TimeInterval = 5 * 60

func shouldLockOnResume(backgroundedAt: Date?, now: Date, timeout: TimeInterval = lockTimeoutSeconds) -> Bool {
    guard let backgroundedAt else { return false }
    return now.timeIntervalSince(backgroundedAt) >= timeout
}

func maskAmount(_ formatted: String, visible: Bool) -> String {
    if visible { return formatted }
    let suffix = formatted.range(of: " [A-Z]{3}$", options: .regularExpression).map { String(formatted[$0]) } ?? ""
    return "$ ••••\(suffix)"
}

/// Salir de la pantalla de Cobrar solo se permite cuando no se pierde un cobro en curso.
func allowsTabSwitch(resolve: ResolveState, charge: ChargeState) -> Bool {
    if resolve.isResolving { return false }
    if !resolve.isIdentified { return true }
    switch charge {
    case .ready: return true
    case let .error(_, recoverable): return recoverable
    case let .created(_, _, payment): return isTerminal(payment)
    default: return false
    }
}

/// Solo se abre el enlace de aprobación si es https y el host es exactamente el de PayPal Sandbox.
func isAllowedApproveUrl(_ url: String?) -> Bool {
    guard let url, !url.trimmingCharacters(in: .whitespaces).isEmpty,
          let components = URLComponents(string: url) else { return false }
    return components.scheme?.lowercased() == "https"
        && components.host?.lowercased() == "www.sandbox.paypal.com"
        && components.user == nil && components.password == nil
        && (components.port == nil || components.port == 443)
}

func movementStatusLabel(_ state: String, kind: String = "cobro") -> String {
    if kind == "envio" {
        switch state {
        case "procesando": return L("Enviando")
        case "completada": return L("Enviado")
        case "no_reclamada": return L("Sin reclamar")
        case "fallida": return L("Falló")
        default: return L("Desconocido")
        }
    }
    switch state {
    case "pagada": return L("Pagado")
    case "aprobada", "enviada": return L("Procesando")
    case "pendiente_de_pagador", "creada": return L("Esperando al pagador")
    case "rechazada": return L("Rechazado")
    case "expirada": return L("Expirado")
    case "fallida": return L("Falló")
    default: return L("Desconocido")
    }
}

/// Solo los movimientos que no movieron dinero se pueden quitar de la lista (mismas reglas que el
/// servidor, que es quien decide). Es un ocultado: el registro y el saldo no cambian.
func canHideMovement(_ state: String, kind: String = "cobro") -> Bool {
    kind == "envio" ? state == "fallida" : ["expirada", "rechazada", "fallida"].contains(state)
}

func movementStatusKind(_ state: String, kind: String = "cobro") -> StatusKind {
    if kind == "envio" {
        switch state {
        case "procesando", "no_reclamada": return .pending
        case "completada": return .success
        case "fallida": return .failure
        default: return .neutral
        }
    }
    switch state {
    case "pagada": return .success
    case "aprobada", "enviada", "pendiente_de_pagador", "creada": return .pending
    case "rechazada", "expirada", "fallida": return .failure
    default: return .neutral
    }
}

private let posixLocale = Locale(identifier: "en_US_POSIX")

/// "$350.00 MXN", sin separador de miles (igual que Inicio en Android).
func formatMoney(_ amount: String, _ currency: String) -> String {
    guard let number = Decimal(string: amount.trimmingCharacters(in: .whitespaces), locale: posixLocale) else {
        return "\(amount) \(currency)"
    }
    var value = number
    var rounded = Decimal()
    NSDecimalRound(&rounded, &value, 2, .plain)
    let formatter = NumberFormatter()
    formatter.locale = posixLocale
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 2
    formatter.usesGroupingSeparator = false
    return "$" + (formatter.string(from: rounded as NSDecimalNumber) ?? amount) + " " + currency
}

/// Quita la moneda final: "$350.00 MXN" → "$350.00".
func withoutCurrency(_ formatted: String) -> String {
    guard let index = formatted.lastIndex(of: " ") else { return formatted }
    return String(formatted[..<index])
}

func parseISODate(_ iso: String) -> Date? {
    let withFraction = ISO8601DateFormatter()
    withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return withFraction.date(from: iso) ?? ISO8601DateFormatter().date(from: iso)
}

func formatMovementDate(_ iso: String) -> String {
    guard let date = parseISODate(iso) else { return iso }
    let formatter = DateFormatter()
    formatter.locale = currentLanguage.locale
    formatter.dateFormat = currentLanguage == .en ? "MMM d, yyyy, h:mm a" : "d MMM yyyy, HH:mm"
    return formatter.string(from: date)
}

func remainingSeconds(_ expiresAt: String, now: Date = Date()) -> Int {
    guard let expiration = parseISODate(expiresAt) else { return 0 }
    return max(0, Int(expiration.timeIntervalSince(now)))
}

func formatCountdown(_ seconds: Int) -> String {
    String(format: "%d:%02d", seconds / 60, seconds % 60)
}

// MARK: - Envíos (Transfers.kt)

let maxTransferAmount: Decimal = 1000
let maxTransferNoteLength = 127

/// Devuelve el monto con 2 decimales ("100.00") o nil si no es válido. Nunca devuelve más de lo escrito.
func parseAmountInput(_ text: String, max: Decimal = maxTransferAmount) -> String? {
    let normalized = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
    guard normalized.range(of: #"^\d+(\.\d{1,2})?$"#, options: .regularExpression) != nil,
          let value = Decimal(string: normalized, locale: posixLocale),
          value > 0, value <= max else { return nil }
    let formatter = NumberFormatter()
    formatter.locale = posixLocale
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 2
    formatter.usesGroupingSeparator = false
    return formatter.string(from: value as NSDecimalNumber)
}

func isValidPayoutEmail(_ text: String) -> Bool {
    let trimmed = text.trimmingCharacters(in: .whitespaces)
    return trimmed.count <= 127 && trimmed.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) != nil
}

func transferErrorMessage(_ type: ApiFailureType, serverError: String?, recipientName: String?) -> String {
    switch type {
    case .conflict:
        switch serverError {
        case "recipient cannot receive":
            return L("%@ todavía no registró su correo para recibir envíos.", recipientName ?? L("El destinatario"))
        case "insufficient balance": return L("No tienes saldo suficiente.")
        default: return L("No se pudo completar el envío.")
        }
    case .badRequest: return L("Revisa el monto (máximo 1,000 MXN).")
    case .notFound: return L("La vista previa venció. Revísala de nuevo.")
    case .rateLimited: return L("Demasiados intentos. Espera unos segundos.")
    default: return L("No pudimos confirmar el envío. Revisa Movimientos antes de intentarlo de nuevo.")
    }
}

var sessionInvalidMessage: String { L("Tu sesión ya no es válida. Crea tu cuenta de nuevo.") }
var sessionInvalidConfirmMessage: String { L("Tu sesión ya no es válida. El cobro no se envió. Crea tu cuenta de nuevo.") }

/// Iniciales para el avatar ("Ana López" → "AL").
func initials(_ name: String) -> String {
    let parts = name.split(whereSeparator: \.isWhitespace).prefix(2)
    let letters = parts.compactMap { $0.first.map(String.init) }.joined()
    return letters.isEmpty ? "?" : letters.uppercased()
}
