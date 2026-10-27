package com.glasspay.android

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

enum class StatsPeriod(val apiValue: String, val label: String, val previousLabel: String, val bucketNoun: String) {
    WEEK("week", "Semana", "vs. semana pasada", "día"),
    MONTH("month", "Mes", "vs. mes pasado", "semana"),
    YEAR("year", "Año", "vs. año pasado", "mes"),
}

data class StatsBucket(val label: String, val paidTotal: Double, val sentTotal: Double)

data class StatsPayer(val name: String, val total: Double, val count: Int)

/** Estadísticas del periodo. Solo cuentan cobros pagados y envíos completados (lo calcula el servidor). */
data class Stats(
    val period: String,
    val currency: String,
    val paidTotal: Double,
    val paidCount: Int,
    val sentTotal: Double,
    val sentCount: Int,
    val netTotal: Double,
    val previousNetTotal: Double,
    val buckets: List<StatsBucket>,
    val paidByStatus: Int,
    val pendingByStatus: Int,
    val notCompletedByStatus: Int,
    val topPayers: List<StatsPayer>,
)

internal fun parseStats(body: String): Stats {
    val json = JSONObject(body)
    val buckets = json.getJSONArray("buckets").let { items ->
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            StatsBucket(item.getString("label"), item.getString("paidTotal").toDouble(), item.getString("sentTotal").toDouble())
        }
    }
    val payers = json.getJSONArray("topPayers").let { items ->
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            StatsPayer(item.getString("name"), item.getString("total").toDouble(), item.getInt("count"))
        }
    }
    val status = json.getJSONObject("byStatus")
    return Stats(
        period = json.getString("period"),
        currency = json.getString("currency"),
        paidTotal = json.getString("paidTotal").toDouble(),
        paidCount = json.getInt("paidCount"),
        sentTotal = json.getString("sentTotal").toDouble(),
        sentCount = json.getInt("sentCount"),
        netTotal = json.getString("netTotal").toDouble(),
        previousNetTotal = json.getString("previousNetTotal").toDouble(),
        buckets = buckets,
        paidByStatus = status.getInt("paid"),
        pendingByStatus = status.getInt("pending"),
        notCompletedByStatus = status.getInt("notCompleted"),
        topPayers = payers,
    )
}

/** "$3,450.00" con separador de miles (la tarjeta de saldo de Inicio no lo usa: no la cambiamos). */
fun formatGroupedMoney(value: Double, signed: Boolean = false): String {
    val amount = BigDecimal.valueOf(abs(value)).setScale(2, RoundingMode.HALF_UP).toPlainString()
    val (integer, decimals) = amount.split(".").let { it[0] to it[1] }
    val grouped = integer.reversed().chunked(3).joinToString(",").reversed()
    val sign = when {
        value < 0 -> "−"
        signed && value > 0 -> "+"
        else -> ""
    }
    return "$sign\$$grouped.$decimals"
}

/**
 * Variación porcentual del balance contra el periodo anterior. Nulo si el anterior era 0 o si los
 * signos difieren (un porcentaje ahí confunde más de lo que informa).
 */
fun netChangePercent(current: Double, previous: Double): Int? {
    if (previous == 0.0 || (current < 0) != (previous < 0)) return null
    return (((current - previous) / abs(previous)) * 100).roundToInt()
}

/** Tope "redondo" del eje (1, 2, 2.5, 5 × 10ⁿ) para que las guías caigan en cifras legibles. */
fun niceAxisMax(maxValue: Double): Double {
    if (maxValue <= 0) return 100.0
    val magnitude = 10.0.pow(kotlin.math.floor(log10(maxValue)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= maxValue }
    return step * magnitude
}

/** Etiqueta corta del eje: 0, 500, 1k, 2.5k. */
fun axisLabel(value: Double): String {
    if (value < 1000) return value.roundToInt().toString()
    val thousands = value / 1000
    return if (thousands == ceil(thousands)) "${thousands.toInt()}k" else "${String.format(Locale.US, "%.1f", thousands)}k"
}

/** Selección inicial: el último periodo con actividad; si no hay ninguno, el primero. */
fun defaultSelectedBucket(buckets: List<StatsBucket>): Int =
    buckets.indexOfLast { it.paidTotal > 0 || it.sentTotal > 0 }.coerceAtLeast(0)
