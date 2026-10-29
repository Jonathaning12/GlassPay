import Charts
import SwiftUI

// Estadísticas (StatsScreen.kt) con Swift Charts: barras agrupadas cobrado/enviado, un solo eje
// con guías recesivas, lectura del periodo seleccionado y descripción completa para VoiceOver.

struct StatsView: View {
    @State private var model: StatsViewModel

    init(context: AppContext) { _model = State(initialValue: StatsViewModel(context: context)) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("Periodo", selection: $model.period) {
                    ForEach(StatsPeriod.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                switch model.state {
                case .loading:
                    SkeletonBox(height: 120)
                    SkeletonBox(height: 260)
                case .failed:
                    InlineNotice("No pudimos cargar tus estadísticas.", kind: .failure)
                    GPButton("Reintentar", kind: .secondary) { Task { await model.reload() } }
                case let .loaded(stats):
                    content(stats)
                }
                Text("Actividad en GlassPay (Sandbox). Solo cuenta cobros pagados y envíos completados; no es tu saldo bancario.")
                    .font(.gpNote).foregroundStyle(GP.muted)
            }
            .padding(20)
        }
        .gpScreen()
        .navigationTitle("Estadísticas")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: model.period) { await model.reload() }
    }

    @ViewBuilder
    private func content(_ stats: Stats) -> some View {
        GPCard(spacing: 8) {
            Overline("Balance del periodo")
            Text(formatGroupedMoney(stats.netTotal, signed: true)).font(.gpAmount).foregroundStyle(GP.text)
            if let change = netChangePercent(current: stats.netTotal, previous: stats.previousNetTotal) {
                Text("\(change >= 0 ? "▲" : "▼") \(abs(change))% \(model.period.previousLabel)")
                    .font(.gpLabel).foregroundStyle(change >= 0 ? GP.success : GP.error)
            }
        }
        HStack(spacing: 12) {
            tile("Cobrado", stats.paidTotal, "\(stats.paidCount) pagos", GP.chartPaid)
            tile("Enviado", stats.sentTotal, "\(stats.sentCount) envíos", GP.chartSent)
        }
        if stats.paidTotal == 0 && stats.sentTotal == 0 {
            InlineNotice("Sin actividad en este periodo. Cuando cobres o envíes, aparecerá aquí.", icon: GPIcon.chart)
        } else {
            chart(stats)
        }
        statusCard(stats)
        if !stats.topPayers.isEmpty { topPayers(stats.topPayers) }
    }

    private func tile(_ label: String, _ total: Double, _ detail: String, _ color: Color) -> some View {
        GPCard(padding: 16, spacing: 4) {
            HStack(spacing: 6) {
                Circle().fill(color).frame(width: 8, height: 8)
                Text(label).font(.gpNote).foregroundStyle(GP.muted)
            }
            Text(formatGroupedMoney(total)).font(.gpHeading).foregroundStyle(GP.text).lineLimit(1).minimumScaleFactor(0.7)
            Text(detail).font(.gpNote).foregroundStyle(GP.muted)
        }
        .accessibilityElement(children: .combine)
    }

    private func chart(_ stats: Stats) -> some View {
        let buckets = stats.buckets
        let selected = buckets.indices.contains(model.selectedBucket) ? buckets[model.selectedBucket] : nil
        let axisMax = niceAxisMax(buckets.map { max($0.paidTotal, $0.sentTotal) }.max() ?? 0)
        return GPCard(spacing: 12) {
            HStack {
                Text("Por \(model.period.bucketNoun)").font(.gpBodyStrong).foregroundStyle(GP.text)
                Spacer()
                legend("Cobrado", GP.chartPaid)
                legend("Enviado", GP.chartSent)
            }
            if let selected {
                HStack(spacing: 16) {
                    Text(selected.label).font(.gpLabel).foregroundStyle(GP.text)
                    Text(formatGroupedMoney(selected.paidTotal)).font(.gpLabel).foregroundStyle(GP.chartPaid)
                    Text(formatGroupedMoney(selected.sentTotal)).font(.gpLabel).foregroundStyle(GP.chartSent)
                }
            }
            Chart {
                ForEach(Array(buckets.enumerated()), id: \.offset) { index, bucket in
                    BarMark(x: .value("Periodo", bucket.label), y: .value("Monto", bucket.paidTotal))
                        .foregroundStyle(GP.chartPaid)
                        .position(by: .value("Serie", "Cobrado"))
                        .cornerRadius(4)
                        .opacity(index == model.selectedBucket ? 1 : 0.55)
                    BarMark(x: .value("Periodo", bucket.label), y: .value("Monto", bucket.sentTotal))
                        .foregroundStyle(GP.chartSent)
                        .position(by: .value("Serie", "Enviado"))
                        .cornerRadius(4)
                        .opacity(index == model.selectedBucket ? 1 : 0.55)
                }
            }
            .chartYScale(domain: 0...axisMax)
            .chartYAxis {
                AxisMarks(values: [0, axisMax / 2, axisMax]) { value in
                    AxisGridLine().foregroundStyle(GP.line)
                    AxisValueLabel { Text(axisLabel(value.as(Double.self) ?? 0)).font(.gpNote).foregroundStyle(GP.muted) }
                }
            }
            .chartXAxis {
                AxisMarks { _ in AxisValueLabel().font(.gpNote).foregroundStyle(GP.muted) }
            }
            .chartLegend(.hidden)
            .chartOverlay { proxy in
                GeometryReader { _ in
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        .onTapGesture { location in
                            if let label: String = proxy.value(atX: location.x),
                               let index = buckets.firstIndex(where: { $0.label == label }) {
                                model.selectedBucket = index
                            }
                        }
                }
            }
            .frame(height: 220)
            .accessibilityElement()
            .accessibilityLabel("Gráfica de cobrado y enviado por \(model.period.bucketNoun). " + buckets.map {
                "\($0.label): cobrado \(formatGroupedMoney($0.paidTotal)), enviado \(formatGroupedMoney($0.sentTotal))"
            }.joined(separator: ". "))
            Text("Toca un \(model.period.bucketNoun) para ver el detalle.").font(.gpNote).foregroundStyle(GP.muted)
        }
    }

    private func legend(_ label: String, _ color: Color) -> some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 10, height: 10)
            Text(label).font(.gpNote).foregroundStyle(GP.muted)
        }
    }

    private func statusCard(_ stats: Stats) -> some View {
        let rows: [(String, Int, StatusKind)] = [
            ("Pagados", stats.paidByStatus, .success),
            ("Pendientes", stats.pendingByStatus, .pending),
            ("No completados", stats.notCompletedByStatus, .failure),
        ]
        let total = max(1, rows.map(\.1).reduce(0, +))
        return GPCard(spacing: 12) {
            Text("Cobros por estado").font(.gpBodyStrong).foregroundStyle(GP.text)
            GeometryReader { geometry in
                HStack(spacing: 2) {
                    ForEach(rows, id: \.0) { row in
                        if row.1 > 0 {
                            Rectangle().fill(GP.status(row.2)).frame(width: geometry.size.width * CGFloat(row.1) / CGFloat(total))
                        }
                    }
                }
                .clipShape(Capsule())
            }
            .frame(height: 10)
            .background(GP.raised, in: Capsule())
            .accessibilityHidden(true)
            ForEach(rows, id: \.0) { row in
                HStack {
                    Circle().fill(GP.status(row.2)).frame(width: 8, height: 8)
                    Text(row.0).font(.gpBody).foregroundStyle(GP.text)
                    Spacer()
                    Text("\(row.1)").font(.gpBodyStrong).foregroundStyle(GP.text)
                }
                .accessibilityElement(children: .combine)
            }
            Text("No completados = rechazados, expirados o fallidos.").font(.gpNote).foregroundStyle(GP.muted)
        }
    }

    private func topPayers(_ payers: [StatsPayer]) -> some View {
        GPCard(spacing: 12) {
            Text("Quién te pagó más").font(.gpBodyStrong).foregroundStyle(GP.text)
            ForEach(payers) { payer in
                HStack(spacing: 12) {
                    AvatarView(name: payer.name, size: 36)
                    VStack(alignment: .leading) {
                        Text(payer.name).font(.gpBodyStrong).foregroundStyle(GP.text)
                        Text(payer.count == 1 ? "1 pago" : "\(payer.count) pagos").font(.gpNote).foregroundStyle(GP.muted)
                    }
                    Spacer()
                    Text(formatGroupedMoney(payer.total)).font(.gpBodyStrong).foregroundStyle(GP.text)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }
}
