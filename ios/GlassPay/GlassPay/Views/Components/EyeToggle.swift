import SwiftUI

// Ojo para mostrar/ocultar montos y montos que se ocultan sin saltos (EyeToggle.kt en Android).

/// Duración compartida del ojo y de los montos: el párpado termina de cerrar justo cuando la cifra se oculta.
let amountToggleAnimation = Animation.timingCurve(0.4, 0, 0.2, 1, duration: 0.26)

/// Botón de 48 pt para mostrar u ocultar montos. VoiceOver: acción + estado actual.
struct EyeToggle: View {
    let visible: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            EyeGlyph(open: visible)
                .frame(width: 48, height: 48)
                .background(GP.raised, in: Circle())
        }
        .buttonStyle(PressScaleStyle())
        .accessibilityLabel(visible ? "Ocultar montos" : "Mostrar montos")
        .accessibilityValue(visible ? "Montos visibles" : "Montos ocultos")
    }
}

/**
 Ojo que parpadea de verdad: al ocultar, el párpado superior baja hasta juntarse con el inferior y
 tapa la pupila; cerrado queda como un ojo dormido (curva y pestañas). Al mostrar, el párpado sube.
 Sin rebotes, al mismo ritmo que los montos.
 */
struct EyeGlyph: View {
    let open: Bool
    var color: Color = GP.text
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let progress: Double = open ? 1 : 0
        let line = StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round)
        ZStack {
            // La pupila no se encoge: el párpado la va tapando porque solo se pinta dentro del ojo.
            EyePupil(progress: progress).fill(color).clipShape(EyeOutline(progress: progress))
            EyeOutline(progress: progress).stroke(color, style: line)
            EyeLashes(progress: progress).stroke(color, style: line)
        }
        .frame(width: 24, height: 24)
        .animation(reduceMotion ? nil : amountToggleAnimation, value: open)
        .accessibilityHidden(true)
    }
}

/// Geometría compartida de las tres piezas del ojo, en función de cuánto está abierto (0…1).
private struct EyeGeometry {
    let left: CGPoint
    let right: CGPoint
    let center: CGPoint
    let upperControlY: CGFloat
    let lowerControlY: CGFloat
    let halfHeight: CGFloat

    init(_ rect: CGRect, _ progress: Double) {
        let p = CGFloat(progress)
        let halfWidth = rect.width * 0.42
        halfHeight = rect.height * 0.27
        // Cerrado, el dibujo sube un poco para que curva + pestañas queden centradas.
        let cy = rect.midY - halfHeight * 0.45 * (1 - p)
        center = CGPoint(x: rect.midX, y: cy)
        left = CGPoint(x: rect.midX - halfWidth, y: cy)
        right = CGPoint(x: rect.midX + halfWidth, y: cy)
        lowerControlY = cy + 2 * halfHeight
        // El párpado superior pasa de arriba (abierto) a coincidir con el inferior (cerrado).
        upperControlY = lowerControlY - 4 * halfHeight * p
    }

    /// Punto y normal hacia afuera en la curva inferior (Bézier cuadrática) para el parámetro t.
    func lowerPoint(_ t: CGFloat) -> (point: CGPoint, normal: CGVector) {
        let u = 1 - t
        let point = CGPoint(
            x: u * u * left.x + 2 * u * t * center.x + t * t * right.x,
            y: u * u * left.y + 2 * u * t * lowerControlY + t * t * right.y
        )
        let tangent = CGVector(
            dx: 2 * u * (center.x - left.x) + 2 * t * (right.x - center.x),
            dy: 2 * u * (lowerControlY - left.y) + 2 * t * (right.y - lowerControlY)
        )
        let length = max(0.001, (tangent.dx * tangent.dx + tangent.dy * tangent.dy).squareRoot())
        return (point, CGVector(dx: -tangent.dy / length, dy: tangent.dx / length))
    }
}

private struct EyeOutline: Shape {
    var progress: Double
    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let eye = EyeGeometry(rect, progress)
        var path = Path()
        path.move(to: eye.left)
        path.addQuadCurve(to: eye.right, control: CGPoint(x: eye.center.x, y: eye.upperControlY))
        path.addQuadCurve(to: eye.left, control: CGPoint(x: eye.center.x, y: eye.lowerControlY))
        path.closeSubpath()
        return path
    }
}

private struct EyePupil: Shape {
    var progress: Double
    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func path(in rect: CGRect) -> Path {
        guard progress > 0.02 else { return Path() }
        let eye = EyeGeometry(rect, progress)
        let radius = rect.width * 0.12
        let center = CGPoint(x: eye.center.x, y: eye.center.y + eye.halfHeight * 0.1)
        return Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
    }
}

/// Pestañas: crecen hacia afuera del párpado inferior conforme el ojo se cierra.
private struct EyeLashes: Shape {
    var progress: Double
    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let lash = rect.width / 24 * 3.2 * (1 - CGFloat(progress))
        guard lash > 0.3 else { return Path() }
        let eye = EyeGeometry(rect, progress)
        var path = Path()
        for t in [0.22, 0.5, 0.78] as [CGFloat] {
            let (point, normal) = eye.lowerPoint(t)
            path.move(to: point)
            path.addLine(to: CGPoint(x: point.x + normal.dx * lash, y: point.y + normal.dy * lash))
        }
        return path
    }
}

/**
 Monto que se puede ocultar sin saltos: las dos versiones (cifra y "••••") se dibujan una sobre otra
 y el contenedor toma el ancho mayor, así la tarjeta no cambia de tamaño. El cambio es un fundido
 cruzado con un desenfoque breve. Para VoiceOver solo existe la versión vigente.
 */
struct MaskedMoney: View {
    let formatted: String
    let visible: Bool
    var font: Font = .gpAmount
    var large = true
    var color: Color = GP.text
    var prefix = ""
    var alignment: Alignment = .leading
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        // Solo cambia la cifra; la moneda ("MXN") se dibuja una vez y no se mueve.
        let last = formatted.split(separator: " ").last.map(String.init) ?? ""
        let currency = formatted.contains(" ") && last.count == 3 && last.allSatisfy(\.isUppercase) ? last : ""
        let amount = prefix + (currency.isEmpty ? formatted : withoutCurrency(formatted))
        let masked = prefix + (currency.isEmpty
            ? maskAmount(formatted, visible: false)
            : String(maskAmount(formatted, visible: false).dropLast(currency.count + 1)))
        HStack(alignment: .lastTextBaseline, spacing: large ? 8 : 4) {
            ZStack(alignment: alignment) {
                amountText(masked).modifier(FadeBlur(alpha: visible ? 0 : 1))
                amountText(amount).modifier(FadeBlur(alpha: visible ? 1 : 0))
            }
            .animation(reduceMotion ? nil : amountToggleAnimation, value: visible)
            if !currency.isEmpty {
                Text(currency)
                    .font(large ? .gpBodyStrong : font)
                    .foregroundStyle(large ? GP.muted : color)
                    .lineLimit(1)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(visible ? prefix + formatted : "Monto oculto")
    }

    private func amountText(_ text: String) -> some View {
        Text(text).font(font).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.6)
    }
}

/// Opacidad con desenfoque máximo a mitad del cambio; en reposo (0 o 1) no se desenfoca.
private struct FadeBlur: ViewModifier, Animatable {
    var alpha: Double
    var animatableData: Double {
        get { alpha }
        set { alpha = newValue }
    }

    func body(content: Content) -> some View {
        content.opacity(alpha).blur(radius: (1 - abs(alpha * 2 - 1)) * 4)
    }
}
