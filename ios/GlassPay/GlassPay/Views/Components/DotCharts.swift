import Charts
import SwiftUI

// Gráficas de puntos (DotCharts.kt en Android): curva temporal de puntos y pistas tipo Cleveland.

/**
 Gráfica temporal de puntos unidos por una curva suave (un solo eje, desde 0). La interpolación es
 monótona, así que nunca pasa por debajo de 0 ni inventa picos entre dos puntos. Tocar o deslizar
 elige un periodo; la guía vertical marca el elegido y sus puntos se agrandan.
 */
struct DotLineChart: View {
    let buckets: [StatsBucket]
    @Binding var selected: Int
    @State private var reveal: CGFloat = 0
    @State private var touched: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private struct Series: Identifiable {
        let name: String
        let color: Color
        let value: (StatsBucket) -> Double
        var id: String { name }
    }

    // Enviado primero para que Cobrado quede encima cuando se cruzan.
    private let series = [
        Series(name: "Enviado", color: GP.chartSent, value: \.sentTotal),
        Series(name: "Cobrado", color: GP.chartPaid, value: \.paidTotal),
    ]

    var body: some View {
        let axisMax = niceAxisMax(buckets.map { max($0.paidTotal, $0.sentTotal) }.max() ?? 0)
        let selectedLabel = buckets.indices.contains(selected) ? buckets[selected].label : nil
        Chart {
            if let selectedLabel {
                RuleMark(x: .value("Periodo", selectedLabel))
                    .foregroundStyle(GP.line)
                    .lineStyle(StrokeStyle(lineWidth: 1))
            }
            ForEach(series) { item in
                ForEach(Array(buckets.enumerated()), id: \.offset) { index, bucket in
                    LineMark(
                        x: .value("Periodo", bucket.label),
                        y: .value("Monto", item.value(bucket)),
                        series: .value("Serie", item.name)
                    )
                    .foregroundStyle(item.color)
                    .interpolationMethod(.monotone)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))

                    PointMark(x: .value("Periodo", bucket.label), y: .value("Monto", item.value(bucket)))
                        .symbol { ChartDot(color: item.color, emphasized: index == selected) }
                }
            }
        }
        .chartYScale(domain: 0...axisMax)
        .chartYAxis {
            AxisMarks(position: .leading, values: [0, axisMax / 2, axisMax]) { value in
                AxisGridLine().foregroundStyle(GP.line.opacity(value.as(Double.self) == 0 ? 1 : 0.5))
                AxisValueLabel { Text(axisLabel(value.as(Double.self) ?? 0)).font(.gpNote).foregroundStyle(GP.muted) }
            }
        }
        .chartXAxis {
            AxisMarks { value in
                AxisValueLabel {
                    let label = value.as(String.self) ?? ""
                    Text(label)
                        .font(.gpNote.weight(label == selectedLabel ? .semibold : .regular))
                        .foregroundStyle(label == selectedLabel ? GP.text : GP.muted)
                }
            }
        }
        .chartLegend(.hidden)
        // Entrada: la gráfica se descubre de izquierda a derecha.
        .chartPlotStyle { plot in
            plot.mask(alignment: .leading) {
                GeometryReader { geometry in Rectangle().frame(width: geometry.size.width * reveal) }
            }
        }
        .chartXSelection(value: $touched)
        .onChange(of: touched) { _, label in
            // Al soltar el dedo la selección vuelve a nil: se conserva el último periodo elegido.
            if let label, let index = buckets.firstIndex(where: { $0.label == label }) { selected = index }
        }
        .sensoryFeedback(.selection, trigger: selected)
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85), value: selected)
        .onAppear {
            guard !reduceMotion else { return reveal = 1 }
            reveal = 0
            withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.65)) { reveal = 1 }
        }
    }
}

/// Punto con sombra suave, halo del color de la serie si está elegido y aro de 2 pt del color de la tarjeta.
private struct ChartDot: View {
    let color: Color
    let emphasized: Bool

    var body: some View {
        let radius: CGFloat = emphasized ? 6.5 : 4.5
        Circle()
            .fill(color)
            .frame(width: radius * 2, height: radius * 2)
            .padding(2)
            .background(GP.card, in: Circle())
            .shadow(color: .black.opacity(0.45), radius: 3, y: 1.5)
            .background {
                if emphasized {
                    Circle().fill(color.opacity(0.28)).frame(width: radius * 5.6, height: radius * 5.6).blur(radius: 4)
                }
            }
    }
}

/**
 Pista de un gráfico de puntos (Cleveland): eje fino de 0 al máximo, un tallo tenue y el punto en su
 valor. Todas las filas de una tarjeta comparten el máximo, así las distancias se comparan.
 */
struct DotTrack: View {
    let fraction: Double
    let color: Color
    var order = 0
    @State private var position: Double = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geometry in
            let radius: CGFloat = 5
            let start = radius + 2
            let length = max(0, geometry.size.width - 2 * start)
            let dotX = start + length * CGFloat(position)
            ZStack(alignment: .leading) {
                Capsule().fill(GP.line).frame(width: length, height: 1).offset(x: start)
                Capsule().fill(color.opacity(0.4)).frame(width: max(0, dotX - start), height: 2).offset(x: start)
                Circle()
                    .fill(color)
                    .frame(width: radius * 2, height: radius * 2)
                    .padding(2)
                    .background(GP.card, in: Circle())
                    .shadow(color: color.opacity(0.35), radius: 4)
                    .position(x: dotX, y: geometry.size.height / 2)
            }
            .frame(maxHeight: .infinity)
        }
        .frame(height: 18)
        .accessibilityHidden(true)
        .onAppear {
            // Solo la primera vez se escalona; los cambios posteriores se deslizan directo.
            guard !reduceMotion else { return position = clamped }
            withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.52).delay(Double(order) * 0.07)) { position = clamped }
        }
        .onChange(of: fraction) { _, _ in
            if reduceMotion { position = clamped } else { withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.52)) { position = clamped } }
        }
    }

    private var clamped: Double { min(1, max(0, fraction)) }
}
