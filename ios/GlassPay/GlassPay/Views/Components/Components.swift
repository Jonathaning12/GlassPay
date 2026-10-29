import SwiftUI

// Componentes compartidos (GlassPayUi.kt): botón, tarjeta, chip de estado, avatar, avisos y montos.

enum GPButtonKind { case primary, secondary, ghost, danger }

struct GPButtonStyle: ButtonStyle {
    let kind: GPButtonKind
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.gpLabel)
            .frame(maxWidth: kind == .ghost ? nil : .infinity, minHeight: kind == .ghost ? 44 : 56)
            .padding(.horizontal, kind == .ghost ? 8 : 20)
            .foregroundStyle(foreground)
            .background(background, in: RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous))
            .overlay {
                if kind == .secondary {
                    RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous).stroke(GP.line, lineWidth: 1)
                }
            }
            .opacity(isEnabled ? 1 : 0.45)
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }

    private var foreground: Color {
        switch kind {
        case .primary: GP.onAccent
        case .secondary: GP.text
        case .ghost: GP.accent
        case .danger: GP.background
        }
    }

    private var background: Color {
        switch kind {
        case .primary: GP.accent
        case .secondary: GP.card
        case .ghost: .clear
        case .danger: GP.error
        }
    }
}

struct GPButton: View {
    let title: String
    var icon: String?
    var kind: GPButtonKind = .primary
    let action: () -> Void

    init(_ title: String, icon: String? = nil, kind: GPButtonKind = .primary, action: @escaping () -> Void) {
        self.title = title
        self.icon = icon
        self.kind = kind
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if let icon { Image(systemName: icon).font(.system(size: 18, weight: .semibold)) }
                Text(title)
            }
        }
        .buttonStyle(GPButtonStyle(kind: kind))
    }
}

struct GPCard<Content: View>: View {
    var padding: CGFloat = 20
    var spacing: CGFloat = 16
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(GP.card, in: RoundedRectangle(cornerRadius: GP.radiusLarge, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: GP.radiusLarge, style: .continuous).stroke(GP.line, lineWidth: 1))
    }
}

struct StatusPill: View {
    let text: String
    let kind: StatusKind

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(GP.status(kind)).frame(width: 8, height: 8)
            Text(text).font(.gpNote.weight(.medium)).foregroundStyle(GP.status(kind))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(GP.statusSoft(kind), in: Capsule())
        .accessibilityElement(children: .combine)
    }
}

struct AvatarView: View {
    let name: String
    var size: CGFloat = 40
    var highlighted = false

    var body: some View {
        Text(initials(name))
            .font(.system(size: size * 0.38, weight: .semibold))
            .foregroundStyle(highlighted ? GP.onAccent : GP.accent)
            .frame(width: size, height: size)
            .background(highlighted ? GP.accent : GP.accentSoft, in: Circle())
            .accessibilityHidden(true)
    }
}

struct IconBadge: View {
    let icon: String
    var tint: Color = GP.accent
    var size: CGFloat = 40
    var background: Color = GP.accentSoft

    var body: some View {
        Image(systemName: icon)
            .font(.system(size: size * 0.45, weight: .medium))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(background, in: Circle())
            .accessibilityHidden(true)
    }
}

struct Overline: View {
    let text: String
    var color: Color = GP.muted
    init(_ text: String, color: Color = GP.muted) { self.text = text; self.color = color }

    var body: some View {
        Text(text.uppercased()).font(.gpOverline).tracking(1.2).foregroundStyle(color)
    }
}

struct InlineNotice: View {
    let text: String
    var kind: StatusKind = .neutral
    var icon: String?

    init(_ text: String, kind: StatusKind = .neutral, icon: String? = nil) {
        self.text = text
        self.kind = kind
        self.icon = icon
    }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon ?? defaultIcon).foregroundStyle(kind == .neutral ? GP.accent : GP.status(kind))
            Text(text).font(.gpLabel.weight(.regular)).foregroundStyle(GP.text).frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .background(kind == .neutral ? GP.raised : GP.statusSoft(kind), in: RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private var defaultIcon: String {
        switch kind {
        case .success: "checkmark.circle"
        case .pending: "exclamationmark.circle"
        case .failure: "xmark.octagon"
        case .neutral: "info.circle"
        }
    }
}

/// Monto grande con la moneda en pequeño al lado ("$350.00" + "MXN").
struct MoneyText: View {
    let formatted: String
    var font: Font = .gpAmount
    var color: Color = GP.text

    var body: some View {
        let parts = formatted.split(separator: " ")
        let hasCurrency = parts.count > 1 && parts.last?.count == 3
        HStack(alignment: .lastTextBaseline, spacing: 8) {
            Text(hasCurrency ? withoutCurrency(formatted) : formatted)
                .font(font).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.6)
            if hasCurrency, let currency = parts.last {
                Text(currency).font(.gpBodyStrong).foregroundStyle(GP.muted)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

struct EyeToggle: View {
    let visible: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            Image(systemName: visible ? "eye" : "eye.slash")
                .contentTransition(.symbolEffect(.replace))
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(GP.muted)
                .frame(width: 44, height: 44)
        }
        .accessibilityLabel(visible ? "Ocultar montos" : "Mostrar montos")
    }
}

struct SkeletonBox: View {
    var height: CGFloat = 20
    @State private var dim = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(GP.raised)
            .frame(height: height)
            .opacity(dim ? 0.55 : 1)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { dim = true }
            }
            .accessibilityHidden(true)
    }
}

struct WorkingRow: View {
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            ProgressView().tint(GP.accent)
            Text(text).font(.gpBodyStrong).foregroundStyle(GP.text)
        }
    }
}

/// Esquinas del visor de QR (ScanFrame en Android).
struct ScanFrame: Shape {
    func path(in rect: CGRect) -> Path {
        let arm: CGFloat = 50
        let radius: CGFloat = 24
        var path = Path()
        func corner(_ x: CGFloat, _ y: CGFloat, _ dx: CGFloat, _ dy: CGFloat) {
            path.move(to: CGPoint(x: x, y: y + dy * arm))
            path.addLine(to: CGPoint(x: x, y: y + dy * radius))
            path.addQuadCurve(to: CGPoint(x: x + dx * radius, y: y), control: CGPoint(x: x, y: y))
            path.addLine(to: CGPoint(x: x + dx * arm, y: y))
        }
        corner(rect.minX, rect.minY, 1, 1)
        corner(rect.maxX, rect.minY, -1, 1)
        corner(rect.minX, rect.maxY, 1, -1)
        corner(rect.maxX, rect.maxY, -1, -1)
        return path
    }
}

extension View {
    func gpScreen() -> some View {
        frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top).background(GP.background.ignoresSafeArea())
    }

    func gpField() -> some View {
        padding(16)
            .background(GP.card, in: RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous).stroke(GP.line, lineWidth: 1))
            .foregroundStyle(GP.text)
            .tint(GP.accent)
    }
}
