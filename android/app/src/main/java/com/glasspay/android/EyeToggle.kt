package com.glasspay.android

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sqrt

/** Botón de 48 dp para mostrar u ocultar montos. Lectores de pantalla: acción + estado actual. */
@Composable
fun EyeToggle(visible: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = GlassPayColors.Raised,
        interactionSource = interaction,
        modifier = modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics {
                contentDescription = if (visible) "Ocultar montos" else "Mostrar montos"
                stateDescription = if (visible) "Montos visibles" else "Montos ocultos"
            },
    ) {
        Box(contentAlignment = Alignment.Center) { EyeGlyph(open = visible) }
    }
}

/** Duración compartida del ojo y de los montos: el párpado termina de cerrar justo cuando la cifra se oculta. */
private const val AMOUNT_TOGGLE_MILLIS = 260

/**
 * Ojo dibujado en Canvas que parpadea de verdad: al ocultar, el párpado superior baja hasta
 * juntarse con el inferior y tapa la pupila; cerrado queda como un ojo dormido (curva y pestañas).
 * Al mostrar, el párpado sube y descubre la pupila. Sin rebotes, al mismo ritmo que los montos.
 */
@Composable
fun EyeGlyph(open: Boolean, modifier: Modifier = Modifier, color: Color = GlassPayColors.Text) {
    val animate = animationsEnabled()
    val progress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(if (animate) AMOUNT_TOGGLE_MILLIS else 0, easing = FastOutSlowInEasing),
        label = "eyeOpen",
    )
    Canvas(modifier = modifier.size(24.dp)) {
        val stroke = 1.8.dp.toPx()
        val halfWidth = size.width * 0.42f
        val halfHeight = size.height * 0.27f
        val cx = size.width / 2f
        // Cerrado, el dibujo sube un poco para que curva + pestañas queden centradas.
        val cy = size.height / 2f - halfHeight * 0.45f * (1f - progress)
        val left = Offset(cx - halfWidth, cy)
        val right = Offset(cx + halfWidth, cy)
        val lowerControlY = cy + 2f * halfHeight
        // El párpado superior pasa de arriba (abierto) a coincidir con el inferior (cerrado).
        val upperControlY = lowerControlY - 4f * halfHeight * progress
        val eye = Path().apply {
            moveTo(left.x, left.y)
            quadraticTo(cx, upperControlY, right.x, right.y)
            quadraticTo(cx, lowerControlY, left.x, left.y)
            close()
        }
        // La pupila no se encoge: el párpado la va tapando porque solo se pinta dentro del ojo.
        if (progress > 0.02f) {
            clipPath(eye) {
                drawCircle(color, radius = size.width * 0.12f, center = Offset(cx, cy + halfHeight * 0.1f))
            }
        }
        drawPath(eye, color, style = Stroke(width = stroke, cap = StrokeCap.Round))

        // Pestañas: crecen hacia afuera del párpado inferior conforme el ojo se cierra.
        val lash = 3.2.dp.toPx() * (1f - progress)
        if (lash > 0.3f) {
            listOf(0.22f, 0.5f, 0.78f).forEach { t ->
                val u = 1f - t
                val point = Offset(
                    u * u * left.x + 2f * u * t * cx + t * t * right.x,
                    u * u * left.y + 2f * u * t * lowerControlY + t * t * right.y,
                )
                // Normal hacia afuera de la curva inferior (perpendicular a su tangente).
                val tangent = Offset(
                    2f * u * (cx - left.x) + 2f * t * (right.x - cx),
                    2f * u * (lowerControlY - left.y) + 2f * t * (right.y - lowerControlY),
                )
                val length = sqrt(tangent.x * tangent.x + tangent.y * tangent.y)
                val normal = Offset(-tangent.y / length, tangent.x / length)
                drawLine(color, point, point + normal * lash, strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * Monto que se puede ocultar sin saltos: las dos versiones (cifra y "••••") se dibujan una sobre
 * otra y el contenedor toma el ancho mayor, así la tarjeta no cambia de tamaño. El cambio es un
 * fundido cruzado con un desenfoque breve. Para el lector de pantalla solo existe la versión vigente.
 */
@Composable
fun MaskedMoney(
    formatted: String,
    visible: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    large: Boolean = true,
    color: Color = GlassPayColors.Text,
    prefix: String = "",
    align: Alignment = Alignment.CenterStart,
) {
    val animate = animationsEnabled()
    val shown by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (animate) AMOUNT_TOGGLE_MILLIS else 0, easing = FastOutSlowInEasing),
        label = "amountReveal",
    )
    // Solo cambia la cifra; la moneda ("MXN") se dibuja una vez y no se mueve.
    val currency = formatted.substringAfterLast(" ", "").takeIf { it.length == 3 && it.all(Char::isUpperCase) }.orEmpty()
    val amount = prefix + if (currency.isEmpty()) formatted else formatted.substringBeforeLast(" ")
    val masked = prefix + maskAmount(formatted, false).removeSuffix(" $currency")
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (visible) prefix + formatted else "Monto oculto"
        },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(if (large) 8.dp else 4.dp),
    ) {
        Box(contentAlignment = align) {
            AmountLayer(masked, style, color, alpha = 1f - shown)
            AmountLayer(amount, style, color, alpha = shown)
        }
        if (currency.isNotEmpty()) {
            if (large) {
                Text(
                    currency,
                    style = MaterialTheme.typography.titleMedium,
                    color = GlassPayColors.MutedText,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            } else {
                Text(currency, style = style, color = color, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AmountLayer(text: String, style: TextStyle, color: Color, alpha: Float) {
    // Desenfoque máximo a mitad del cambio; en reposo (0 o 1) no se aplica.
    val blurRadius = (1f - abs(alpha * 2f - 1f)) * 4f
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            .then(if (blurRadius > 0.1f) Modifier.blur(blurRadius.dp) else Modifier),
    )
}
