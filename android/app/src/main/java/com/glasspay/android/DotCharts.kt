package com.glasspay.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Una serie de la gráfica temporal: un valor por periodo, en el mismo orden que las etiquetas. */
class DotSeries(val values: List<Double>, val color: Color)

/**
 * Gráfica temporal de puntos unidos por una curva suave (un solo eje, desde 0). La curva es
 * monótona por tramos, así que nunca pasa por debajo de 0 ni inventa picos entre dos puntos.
 * Tocar o deslizar elige un periodo; la guía vertical y los puntos elegidos se agrandan.
 */
@Composable
fun DotLineChart(
    labels: List<String>,
    series: List<DotSeries>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animate = animationsEnabled()
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall
    val haptics = LocalHapticFeedback.current
    val currentSelected by rememberUpdatedState(selected)
    val axisMax = niceAxisMax(series.flatMap { it.values }.maxOrNull() ?: 0.0)
    val ticks = listOf(0.0, axisMax / 2, axisMax)

    // Entrada: la curva se dibuja de izquierda a derecha y cada punto aparece al pasar la curva.
    val reveal = remember(labels, series.map { it.values }) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(reveal) { if (animate) reveal.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    // La selección se desliza entre periodos en lugar de saltar.
    val focus by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = if (animate) spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow) else snap(),
        label = "chartFocus",
    )

    fun indexAt(x: Float, width: Float, axisWidth: Float): Int {
        val slot = (width - axisWidth) / labels.size
        return floor((x - axisWidth) / slot).toInt().coerceIn(0, labels.lastIndex)
    }

    fun choose(index: Int) {
        if (index != currentSelected) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSelect(index)
        }
    }

    Canvas(
        modifier = modifier
            .pointerInput(labels) {
                detectTapGestures { offset -> choose(indexAt(offset.x, size.width.toFloat(), 40.dp.toPx())) }
            }
            .pointerInput(labels) {
                detectHorizontalDragGestures { change, _ ->
                    choose(indexAt(change.position.x, size.width.toFloat(), 40.dp.toPx()))
                }
            },
    ) {
        if (labels.isEmpty()) return@Canvas
        val axisWidth = 40.dp.toPx()
        val labelHeight = 24.dp.toPx()
        val top = 14.dp.toPx()
        val plotHeight = size.height - labelHeight - top
        val slot = (size.width - axisWidth) / labels.size
        fun x(index: Int) = axisWidth + slot * (index + 0.5f)
        fun y(value: Double) = top + plotHeight - (value / axisMax * plotHeight).toFloat()

        // Guías recesivas (hairline) y etiquetas del único eje.
        ticks.forEach { tick ->
            val lineY = y(tick)
            drawLine(GlassPayColors.Line.copy(alpha = if (tick == 0.0) 1f else 0.5f), Offset(axisWidth, lineY), Offset(size.width, lineY), 1.dp.toPx())
            val text = measurer.measure(axisLabel(tick), labelStyle.copy(color = GlassPayColors.MutedText))
            drawText(text, topLeft = Offset(axisWidth - 8.dp.toPx() - text.size.width, lineY - text.size.height / 2f))
        }

        // Guía vertical del periodo elegido.
        val focusX = axisWidth + slot * (focus + 0.5f)
        drawLine(GlassPayColors.Line, Offset(focusX, top - 6.dp.toPx()), Offset(focusX, top + plotHeight), 1.dp.toPx())

        val revealX = axisWidth + (size.width - axisWidth) * reveal.value
        series.forEach { item ->
            val points = item.values.mapIndexed { index, value -> Offset(x(index), y(value)) }
            clipRect(right = revealX) {
                drawPath(smoothPath(points), item.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        series.forEach { item ->
            item.values.forEachIndexed { index, value ->
                val center = Offset(x(index), y(value))
                // Cada punto crece cuando la curva lo alcanza.
                val appear = ((revealX - center.x) / (slot * 0.6f) + 1f).coerceIn(0f, 1f)
                val emphasis = (1f - abs(index - focus)).coerceIn(0f, 1f)
                val radius = (4.5f + 2f * emphasis).dp.toPx() * appear
                if (radius > 0.5f) drawDot(center, radius, item.color, glow = emphasis * appear)
            }
        }

        labels.forEachIndexed { index, label ->
            val active = index == selected
            val text = measurer.measure(
                label,
                labelStyle.copy(
                    color = if (active) GlassPayColors.Text else GlassPayColors.MutedText,
                    fontWeight = if (active) FontWeight.SemiBold else null,
                ),
            )
            drawText(text, topLeft = Offset(x(index) - text.size.width / 2f, top + plotHeight + 6.dp.toPx()))
        }
    }
}

/**
 * Pista de un gráfico de puntos (Cleveland): eje fino de 0 al máximo, un tallo tenue y el punto
 * en su valor. Todas las filas de una tarjeta comparten el máximo, así las distancias se comparan.
 */
@Composable
fun DotTrack(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    order: Int = 0,
    height: Dp = 18.dp,
) {
    val animate = animationsEnabled()
    val position = remember { Animatable(if (animate) 0f else fraction) }
    LaunchedEffect(fraction) {
        if (!animate) {
            position.snapTo(fraction)
        } else {
            // Solo la primera vez se escalona; los cambios posteriores se deslizan directo.
            val firstTime = position.value == 0f && fraction > 0f
            position.animateTo(
                fraction.coerceIn(0f, 1f),
                tween(durationMillis = 520, delayMillis = if (firstTime) order * 70 else 0, easing = FastOutSlowInEasing),
            )
        }
    }
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val radius = 5.dp.toPx()
        val start = radius + 2.dp.toPx()
        val end = size.width - radius - 2.dp.toPx()
        val cy = size.height / 2f
        drawLine(GlassPayColors.Line, Offset(start, cy), Offset(end, cy), 1.dp.toPx(), cap = StrokeCap.Round)
        val dotX = start + (end - start) * position.value
        if (dotX - start > 1f) {
            drawLine(color.copy(alpha = 0.4f), Offset(start, cy), Offset(dotX, cy), 2.dp.toPx(), cap = StrokeCap.Round)
        }
        drawDot(Offset(dotX, cy), radius, color, glow = 0.6f)
    }
}

/** Punto con sombra suave, halo opcional del color de la serie y aro de 2 dp del color de la tarjeta. */
private fun DrawScope.drawDot(center: Offset, radius: Float, color: Color, glow: Float = 0f) {
    if (glow > 0.01f) {
        val haloRadius = radius * 2.8f
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.28f * glow), Color.Transparent), center, haloRadius),
            radius = haloRadius,
            center = center,
        )
    }
    val shadowCenter = center + Offset(0f, 1.5.dp.toPx())
    val shadowRadius = radius + 4.dp.toPx()
    drawCircle(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), shadowCenter, shadowRadius),
        radius = shadowRadius,
        center = shadowCenter,
    )
    drawCircle(GlassPayColors.Card, radius = radius + 2.dp.toPx(), center = center)
    drawCircle(color, radius = radius, center = center)
}

/** Curva cúbica que pasa por todos los puntos con las pendientes de [monotoneSlopes]. */
private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path
    val slopes = monotoneSlopes(points.map { it.x }.toFloatArray(), points.map { it.y }.toFloatArray())
    for (i in 0 until points.lastIndex) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val dx = (p1.x - p0.x) / 3f
        path.cubicTo(p0.x + dx, p0.y + slopes[i] * dx, p1.x - dx, p1.y - slopes[i + 1] * dx, p1.x, p1.y)
    }
    return path
}

/**
 * Pendientes de Fritsch–Carlson para una interpolación cúbica monótona: entre dos puntos la curva
 * nunca sale del rango de sus valores (no hay rebotes por debajo de 0 ni picos falsos).
 */
internal fun monotoneSlopes(xs: FloatArray, ys: FloatArray): FloatArray {
    val n = xs.size
    val slopes = FloatArray(n)
    if (n < 2) return slopes
    val secants = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
    slopes[0] = secants[0]
    slopes[n - 1] = secants[n - 2]
    for (i in 1 until n - 1) {
        slopes[i] = if (secants[i - 1] * secants[i] <= 0f) 0f else (secants[i - 1] + secants[i]) / 2f
    }
    for (i in 0 until n - 1) {
        if (secants[i] == 0f) {
            slopes[i] = 0f
            slopes[i + 1] = 0f
            continue
        }
        val a = slopes[i] / secants[i]
        val b = slopes[i + 1] / secants[i]
        val length = a * a + b * b
        if (length > 9f) {
            val scale = 3f / sqrt(length)
            slopes[i] = scale * a * secants[i]
            slopes[i + 1] = scale * b * secants[i]
        }
    }
    return slopes
}
