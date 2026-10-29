import SwiftUI

// Chat del asistente: la IA solo interpreta; cada propuesta se muestra como tarjeta y requiere confirmación.

struct AssistantView: View {
    @Bindable var model: AssistantViewModel
    let onOpenScheduled: () -> Void
    @FocusState private var inputFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        if model.chat.messages.isEmpty { intro }
                        ForEach(model.chat.messages) { message in
                            if message.fromUser {
                                userBubble(message.text)
                            } else {
                                assistantBubble(message)
                            }
                        }
                        if model.chat.sending { typing }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(20)
                }
                .onChange(of: model.chat.messages.count) { _, _ in withAnimation { proxy.scrollTo("bottom") } }
                .onChange(of: model.chat.sending) { _, _ in withAnimation { proxy.scrollTo("bottom") } }
            }
            suggestions
            composer
        }
        .gpScreen()
        .navigationTitle("Asistente")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(action: onOpenScheduled) { Image(systemName: GPIcon.clock) }.accessibilityLabel("Ver programados")
            }
        }
        .onDisappear { model.stopDictation() }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 10) {
            IconBadge(icon: GPIcon.assistant, size: 56)
            Text("¿En qué te ayudo?").font(.gpHeading).foregroundStyle(GP.text)
            Text("Pregúntame cuánto cobraste o enviaste y quién te debe, o pídeme programar un cobro a un contacto. Respondo con tu actividad en GlassPay (Sandbox).")
                .font(.gpBody).foregroundStyle(GP.muted)
            InlineNotice("La IA solo interpreta tu mensaje: nunca cobra ni envía dinero sin que tú lo confirmes.", icon: GPIcon.lock)
        }
    }

    private func userBubble(_ text: String) -> some View {
        HStack {
            Spacer(minLength: 48)
            Text(text).font(.gpBody).foregroundStyle(GP.onAccent)
                .padding(.horizontal, 16).padding(.vertical, 12)
                .background(GP.accent, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
    }

    private func assistantBubble(_ message: ChatMessage) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(message.text).font(.gpBody).foregroundStyle(message.isError ? GP.error : GP.text)
            if let summary = message.reply?.summary { summaryCard(summary) }
            if let debtors = message.reply?.debtors, !debtors.isEmpty { debtorsCard(debtors) }
            if let proposal = message.reply?.proposal { ProposalCard(model: model, proposal: proposal, onOpenScheduled: onOpenScheduled) }
            if let scheduled = message.reply?.scheduled, !scheduled.isEmpty {
                ForEach(scheduled) { scheduledLine($0) }
                GPButton("Ver programados", icon: GPIcon.clock, kind: .secondary, action: onOpenScheduled)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GP.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(GP.line))
        .padding(.trailing, 24)
    }

    private func summaryCard(_ summary: AssistantSummary) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Overline(assistantPeriodLabel(summary.period))
            HStack(spacing: 12) {
                figure("Cobrado", formatMoney(summary.paidTotal, summary.currency), "\(summary.paidCount) pagos")
                figure("Enviado", formatMoney(summary.sentTotal, summary.currency), "\(summary.sentCount) envíos")
            }
            figure("Balance", formatMoney(summary.netTotal, summary.currency), "cobrado − enviado")
        }
    }

    private func figure(_ label: String, _ value: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.gpNote).foregroundStyle(GP.muted)
            Text(withoutCurrency(value)).font(.gpHeading).foregroundStyle(GP.text)
            Text(detail).font(.gpNote).foregroundStyle(GP.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func debtorsCard(_ debtors: [AssistantDebtor]) -> some View {
        VStack(spacing: 10) {
            ForEach(debtors) { debtor in
                HStack(spacing: 12) {
                    AvatarView(name: debtor.name, size: 32)
                    VStack(alignment: .leading) {
                        Text(debtor.name).font(.gpBodyStrong).foregroundStyle(GP.text)
                        Text(debtor.count == 1 ? "1 cobro pendiente" : "\(debtor.count) cobros pendientes").font(.gpNote).foregroundStyle(GP.muted)
                    }
                    Spacer()
                    Text(withoutCurrency(formatMoney(debtor.total, "MXN"))).font(.gpBodyStrong).foregroundStyle(GP.text)
                }
            }
        }
    }

    private func scheduledLine(_ item: ScheduledItem) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(item.contactName) · \(withoutCurrency(formatMoney(item.amount, item.currency)))").font(.gpBodyStrong).foregroundStyle(GP.text)
                Text(item.runAtLabel).font(.gpNote).foregroundStyle(GP.muted)
            }
            Spacer()
            StatusPill(text: scheduledStateLabel(item.state, kind: item.kind), kind: scheduledStateKind(item.state))
        }
    }

    private var typing: some View {
        HStack(spacing: 10) {
            ProgressView().tint(GP.accent)
            Text("Pensando…").font(.gpLabel).foregroundStyle(GP.muted)
        }
        .padding(16)
        .background(GP.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var suggestions: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.chat.suggestions, id: \.self) { text in
                    Button { model.send(text) } label: {
                        Text(text).font(.gpLabel).foregroundStyle(GP.accent)
                            .padding(.horizontal, 14).padding(.vertical, 10)
                            .background(GP.accentSoft, in: Capsule())
                    }
                    .disabled(model.chat.sending)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
        }
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let message = model.voiceMessage { Text(message).font(.gpNote).foregroundStyle(GP.warning) }
            HStack(spacing: 10) {
                TextField(model.listening ? "Escuchando…" : "Escribe un mensaje", text: $model.input, axis: .vertical)
                    .lineLimit(1...4)
                    .focused($inputFocused)
                    .gpField()
                Button { model.listening ? model.stopDictation() : model.dictate() } label: {
                    Image(systemName: model.listening ? "mic.fill" : GPIcon.mic)
                        .foregroundStyle(model.listening ? GP.onAccent : GP.accent)
                        .frame(width: 48, height: 48)
                        .background(model.listening ? GP.accent : GP.card, in: Circle())
                }
                .accessibilityLabel(model.listening ? "Escuchando" : "Dictar mensaje")
                Button { model.send(model.input) } label: {
                    Image(systemName: "arrow.up").font(.system(size: 18, weight: .bold)).foregroundStyle(GP.onAccent)
                        .frame(width: 48, height: 48).background(GP.accent, in: Circle())
                }
                .disabled(!model.chat.canSend(model.input))
                .opacity(model.chat.canSend(model.input) ? 1 : 0.45)
                .accessibilityLabel("Enviar mensaje")
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
    }
}

private struct ProposalCard: View {
    let model: AssistantViewModel
    let proposal: AssistantProposal
    let onOpenScheduled: () -> Void

    var body: some View {
        let status = model.chat.proposalStatus(proposal.proposalId)
        let paying = proposal.action == "schedule_payment"
        let reminding = proposal.action == "reminder"
        let cancelling = proposal.action == "cancel_scheduled"
        let splitting = proposal.action == "split_bill"
        let scheduledForLater = proposal.runAtLabel != nil
        let auto = Binding(get: { model.autoExecute(for: proposal) }, set: { model.setAutoExecute($0, for: proposal) })

        VStack(alignment: .leading, spacing: 12) {
            Overline(title, color: GP.accent)
            if !splitting {
                Text(proposal.contactName).font(.gpBodyStrong).foregroundStyle(GP.text)
            }
            if let amount = proposal.amount { MoneyText(formatted: formatMoney(amount, proposal.currency), font: .gpAmountMedium) }
            if !proposal.concept.isEmpty { Text(proposal.concept).font(.gpBody).foregroundStyle(GP.muted) }
            if paying && proposal.balanceWarning {
                InlineNotice("Hoy tu saldo no alcanza para este pago. Se volverá a revisar a la hora; si no alcanza, no se envía.", kind: .pending)
            }
            if reminding {
                Text("Le llega como aviso en su sección «Por pagar». No crea ningún cobro.").font(.gpNote).foregroundStyle(GP.muted)
            }
            if splitting {
                ForEach(proposal.participants) { share in
                    HStack {
                        Text(share.contactName).font(.gpBody).foregroundStyle(GP.text)
                        Spacer()
                        Text(withoutCurrency(formatMoney(share.amount, proposal.currency))).font(.gpBodyStrong).foregroundStyle(GP.text)
                    }
                }
                if let mine = proposal.myShare {
                    Text("Tu parte: \(withoutCurrency(formatMoney(mine, proposal.currency))) (no se cobra)").font(.gpNote).foregroundStyle(GP.muted)
                }
            }
            if let series = recurrenceLabel(proposal.recurrence, total: proposal.occurrencesTotal) {
                Label(series, systemImage: "repeat").font(.gpLabel).foregroundStyle(GP.text)
            }
            Label(whenText(reminding: reminding), systemImage: GPIcon.clock).font(.gpLabel).foregroundStyle(GP.warning)

            switch status {
            case .pending:
                if scheduledForLater && !cancelling {
                    Toggle(isOn: auto) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(paying ? "Enviar automáticamente a la hora (bajo tu riesgo)"
                                 : reminding ? "Enviar automáticamente a la hora" : "Crear automáticamente a la hora")
                                .font(.gpLabel).foregroundStyle(GP.text)
                            Text(autoDetail(paying: paying, reminding: reminding, auto: auto.wrappedValue)).font(.gpNote).foregroundStyle(GP.muted)
                        }
                    }
                    .tint(GP.accent)
                }
                GPButton(confirmTitle(cancelling: cancelling, splitting: splitting, reminding: reminding, paying: paying, later: scheduledForLater),
                         icon: paying ? "faceid" : GPIcon.check,
                         kind: cancelling ? .danger : .primary) { model.confirm(proposal) }
                GPButton("Descartar", kind: .ghost) { model.discard(proposal) }
            case .confirming:
                WorkingRow(text: "Confirmando…")
            case .confirmed:
                StatusPill(text: cancelling ? "Cancelado" : scheduledForLater ? "Programado" : reminding ? "Enviado" : "Cobros creados", kind: .success)
                if scheduledForLater && !cancelling { GPButton("Ver programados", kind: .secondary, action: onOpenScheduled) }
            case .discarded:
                StatusPill(text: "Descartado", kind: .neutral)
            case .failed:
                StatusPill(text: "No se aplicó", kind: .failure)
            }
        }
        .padding(16)
        .background(GP.raised, in: RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous))
    }

    private var title: String {
        switch proposal.action {
        case "cancel_scheduled": "Cancelar programación"
        case "split_bill": "Dividir cuenta"
        case "recurring_charge": "Cobro recurrente"
        case "reminder": "Recordatorio"
        case "schedule_payment": "Pago programado"
        default: "Cobro programado"
        }
    }

    private func whenText(reminding: Bool) -> String {
        guard let label = proposal.runAtLabel else { return reminding ? "Se envía al confirmar" : "Se cobra al confirmar" }
        return proposal.recurrence != nil ? "Primero: \(label)" : label
    }

    private func autoDetail(paying: Bool, reminding: Bool, auto: Bool) -> String {
        if paying { return auto ? "Se enviará sin volver a pedirte Face ID." : "A la hora te pediré vista previa y Face ID para enviarlo." }
        if !auto { return "A la hora te pediré confirmarlo." }
        return reminding ? "Se envía solo a la hora." : "Se crea solo; tu contacto lo aprueba en PayPal."
    }

    private func confirmTitle(cancelling: Bool, splitting: Bool, reminding: Bool, paying: Bool, later: Bool) -> String {
        if cancelling { return "Sí, cancelar" }
        if splitting && !later { return "Crear \(proposal.participants.count) cobros" }
        if reminding && !later { return "Enviar recordatorio" }
        if paying { return "Confirmar con Face ID" }
        return "Confirmar"
    }
}
