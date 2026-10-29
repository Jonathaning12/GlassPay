import SwiftUI

// Espera del pago (PaymentProgress.kt): línea de tiempo, "¡Pago recibido!" solo con "pagada" del servidor,
// y vista ámbar/roja para rechazado, expirado o fallido.

struct PaymentProgressView: View {
    let payment: PaymentTracking
    let confirmed: ConfirmedCharge?
    let onNewCharge: () -> Void
    let onOpenMovements: () -> Void

    var body: some View {
        switch paymentStep(payment) {
        case .paid: PaidView(confirmed: confirmed, onNewCharge: onNewCharge, onOpenMovements: onOpenMovements)
        case .stopped: stopped
        default: waiting
        }
    }

    private var waiting: some View {
        let step = paymentStep(payment)
        let active = step == .confirming ? 2 : 1
        let awaitingDetail: String
        if case let .waitingWithMessage(message) = payment { awaitingDetail = message } else { awaitingDetail = "Esperando al pagador." }
        let steps = [
            ("Solicitud enviada", "Ya la ve en su teléfono."),
            ("El pagador está aprobando", active == 1 ? awaitingDetail : "Aprobó en PayPal."),
            ("Confirmando con PayPal", "Solo PayPal puede marcarlo como pagado."),
        ]
        return VStack(alignment: .leading, spacing: 16) {
            summary
            Text("Esperando el pago").font(.gpHeading).foregroundStyle(GP.text)
            GPCard(spacing: 0) {
                ForEach(Array(steps.enumerated()), id: \.offset) { index, item in
                    TimelineStep(title: item.0, detail: item.1, done: index < active, active: index == active, last: index == steps.count - 1)
                }
            }
            InlineNotice("Si dejas de esperar, el pagador todavía podrá aprobar desde su teléfono.", icon: GPIcon.lock)
        }
    }

    @ViewBuilder
    private var summary: some View {
        if let confirmed {
            VStack(alignment: .leading, spacing: 4) {
                Text("Cobro a \(confirmed.payerName)").font(.gpNote).foregroundStyle(GP.muted)
                MoneyText(formatted: formatMoney(confirmed.amount, confirmed.currency), font: .gpAmountMedium)
                Text(confirmed.concept).font(.gpBody).foregroundStyle(GP.muted)
            }
        }
    }

    private var stopped: some View {
        let (text, kind): (String, StatusKind) = {
            switch payment {
            case .rejected: return ("El pagador rechazó la solicitud.", .failure)
            case .expired: return ("La solicitud expiró.", .pending)
            case .failed: return ("El pago falló.", .failure)
            case let .error(message): return (message, .failure)
            default: return ("No pudimos consultar el estado del pago.", .failure)
            }
        }()
        return VStack(alignment: .leading, spacing: 16) {
            summary
            HStack(spacing: 12) {
                IconBadge(icon: kind == .pending ? GPIcon.clock : GPIcon.close, tint: GP.status(kind), size: 56, background: GP.statusSoft(kind))
                Text(text).font(.gpBodyStrong).foregroundStyle(GP.status(kind))
            }
            GPButton("Escanear otro QR", icon: GPIcon.charge, action: onNewCharge)
        }
    }
}

private struct TimelineStep: View {
    let title: String
    let detail: String
    let done: Bool
    let active: Bool
    let last: Bool
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(spacing: 0) {
                ZStack {
                    if active && !reduceMotion {
                        Circle().stroke(GP.accent, lineWidth: 2).frame(width: 28, height: 28)
                            .scaleEffect(pulse ? 1.4 : 1).opacity(pulse ? 0 : 0.8)
                            .onAppear { withAnimation(.easeOut(duration: 1.4).repeatForever(autoreverses: false)) { pulse = true } }
                    }
                    Circle().fill(done ? GP.accent : (active ? GP.accentSoft : GP.raised)).frame(width: 28, height: 28)
                    if done {
                        Image(systemName: GPIcon.check).font(.system(size: 13, weight: .bold)).foregroundStyle(GP.onAccent)
                    } else if active {
                        Circle().fill(GP.accent).frame(width: 10, height: 10)
                    }
                }
                if !last { Rectangle().fill(done ? GP.accent : GP.line).frame(width: 2).frame(minHeight: 28) }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.gpBodyStrong).foregroundStyle(done || active ? GP.text : GP.muted)
                Text(detail).font(.gpNote).foregroundStyle(GP.muted)
            }
            .padding(.bottom, last ? 0 : 16)
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(done ? "Paso completado" : (active ? "Paso en curso" : "Paso pendiente"))
    }
}

private struct PaidView: View {
    let confirmed: ConfirmedCharge?
    let onNewCharge: () -> Void
    let onOpenMovements: () -> Void
    @State private var entered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle().fill(GP.successSoft).frame(width: 156, height: 156)
                Circle().fill(GP.success).frame(width: 104, height: 104)
                Image(systemName: GPIcon.check).font(.system(size: 48, weight: .bold)).foregroundStyle(GP.background)
            }
            .scaleEffect(entered || reduceMotion ? 1 : 0.6)
            .accessibilityLabel("Pago recibido")
            .onAppear { withAnimation(.spring(response: 0.5, dampingFraction: 0.6)) { entered = true } }
            Text("¡Pago recibido!").font(.gpTitle).foregroundStyle(GP.success)
            if let confirmed {
                MoneyText(formatted: formatMoney(confirmed.amount, confirmed.currency))
                Text("De \(confirmed.payerName) · \(confirmed.concept)").font(.gpBody).foregroundStyle(GP.muted)
            }
            Text("Confirmado por PayPal (Sandbox)").font(.gpNote).foregroundStyle(GP.muted)
            GPButton("Nuevo cobro", icon: GPIcon.charge, action: onNewCharge).padding(.top, 16)
            GPButton("Ver en Movimientos", kind: .secondary, action: onOpenMovements)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 24)
    }
}
