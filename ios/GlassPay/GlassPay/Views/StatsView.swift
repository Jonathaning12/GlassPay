import SwiftUI

// Estadísticas (StatsScreen.kt) con Swift Charts: curva de puntos cobrado/enviado, un solo eje con
// guías recesivas, lectura del periodo seleccionado y descripción completa para VoiceOver.

struct StatsView: View {
    @State private var model: StatsViewModel

    init(context: AppContext) { _model = State(initialValue: StatsViewModel(context: context)) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker(L("Periodo"), selection: $model.period) {
                    ForEach(StatsPeriod.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                switch model.state {
                case .loading:
                    SkeletonBox(height: 120)
                    SkeletonBox(height: 260)
                case .failed:
                    InlineNotice(L("No pudimos cargar tus estadísticas."), kind: .failure)
                    GPButton(L("Reintentar"), kind: .secondary) { Task { await model.reload() } }
                case let .loaded(stats):
                    content(stats)
                }
                Text(L("Actividad en GlassPay (Sandbox). Solo cuenta cobros pagados y envíos completados; no es tu saldo bancario."))
                    .font(.gpNote).foregroundStyle(GP.muted)
            }
            .padding(20)
        }
        .gpScreen()
        .navigationTitle(L("Estadísticas"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: model.period) { await model.reload() }
    }

    @ViewBuilder
    private func content(_ stats: Stats) -> some View {
        GPCard(spacing: 8) {
            Overline(L("Balance del periodo"))
            Text(formatGroupedMoney(stats.netTotal, signed: true)).font(.gpAmount).foregroundStyle(GP.text)
            if let change = netChangePercent(current: stats.netTotal, previous: stats.previousNetTotal) {
                Text(verbatim: "\(change >= 0 ? "▲" : "▼") \(abs(change))% \(model.period.previousLabel)")
                    .font(.gpLabel).foregroundStyle(change >= 0 ? GP.success : GP.error)
            }
        }
        HStack(spacing: 12) {
            tile(L("Cobrado"), stats.paidTotal, L("%@ pagos", "\(stats.paidCount)"), GP.chartPaid)
            tile(L("Enviado"), stats.sentTotal, L("%@ envíos", "\(stats.sentCount)"), GP.chartSent)
        }
        if stats.paidTotal == 0 && stats.sentTotal == 0 {
            InlineNotice(L("Sin actividad en este periodo. Cuando cobres o envíes, aparecerá aquí."), icon: GPIcon.chart)
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
        return GPCard(spacing: 14) {
            HStack {
                Text(L("Por %@", "\(model.period.bucketNoun)")).font(.gpBodyStrong).foregroundStyle(GP.text)
                Spacer()
                legend(L("Cobrado"), GP.chartPaid)
                legend(L("Enviado"), GP.chartSent)
            }
            // Lectura del periodo seleccionado: arriba de la gráfica para que el dedo no la tape.
            if let selected {
                HStack(spacing: 12) {
                    Text(selected.label).font(.gpLabel).foregroundStyle(GP.text).frame(maxWidth: .infinity, alignment: .leading)
                    Circle().fill(GP.chartPaid).frame(width: 8, height: 8)
                    Text(formatGroupedMoney(selected.paidTotal)).font(.gpLabel).foregroundStyle(GP.text)
                    Circle().fill(GP.chartSent).frame(width: 8, height: 8)
                    Text(formatGroupedMoney(selected.sentTotal)).font(.gpLabel).foregroundStyle(GP.text)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(GP.raised, in: RoundedRectangle(cornerRadius: GP.radiusSmall, style: .continuous))
                .accessibilityElement(children: .combine)
            }
            DotLineChart(buckets: buckets, selected: $model.selectedBucket)
                .frame(height: 220)
                .accessibilityElement()
                .accessibilityLabel(L("Gráfica de cobrado y enviado por %@. ", "\(model.period.bucketNoun)") + buckets.map {
                    L("%@: cobrado %@, enviado %@", "\($0.label)", "\(formatGroupedMoney($0.paidTotal))", "\(formatGroupedMoney($0.sentTotal))")
                }.joined(separator: ". "))
            Text(L("Toca o desliza sobre la gráfica para ver cada %@.", "\(model.period.bucketNoun)")).font(.gpNote).foregroundStyle(GP.muted)
        }
    }

    private func legend(_ label: String, _ color: Color) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label).font(.gpNote).foregroundStyle(GP.muted)
        }
    }

    @ViewBuilder
    private func statusCard(_ stats: Stats) -> some View {
        let rows: [(String, Int, StatusKind, String)] = [
            (L("Pagados"), stats.paidByStatus, .success, GPIcon.check),
            (L("Pendientes"), stats.pendingByStatus, .pending, GPIcon.clock),
            (L("No completados"), stats.notCompletedByStatus, .failure, GPIcon.close),
        ]
        let maxCount = max(1, rows.map(\.1).max() ?? 0)
        if rows.contains(where: { $0.1 > 0 }) {
            GPCard(spacing: 14) {
                Text(L("Cobros por estado")).font(.gpBodyStrong).foregroundStyle(GP.text)
                ForEach(Array(rows.enumerated()), id: \.offset) { order, row in
                    VStack(spacing: 4) {
                        HStack(spacing: 10) {
                            Image(systemName: row.3).font(.system(size: 14, weight: .semibold)).foregroundStyle(GP.status(row.2))
                            Text(row.0).font(.gpLabel).foregroundStyle(GP.text)
                            Spacer()
                            Text("\(row.1)").font(.gpBodyStrong).foregroundStyle(GP.text)
                        }
                        .accessibilityElement(children: .combine)
                        DotTrack(fraction: Double(row.1) / Double(maxCount), color: GP.status(row.2), order: order)
                    }
                }
                Text(L("No completados = rechazados, expirados o fallidos.")).font(.gpNote).foregroundStyle(GP.muted)
            }
        }
    }

    private func topPayers(_ payers: [StatsPayer]) -> some View {
        let maxTotal = max(0.01, payers.map(\.total).max() ?? 0)
        return GPCard(spacing: 16) {
            Text(L("Quién te pagó más")).font(.gpBodyStrong).foregroundStyle(GP.text)
            ForEach(Array(payers.enumerated()), id: \.element.id) { order, payer in
                HStack(spacing: 12) {
                    AvatarView(name: payer.name, size: 36)
                    VStack(spacing: 6) {
                        HStack {
                            Text(payer.count == 1 ? L("%@ · 1 cobro", payer.name) : L("%@ · %@ cobros", payer.name, "\(payer.count)"))
                                .font(.gpLabel).foregroundStyle(GP.text).lineLimit(1)
                            Spacer()
                            Text(formatGroupedMoney(payer.total)).font(.gpLabel).foregroundStyle(GP.text)
                        }
                        DotTrack(fraction: payer.total / maxTotal, color: GP.chartPaid, order: order)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
    }
}
