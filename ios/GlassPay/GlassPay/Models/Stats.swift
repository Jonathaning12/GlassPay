import Foundation

// Traducción de Stats.kt. Solo cuentan cobros pagados y envíos completados (lo calcula el servidor).

enum StatsPeriod: String, CaseIterable, Identifiable {
    case week, month, year
    var id: String { rawValue }

    var label: String {
        switch self { case .week: "Semana"; case .month: "Mes"; case .year: "Año" }
    }
    var previousLabel: String {
        switch self { case .week: "vs. semana pasada"; case .month: "vs. mes pasado"; case .year: "vs. año pasado" }
    }
    var bucketNoun: String {
        switch self { case .week: "día"; case .month: "semana"; case .year: "mes" }
    }
}

struct StatsBucket: Equatable, Identifiable {
    let label: String
    let paidTotal: Double
    let sentTotal: Double
    var id: String { label }
}

struct StatsPayer: Equatable, Identifiable {
    let name: String
    let total: Double
    let count: Int
    var id: String { name }
}

struct Stats: Decodable, Equatable {
    let period: String
    let currency: String
    let paidTotal: Double
    let paidCount: Int
    let sentTotal: Double
    let sentCount: Int
    let netTotal: Double
    let previousNetTotal: Double
    let buckets: [StatsBucket]
    let paidByStatus: Int
    let pendingByStatus: Int
    let notCompletedByStatus: Int
    let topPayers: [StatsPayer]

    private enum CodingKeys: String, CodingKey {
        case period, currency, paidTotal, paidCount, sentTotal, sentCount, netTotal, previousNetTotal
        case buckets, byStatus, topPayers
    }

    private struct RawBucket: Decodable { let label: String; let paidTotal: FlexibleString; let sentTotal: FlexibleString }
    private struct RawPayer: Decodable { let name: String; let total: FlexibleString; let count: Int }
    private struct RawStatus: Decodable { let paid: Int; let pending: Int; let notCompleted: Int }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func number(_ key: CodingKeys) throws -> Double {
            guard let value = Double(try c.decode(FlexibleString.self, forKey: key).value) else {
                throw ApiFailureType.invalidResponse
            }
            return value
        }
        period = try c.decode(String.self, forKey: .period)
        currency = try c.decode(String.self, forKey: .currency)
        paidTotal = try number(.paidTotal)
        paidCount = try c.decode(Int.self, forKey: .paidCount)
        sentTotal = try number(.sentTotal)
        sentCount = try c.decode(Int.self, forKey: .sentCount)
        netTotal = try number(.netTotal)
        previousNetTotal = try number(.previousNetTotal)
        buckets = try c.decode([RawBucket].self, forKey: .buckets).map {
            StatsBucket(label: $0.label, paidTotal: Double($0.paidTotal.value) ?? 0, sentTotal: Double($0.sentTotal.value) ?? 0)
        }
        let status = try c.decode(RawStatus.self, forKey: .byStatus)
        paidByStatus = status.paid
        pendingByStatus = status.pending
        notCompletedByStatus = status.notCompleted
        topPayers = try c.decode([RawPayer].self, forKey: .topPayers).map {
            StatsPayer(name: $0.name, total: Double($0.total.value) ?? 0, count: $0.count)
        }
    }
}

/// "$3,450.00" con separador de miles; con `signed`, "+" para positivos. Negativos con "−".
func formatGroupedMoney(_ value: Double, signed: Bool = false) -> String {
    let formatter = NumberFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 2
    formatter.groupingSeparator = ","
    formatter.groupingSize = 3
    formatter.usesGroupingSeparator = true
    formatter.roundingMode = .halfUp
    let amount = formatter.string(from: NSNumber(value: abs(value))) ?? "0.00"
    let sign = value < 0 ? "−" : (signed && value > 0 ? "+" : "")
    return "\(sign)$\(amount)"
}

/// Nulo si el periodo anterior era 0 o si los signos difieren (ahí un porcentaje confunde).
func netChangePercent(current: Double, previous: Double) -> Int? {
    if previous == 0 || (current < 0) != (previous < 0) { return nil }
    return Int(((current - previous) / abs(previous) * 100).rounded())
}

/// Tope "redondo" del eje (1, 2, 2.5, 5 × 10ⁿ) para que las guías caigan en cifras legibles.
func niceAxisMax(_ maxValue: Double) -> Double {
    if maxValue <= 0 { return 100 }
    let magnitude = pow(10, floor(log10(maxValue)))
    let step = [1.0, 2.0, 2.5, 5.0, 10.0].first { $0 * magnitude >= maxValue } ?? 10
    return step * magnitude
}

/// Etiqueta corta del eje: 0, 500, 1k, 2.5k.
func axisLabel(_ value: Double) -> String {
    if value < 1000 { return String(Int(value.rounded())) }
    let thousands = value / 1000
    return thousands == thousands.rounded(.up) ? "\(Int(thousands))k" : String(format: "%.1fk", thousands)
}

/// Selección inicial: el último periodo con actividad; si no hay ninguno, el primero.
func defaultSelectedBucket(_ buckets: [StatsBucket]) -> Int {
    buckets.lastIndex { $0.paidTotal > 0 || $0.sentTotal > 0 } ?? 0
}
