import SwiftUI

struct MovementsView: View {
    @Binding var amountsVisible: Bool
    @State private var model: MovementsViewModel

    init(context: AppContext, amountsVisible: Binding<Bool>) {
        _amountsVisible = amountsVisible
        _model = State(initialValue: MovementsViewModel(context: context))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Movimientos").font(.gpTitle).foregroundStyle(GP.text)
                        Text("Actividad en GlassPay (Sandbox)").font(.gpNote).foregroundStyle(GP.muted)
                    }
                    Spacer()
                    EyeToggle(visible: amountsVisible) { amountsVisible.toggle() }
                }
                .padding(.bottom, 8)
                switch model.state {
                case .loading:
                    ForEach(0..<5, id: \.self) { _ in SkeletonBox(height: 68) }
                case .failed:
                    InlineNotice("No pudimos cargar tus movimientos.", kind: .failure)
                    GPButton("Reintentar", kind: .secondary) { Task { await model.reload() } }
                case let .loaded(movements):
                    if movements.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Aún no tienes movimientos.").font(.gpBodyStrong).foregroundStyle(GP.text)
                            Text("Tus cobros y envíos aparecerán aquí.").font(.gpNote).foregroundStyle(GP.muted)
                        }
                    }
                    ForEach(movements) { movement in
                        NavigationLink(value: movement.movementId) { row(movement) }.buttonStyle(.plain)
                    }
                }
            }
            .padding(20)
        }
        .refreshable { await model.reload() }
        .gpScreen()
        .toolbar(.hidden, for: .navigationBar)
        .task { await model.reload() }
        .navigationDestination(for: String.self) { id in
            if let movement = model.state.value?.first(where: { $0.movementId == id }) {
                MovementDetailView(movement: movement, amountsVisible: amountsVisible)
            }
        }
    }

    private func row(_ movement: Movement) -> some View {
        HStack(spacing: 12) {
            IconBadge(icon: movement.kind == "envio" ? GPIcon.send : GPIcon.receive,
                      tint: movement.kind == "envio" ? GP.text : GP.success,
                      background: movement.kind == "envio" ? GP.raised : GP.successSoft)
            VStack(alignment: .leading, spacing: 4) {
                Text(movementTitle(movement)).font(.gpBodyStrong).foregroundStyle(GP.text).lineLimit(1)
                Text(movement.concept).font(.gpNote).foregroundStyle(GP.muted).lineLimit(1)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(signedAmount(movement, visible: amountsVisible)).font(.gpBodyStrong).foregroundStyle(GP.text)
                StatusPill(text: movementStatusLabel(movement.state, kind: movement.kind), kind: movementStatusKind(movement.state, kind: movement.kind))
            }
        }
        .padding(14)
        .background(GP.card, in: RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous).stroke(GP.line))
        .accessibilityElement(children: .combine)
    }
}

private func movementTitle(_ movement: Movement) -> String {
    movement.kind == "envio" ? "Enviado a \(movement.payerName)" : movement.payerName
}

private func signedAmount(_ movement: Movement, visible: Bool) -> String {
    let formatted = maskAmount(formatMoney(movement.amount, movement.currency), visible: visible)
    return movement.kind == "envio" ? "−\(formatted)" : formatted
}

private struct MovementDetailView: View {
    let movement: Movement
    let amountsVisible: Bool

    var body: some View {
        let outgoing = movement.kind == "envio"
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                GPCard(padding: 24, spacing: 16) {
                    StatusPill(text: movementStatusLabel(movement.state, kind: movement.kind), kind: movementStatusKind(movement.state, kind: movement.kind))
                    MoneyText(formatted: signedAmount(movement, visible: amountsVisible))
                    line("Concepto", movement.concept)
                    line(outgoing ? "Destinatario" : "Pagador", movement.payerName)
                    line("Creado", formatMovementDate(movement.createdAt))
                    line("Actualizado", formatMovementDate(movement.updatedAt))
                }
                InlineNotice("Actividad en GlassPay (Sandbox). Solo el servidor confirma un pago.", icon: GPIcon.lock)
            }
            .padding(20)
        }
        .gpScreen()
        .navigationTitle(outgoing ? "Detalle del envío" : "Detalle del cobro")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func line(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.gpNote).foregroundStyle(GP.muted)
            Text(value).font(.gpBody).foregroundStyle(GP.text)
        }
        .accessibilityElement(children: .combine)
    }
}
