package com.glasspay.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun EyeToggle(visible: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val animate = animationsEnabled()
    val open by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (animate) 250 else 0),
        label = "eyeOpen",
    )
    IconButton(
        onClick = onToggle,
        modifier = modifier.semantics {
            contentDescription = if (visible) "Ocultar montos" else "Mostrar montos"
        },
    ) {
        Canvas(modifier = Modifier.size(28.dp)) {
            val color = Color.White
            val stroke = 2.dp.toPx()
            val cx = size.width / 2f
            val cy = size.height / 2f
            val halfWidth = size.width * 0.45f
            // Párpado: la almendra se aplana al cerrar.
            val halfHeight = (size.height * 0.28f * open).coerceAtLeast(1.5.dp.toPx())
            val path = Path().apply {
                moveTo(cx - halfWidth, cy)
                quadraticTo(cx, cy - 2f * halfHeight, cx + halfWidth, cy)
                quadraticTo(cx, cy + 2f * halfHeight, cx - halfWidth, cy)
                close()
            }
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
            if (open > 0.05f) {
                drawCircle(color, radius = size.width * 0.12f * open, center = Offset(cx, cy))
            }
            val slashAlpha = 1f - open
            if (slashAlpha > 0.05f) {
                drawLine(
                    color.copy(alpha = slashAlpha),
                    start = Offset(cx - halfWidth * 0.8f, cy + size.height * 0.3f),
                    end = Offset(cx + halfWidth * 0.8f, cy - size.height * 0.3f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
