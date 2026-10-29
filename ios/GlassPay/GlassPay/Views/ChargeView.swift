import SwiftUI

// Pestaña Cobrar: cámara con visor mientras se busca el QR; con el pagador identificado, el flujo de
// dictado → vista previa → confirmación → espera del pago. Los botones salen de availableActions(state).

struct ChargeView: View {
    @Bindable var model: ChargeViewModel

    var body: some View {
        Group {
            if model.resolveState.isIdentified {
                identified
            } else {
                scanning
            }
        }
        .gpScreen()
        .task { await model.onAppear() }
    }

    // MARK: - Escaneo

    private var scanning: some View {
        ZStack {
            if model.cameraGranted {
                QRScannerView(onToken: model.resolveToken).ignoresSafeArea()
                GP.background.opacity(0.35).ignoresSafeArea()
                ScanFrame().stroke(GP.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round)).frame(width: 264, height: 264)
                    .accessibilityHidden(true)
            }
            VStack {
                if model.cameraGranted, model.resolveState == .scanning {
                    HStack(spacing: 10) {
                        Image(systemName: GPIcon.qr).foregroundStyle(GP.accent)
                        Text("Apunta al QR de tu amigo").font(.gpBodyStrong).foregroundStyle(GP.text)
                    }
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .background(GP.background.opacity(0.72), in: Capsule())
                    .padding(.top, 24)
                }
                Spacer()
                scanSheet.padding(20)
                if !model.cameraGranted { Spacer() }
            }
        }
    }

    private var scanSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch model.resolveState {
            case let .resolving(_, _, waking):
                WorkingRow(text: waking ? "Despertando el servidor…" : "Resolviendo QR…")
            case let .retryableError(_, _, message):
                statusLine(message, failed: true)
                GPButton("Reintentar") { model.retryResolve() }
            case let .failed(message):
                statusLine(message, failed: true)
                GPButton("Escanear otro QR", icon: GPIcon.charge, kind: .secondary) { model.resetScan() }
            default:
                statusLine("Buscando un QR de GlassPay…", failed: false)
                Text("Solo se leen códigos de GlassPay. Los demás se ignoran sin enviar nada.").font(.gpNote).foregroundStyle(GP.muted)
            }
            if let error = model.cameraError { Text(error).font(.gpLabel).foregroundStyle(GP.error) }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GP.card.opacity(0.94), in: RoundedRectangle(cornerRadius: GP.radiusLarge, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: GP.radiusLarge, style: .continuous).stroke(GP.line))
    }

    private func statusLine(_ text: String, failed: Bool) -> some View {
        HStack(spacing: 10) {
            Circle().fill(failed ? GP.error : GP.accent).frame(width: 10, height: 10)
            Text(text).font(.gpBodyStrong).foregroundStyle(failed ? GP.error : GP.text)
        }
    }

    // MARK: - Pagador identificado

    private var identified: some View {
        let state = model.chargeState
        let actions = model.actions
        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if case let .identified(name, _, _, contactId) = model.resolveState {
                    if !isWaitingForPayment(state) { payerCard(name: name, contactId: contactId, state: state) }
                    if let message = model.contactMessage { InlineNotice(message, kind: .pending) }
                    stateContent(state, payerName: name)
                }
                if showsRecognizedText(state), let text = model.lastRecognizedText { echo("Entendí", displayText(text, maxLength: 120)) }
                if showsAccumulatedText(state), let text = model.accumulatedText { echo("Frase acumulada", displayText(text, maxLength: 240)) }

                if actions.contains(.confirm) { GPButton("Confirmar cobro", icon: GPIcon.check) { model.confirm() }.disabled(model.locked) }
                if actions.contains(.dictate) { GPButton("Dictar cobro", icon: GPIcon.mic) { model.startChargeInput() } }
                if actions.contains(.answer) { GPButton("Responder", icon: GPIcon.mic) { model.startChargeInput() } }
                if actions.contains(.retry) { GPButton("Intentar de nuevo") { model.retry() } }
                if actions.contains(.cancel) { GPButton("Cancelar", kind: .secondary) { model.cancel() } }
                if actions.contains(.stopWaiting) { GPButton("Dejar de esperar", kind: .secondary) { model.stopWaiting() } }
                if actions.contains(.scanAnother) && !state.isCreated {
                    GPButton("Escanear otro QR", icon: GPIcon.charge, kind: .secondary) { model.scanAnotherQr() }
                }
                if actions.contains(.back) || actions.contains(.startOver) {
                    HStack {
                        if actions.contains(.back) { GPButton("Volver", icon: "chevron.left", kind: .ghost) { model.back() } }
                        Spacer()
                        if actions.contains(.startOver) { GPButton("Empezar de nuevo", kind: .ghost) { model.startOver() } }
                    }
                }
                if showsTextInput(state) { textInput }
            }
            .padding(20)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private func isWaitingForPayment(_ state: ChargeState) -> Bool {
        if case let .created(_, _, payment) = state { return !isTerminal(payment) }
        return false
    }

    private func payerCard(name: String, contactId: String?, state: ChargeState) -> some View {
        GPCard {
            HStack(spacing: 12) {
                AvatarView(name: name, size: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Overline("Cobrar a")
                    Text(name).font(.gpHeading).foregroundStyle(GP.text)
                }
            }
            if contactId != nil {
                StatusPill(text: "En tus contactos", kind: .success)
            } else if model.savingContact {
                ProgressView().tint(GP.accent)
            } else if canSaveContact(state) {
                GPButton("Guardar contacto", icon: GPIcon.plus, kind: .secondary) { model.saveContact() }
            }
        }
    }

    private func canSaveContact(_ state: ChargeState) -> Bool {
        switch state {
        case .ready, .error: return true
        case let .created(_, _, payment): return isTerminal(payment)
        default: return false
        }
    }

    @ViewBuilder
    private func stateContent(_ state: ChargeState, payerName: String) -> some View {
        switch state {
        case let .ready(message):
            if let message { InlineNotice(message, kind: .failure) }
        case .listening:
            HStack(spacing: 14) {
                MicIndicator()
                Text(model.voiceStatus ?? "Preparando micrófono…").font(.gpBodyStrong).foregroundStyle(GP.text)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(GP.accentSoft, in: RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous))
        case .interpreting:
            WorkingRow(text: "Interpretando…")
        case let .clarification(question):
            GPCard(spacing: 8) {
                Overline("Necesito un dato más", color: GP.warning)
                Text(question).font(.gpBodyStrong).foregroundStyle(GP.text)
            }
        case let .preview(proposal, remaining, message):
            ReceiptCard(proposal: proposal, remaining: remaining, message: message, payerName: payerName)
        case .confirming:
            WorkingRow(text: "Confirmando…")
        case let .created(_, _, payment):
            PaymentProgressView(
                payment: payment,
                confirmed: model.confirmedCharge,
                onNewCharge: model.scanAnotherQr,
                onOpenMovements: model.openMovements
            )
        case let .error(message, _):
            InlineNotice(message, kind: .failure)
        }
    }

    private func echo(_ label: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.gpNote).foregroundStyle(GP.muted)
            Text("«\(text)»").font(.gpBody).foregroundStyle(GP.text)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GP.raised, in: RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous))
    }

    private var textInput: some View {
        VStack(spacing: 12) {
            Divider().overlay(GP.line)
            TextField("Cóbrale 350 pesos por la cena", text: $model.textInput, axis: .vertical)
                .lineLimit(1...3)
                .gpField()
                .accessibilityLabel("O escribe la frase del cobro")
            GPButton("Interpretar texto", icon: GPIcon.keyboard, kind: .secondary) { model.interpretTextInput() }
                .disabled(model.textInput.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }
}

/// Recibo de la vista previa: monto, concepto, pagador y cuenta regresiva de la propuesta.
private struct ReceiptCard: View {
    let proposal: ChargeProposal
    let remaining: Int
    let message: String?
    let payerName: String
    /// Vida de referencia (2 min): solo dimensiona la barra; el servidor manda.
    private let referenceSeconds = 120.0

    var body: some View {
        let expired = remaining <= 0
        let timeColor = expired ? GP.error : GP.warning
        VStack(alignment: .leading, spacing: 16) {
            GPCard(padding: 24, spacing: 18) {
                Overline("Revisa el cobro")
                MoneyText(formatted: formatMoney(proposal.amount, proposal.currency))
                field("Concepto", proposal.concept)
                field("Pagador", payerName)
                Divider().overlay(GP.line)
                HStack(spacing: 8) {
                    Image(systemName: GPIcon.clock)
                    Text(expired ? "La propuesta venció" : "Vence en \(formatCountdown(remaining))").font(.gpLabel)
                }
                .foregroundStyle(timeColor)
                ProgressView(value: min(1, max(0, Double(remaining) / referenceSeconds)))
                    .tint(timeColor)
                    .accessibilityHidden(true)
                if let message { Text(message).font(.gpLabel).foregroundStyle(GP.error) }
            }
            if !expired {
                InlineNotice("Di «confirmar» o toca el botón. «Cancelar» lo descarta.", icon: GPIcon.mic)
            }
        }
    }

    private func field(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.gpNote).foregroundStyle(GP.muted)
            Text(value).font(.gpBody).foregroundStyle(GP.text)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct MicIndicator: View {
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if !reduceMotion {
                Circle().fill(GP.accent).frame(width: 40, height: 40)
                    .scaleEffect(pulse ? 1.5 : 1).opacity(pulse ? 0 : 0.5)
                    .onAppear { withAnimation(.easeOut(duration: 1.2).repeatForever(autoreverses: false)) { pulse = true } }
            }
            Circle().fill(GP.accent).frame(width: 40, height: 40)
            Image(systemName: "mic.fill").foregroundStyle(GP.onAccent)
        }
        .frame(width: 48, height: 48)
        .accessibilityLabel("Micrófono escuchando")
    }
}
