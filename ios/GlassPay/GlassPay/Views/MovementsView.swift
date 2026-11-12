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
                        Text(L("Movimientos")).font(.gpTitle).foregroundStyle(GP.text)
                        Text(L("Actividad en GlassPay (Sandbox)")).font(.gpNote).foregroundStyle(GP.muted)
                    }
                    Spacer()
                    EyeToggle(visible: amountsVisible) { amountsVisible.toggle() }
                }
                .padding(.bottom, 8)
                if let notice = model.notice { InlineNotice(notice.text, kind: notice.kind) }
                switch model.state {
                case .loading:
                    ForEach(0..<5, id: \.self) { _ in SkeletonBox(height: 68) }
                case .failed:
                    InlineNotice(L("No pudimos cargar tus movimientos."), kind: .failure)
                    GPButton(L("Reintentar"), kind: .secondary) { Task { await model.reload() } }
                case let .loaded(movements):
                    if movements.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(L("Aún no tienes movimientos.")).font(.gpBodyStrong).foregroundStyle(GP.text)
                            Text(L("Tus cobros y envíos aparecerán aquí.")).font(.gpNote).foregroundStyle(GP.muted)
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
                MovementDetailView(movement: movement, amountsVisible: amountsVisible, model: model)
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
                MaskedMoney(
                    formatted: withoutCurrency(formatMoney(movement.amount, movement.currency)),
                    visible: amountsVisible,
                    font: .gpBodyStrong,
                    large: false,
                    prefix: amountSign(movement),
                    alignment: .trailing
                )
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
    movement.kind == "envio" ? L("Enviado a %@", "\(movement.payerName)") : movement.payerName
}

private func amountSign(_ movement: Movement) -> String { movement.kind == "envio" ? "−" : "" }

private struct MovementDetailView: View {
    let movement: Movement
    let amountsVisible: Bool
    let model: MovementsViewModel
    @State private var confirmingHide = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let outgoing = movement.kind == "envio"
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                GPCard(padding: 24, spacing: 16) {
                    StatusPill(text: movementStatusLabel(movement.state, kind: movement.kind), kind: movementStatusKind(movement.state, kind: movement.kind))
                    MaskedMoney(formatted: formatMoney(movement.amount, movement.currency), visible: amountsVisible, prefix: amountSign(movement))
                    line(L("Concepto"), movement.concept)
                    line(outgoing ? L("Destinatario") : L("Pagador"), movement.payerName)
                    line(L("Creado"), formatMovementDate(movement.createdAt))
                    line(L("Actualizado"), formatMovementDate(movement.updatedAt))
                }
                InlineNotice(L("Actividad en GlassPay (Sandbox). Solo el servidor confirma un pago."), icon: GPIcon.lock)
                if let notice = model.notice { InlineNotice(notice.text, kind: notice.kind) }
                // Solo aparece en movimientos que no movieron dinero (expirados, rechazados, fallidos).
                if canHideMovement(movement.state, kind: movement.kind) {
                    GPButton(model.hiding ? L("Quitando…") : L("Eliminar de mi lista"), icon: GPIcon.trash, kind: .danger) {
                        confirmingHide = true
                    }
                    .disabled(model.hiding)
                    Text(L("Solo lo quita de tu lista. El registro se conserva y tu saldo no cambia."))
                        .font(.gpNote).foregroundStyle(GP.muted)
                }
            }
            .padding(20)
        }
        .gpScreen()
        .navigationTitle(outgoing ? L("Detalle del envío") : L("Detalle del cobro"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.notice = nil }
        .confirmationDialog(L("¿Eliminar de tu lista?"), isPresented: $confirmingHide, titleVisibility: .visible) {
            Button(L("Eliminar"), role: .destructive) {
                Task {
                    guard await model.hide(movement) else { return }
                    dismiss()
                    await model.reload()
                }
            }
            Button(L("Cancelar"), role: .cancel) {}
        } message: {
            Text(L("Dejarás de ver este movimiento. El registro se conserva en GlassPay y tu saldo no cambia."))
        }
    }

    private func line(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.gpNote).foregroundStyle(GP.muted)
            Text(value).font(.gpBody).foregroundStyle(GP.text)
        }
        .accessibilityElement(children: .combine)
    }
}
