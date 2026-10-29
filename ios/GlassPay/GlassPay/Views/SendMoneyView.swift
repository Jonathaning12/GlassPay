import SwiftUI

// Enviar dinero: contacto → monto y nota → vista previa con cuenta regresiva → Face ID / código → resultado.

struct SendMoneyView: View {
    @State private var model: SendMoneyViewModel
    let locked: Bool
    let onOpenMovements: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    init(context: AppContext, initialContact: Contact?, locked: Bool, onOpenMovements: @escaping () -> Void) {
        _model = State(initialValue: SendMoneyViewModel(context: context, initialContact: initialContact))
        self.locked = locked
        self.onOpenMovements = onOpenMovements
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                switch model.step {
                case .pick: pick
                case .amount: amount
                case .preview: preview
                case .result: ResultView(model: model, onDone: { dismiss() }, onOpenMovements: onOpenMovements)
                }
            }
            .padding(20)
        }
        .scrollDismissesKeyboard(.interactively)
        .gpScreen()
        .navigationTitle("Enviar dinero")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(model.step == .result)
        .onChange(of: locked, initial: true) { _, value in model.locked = value }
        .task { await model.loadContacts() }
        .task(id: "\(model.result?.state ?? "")|\(locked)|\(scenePhase == .active)") {
            if scenePhase == .active { await model.pollResult() }
        }
    }

    @ViewBuilder
    private var pick: some View {
        Overline("¿A quién le envías?")
        switch model.contacts {
        case .loading: ForEach(0..<3, id: \.self) { _ in SkeletonBox(height: 72) }
        case .failed: InlineNotice("No pudimos cargar tus contactos.", kind: .failure)
        case let .loaded(contacts):
            if contacts.isEmpty { InlineNotice("Aún no tienes contactos. Agrégalos desde Contactos.", icon: GPIcon.contacts) }
            ForEach(contacts) { contact in
                Button { model.pick(contact) } label: {
                    HStack(spacing: 12) {
                        AvatarView(name: contact.displayName)
                        Text(contact.displayName).font(.gpBodyStrong).foregroundStyle(GP.text)
                        Spacer()
                        Image(systemName: GPIcon.chevron).foregroundStyle(GP.muted)
                    }
                    .padding(16)
                    .background(GP.card, in: RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous).stroke(GP.line))
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private var amount: some View {
        HStack(spacing: 12) {
            AvatarView(name: model.contact?.displayName ?? "", size: 48)
            VStack(alignment: .leading) {
                Overline("Enviar a")
                Text(model.contact?.displayName ?? "").font(.gpHeading).foregroundStyle(GP.text)
            }
        }
        HStack(spacing: 6) {
            Text("$").font(.gpTitle).foregroundStyle(GP.muted)
            TextField("Monto (MXN)", text: $model.amountText).keyboardType(.decimalPad).font(.gpTitle)
        }
        .gpField()
        .disabled(model.busy)
        TextField("Nota (opcional)", text: $model.note).gpField().disabled(model.busy)
        if let message = model.message { InlineNotice(message, kind: .failure) }
        GPButton(model.busy ? "Revisando…" : "Revisar") { model.requestPreview() }
            .disabled(model.amountText.trimmingCharacters(in: .whitespaces).isEmpty || model.busy)
    }

    @ViewBuilder
    private var preview: some View {
        if let proposal = model.preview {
            let expired = model.remaining <= 0
            let timeColor = expired ? GP.error : GP.warning
            GPCard(padding: 24, spacing: 16) {
                Overline("Revisa el envío")
                MoneyText(formatted: formatMoney(proposal.amount, proposal.currency))
                field("Destinatario", proposal.recipientName)
                if let note = proposal.note { field("Nota", note) }
                Divider().overlay(GP.line)
                HStack(spacing: 8) {
                    Image(systemName: GPIcon.clock)
                    Text(expired ? "La vista previa venció. Revísala de nuevo." : "Vence en \(model.remaining) s").font(.gpLabel)
                }
                .foregroundStyle(timeColor)
            }
            InlineNotice("Para enviar te pediremos Face ID o el código del teléfono.", icon: GPIcon.lock)
            if let message = model.message { InlineNotice(message, kind: .failure) }
            GPButton("Confirmar envío", icon: "faceid") { model.confirmWithAuth() }.disabled(!model.canConfirm)
            if expired { GPButton("Revisar de nuevo", kind: .secondary) { model.reviewAgain() } }
            if model.confirmBlocked { GPButton("Ver en Movimientos", kind: .secondary, action: onOpenMovements) }
        }
    }

    private func field(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.gpNote).foregroundStyle(GP.muted)
            Text(value).font(.gpBody).foregroundStyle(GP.text)
        }
    }
}

private struct ResultView: View {
    let model: SendMoneyViewModel
    let onDone: () -> Void
    let onOpenMovements: () -> Void
    @State private var entered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let outcome = model.result {
            VStack(spacing: 12) {
                switch outcome.state {
                case "completada":
                    ZStack {
                        Circle().fill(GP.successSoft).frame(width: 156, height: 156)
                        Circle().fill(GP.success).frame(width: 104, height: 104)
                        Image(systemName: GPIcon.check).font(.system(size: 48, weight: .bold)).foregroundStyle(GP.background)
                    }
                    .scaleEffect(entered || reduceMotion ? 1 : 0.6)
                    .onAppear { withAnimation(.spring(response: 0.5, dampingFraction: 0.6)) { entered = true } }
                    .accessibilityLabel("Envío completado")
                    Text("¡Enviado!").font(.gpTitle).foregroundStyle(GP.success)
                    MoneyText(formatted: formatMoney(outcome.amount, outcome.currency))
                    Text("a \(outcome.recipientName)").font(.gpBody).foregroundStyle(GP.muted)
                case "fallida":
                    IconBadge(icon: GPIcon.close, tint: GP.error, size: 88, background: GP.errorSoft)
                    Text("El envío no se pudo completar. Tu saldo no se descontó.")
                        .font(.gpBodyStrong).foregroundStyle(GP.error).multilineTextAlignment(.center)
                default:
                    if model.pollGaveUp || reduceMotion {
                        IconBadge(icon: GPIcon.clock, tint: GP.warning, size: 88, background: GP.warningSoft)
                    } else {
                        ProgressView().controlSize(.large).tint(GP.accent).accessibilityLabel("Enviando")
                    }
                    Text("Enviando a \(outcome.recipientName)…").font(.gpHeading).foregroundStyle(GP.text)
                    MoneyText(formatted: formatMoney(outcome.amount, outcome.currency), font: .gpAmountMedium)
                    if model.pollGaveUp { InlineNotice("Tu envío sigue en proceso. Revísalo en Movimientos.", kind: .pending) }
                }
                GPButton("Listo", action: onDone).padding(.top, 16)
                GPButton("Ver en Movimientos", kind: .secondary, action: onOpenMovements)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 24)
        }
    }
}
