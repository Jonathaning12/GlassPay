import SwiftUI

// Tokens del tema oscuro: los mismos valores que GlassPayTheme.kt y la colección "Color" de Figma.
// Tipografía: San Francisco (la del sistema en iOS) con la misma escala que Roboto en Android.

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}

enum GP {
    static let background = Color(hex: 0x0A0D14) // bg/base
    static let card = Color(hex: 0x131826) // bg/surface
    static let raised = Color(hex: 0x1B2233) // bg/raised
    static let line = Color(hex: 0x283044) // line/default
    static let text = Color(hex: 0xF5F7FB) // text/primary
    static let muted = Color(hex: 0xA3ADC2) // text/muted
    static let onAccent = Color(hex: 0x00151C) // text/on-accent
    static let accent = Color(hex: 0x7FE7FF) // accent/default
    static let accentSoft = Color(hex: 0x12303A) // accent/soft
    static let success = Color(hex: 0x63E6A0)
    static let successSoft = Color(hex: 0x10301F)
    static let warning = Color(hex: 0xFFC56B)
    static let warningSoft = Color(hex: 0x33260F)
    static let error = Color(hex: 0xFF8A80)
    static let errorSoft = Color(hex: 0x3A1614)
    static let qrPaper = Color.white
    // Series de gráficas validadas para daltonismo sobre `card`.
    static let chartPaid = Color(hex: 0x14A3C4)
    static let chartSent = Color(hex: 0x9878F0)

    static let radiusSmall: CGFloat = 10
    static let radiusMedium: CGFloat = 16
    static let radiusLarge: CGFloat = 24

    static func status(_ kind: StatusKind) -> Color {
        switch kind {
        case .success: success
        case .pending: warning
        case .failure: error
        case .neutral: muted
        }
    }

    static func statusSoft(_ kind: StatusKind) -> Color {
        switch kind {
        case .success: successSoft
        case .pending: warningSoft
        case .failure: errorSoft
        case .neutral: raised
        }
    }
}

extension Font {
    static let gpAmountLarge = Font.system(size: 48, weight: .bold, design: .rounded)
    static let gpAmount = Font.system(size: 40, weight: .bold, design: .rounded)
    static let gpAmountMedium = Font.system(size: 32, weight: .semibold, design: .rounded)
    static let gpTitle = Font.system(size: 26, weight: .semibold)
    static let gpHeading = Font.system(size: 20, weight: .semibold)
    static let gpBody = Font.system(size: 17)
    static let gpBodyStrong = Font.system(size: 17, weight: .medium)
    static let gpLabel = Font.system(size: 15, weight: .medium)
    static let gpNote = Font.system(size: 13)
    static let gpOverline = Font.system(size: 12, weight: .semibold)
}

/// Iconos: SF Symbols, el equivalente nativo de los vectores de línea de Android (res/drawable/ic_*).
enum GPIcon {
    static let home = "house"
    static let movements = "list.bullet.rectangle"
    static let charge = "qrcode.viewfinder"
    static let contacts = "person.2"
    static let send = "paperplane"
    static let receive = "arrow.down.circle"
    static let qr = "qrcode"
    static let check = "checkmark"
    static let close = "xmark"
    static let clock = "clock"
    static let chevron = "chevron.right"
    static let lock = "lock"
    static let wifiOff = "wifi.slash"
    static let keyboard = "keyboard"
    static let mail = "envelope"
    static let chart = "chart.bar"
    static let trash = "trash"
    static let mic = "mic"
    static let plus = "plus"
    static let assistant = "sparkles"
}
