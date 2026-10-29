import SwiftUI

struct ScheduledView: View {
    @State private var model: ScheduledViewModel

    init(context: AppContext) { _model = State(initialValue: ScheduledViewModel(context: context)) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                InlineNotice("Lo programado vive en el servidor de prueba: si se reinicia o se actualiza, se pierde.", kind: .pending)
                if let message = model.message { InlineNotice(message) }
                switch model.state {
                case .loading:
                    ForEach(0..<3, id: \.self) { _ in SkeletonBox(height: 110) }
                case .failed:
                    InlineNotice("No pudimos cargar lo programado.", kind: .failure)
                    GPButton("Reintentar", kind: .secondary) { Task { await model.reload() } }
                case let .loaded(items):
                    if items.isEmpty {
                        GPCard {
                            IconBadge(icon: GPIcon.clock, size: 56)
                            Text("No tienes nada programado").font(.gpBodyStrong).foregroundStyle(GP.text)
                            Text("Pídeselo al asistente: «cóbrale a Ana 300 por la renta mañana a las 6».").font(.gpNote).foregroundStyle(GP.muted)
                        }
                    }
                    ForEach(items) { card($0) }
                }
            }
            .padding(20)
        }
        .refreshable { await model.reload() }
        .gpScreen()
        .navigationTitle("Programados")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.reload() }
        .confirmationDialog(
            cancelTitle,
            isPresented: Binding(get: { model.pendingCancel != nil }, set: { if !$0 { model.pendingCancel = nil } }),
            titleVisibility: .visible
        ) {
            Button(model.pendingCancel?.kind == "reminder" ? "Cancelar recordatorio" : "Cancelar cobro", role: .destructive) {
                if let item = model.pendingCancel { model.cancel(item) }
                model.pendingCancel = nil
            }
            Button("Volver", role: .cancel) { model.pendingCancel = nil }
        }
    }

    private var cancelTitle: String {
        guard let item = model.pendingCancel else { return "" }
        return item.kind == "reminder"
            ? "¿Cancelar el recordatorio a \(item.contactName)?"
            : "¿Cancelar el cobro a \(item.contactName) por \(formatMoney(item.amount, item.currency))?"
    }

    private func card(_ item: ScheduledItem) -> some View {
        GPCard(spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(prefix(item) + item.contactName).font(.gpBodyStrong).foregroundStyle(GP.text)
                    if item.kind != "reminder" || item.amount != "0" {
                        Text(withoutCurrency(formatMoney(item.amount, item.currency))).font(.gpHeading).foregroundStyle(GP.text)
                    }
                    if !item.concept.isEmpty { Text(item.concept).font(.gpNote).foregroundStyle(GP.muted) }
                }
                Spacer()
                StatusPill(text: scheduledStateLabel(item.state, kind: item.kind), kind: scheduledStateKind(item.state))
            }
            Label(item.recurrence != nil
                  ? "\(item.runAtLabel) (\(item.occurrencesDone + 1) de \(item.occurrencesTotal))"
                  : item.runAtLabel, systemImage: GPIcon.clock)
                .font(.gpLabel).foregroundStyle(GP.muted)
            if let failure = scheduledFailureMessage(item.failure) { Text(failure).font(.gpNote).foregroundStyle(GP.error) }
            if model.busyId == item.scheduledId {
                Text("Procesando…").font(.gpNote).foregroundStyle(GP.muted)
            } else {
                if item.state == "esperando_confirmacion" {
                    GPButton(runTitle(item), icon: item.kind == "payment" ? "faceid" : nil) { model.run(item) }
                }
                if isScheduledActive(item.state) && item.state != "ejecutando" {
                    GPButton("Cancelar", kind: .ghost) { model.pendingCancel = item }
                }
            }
        }
    }

    private func prefix(_ item: ScheduledItem) -> String {
        switch item.kind {
        case "reminder": "Recordar a "
        case "payment": "Pagar a "
        default: "Cobrar a "
        }
    }

    private func runTitle(_ item: ScheduledItem) -> String {
        switch item.kind {
        case "payment": "Enviar con Face ID"
        case "reminder": "Enviar recordatorio ahora"
        default: "Crear el cobro ahora"
        }
    }
}
