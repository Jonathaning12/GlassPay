package com.glasspay.android

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class GpButtonKind { PRIMARY, SECONDARY, GHOST, DANGER }

/** Escala sutil al presionar (0.97, 120 ms): confirma el toque sin retrasar la acción. */
@Composable
private fun pressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && animationsEnabled()) 0.97f else 1f,
        animationSpec = tween(120),
        label = "pressScale",
    )
    return scale
}

/** Botón de 56 dp del diseño. Una sola acción PRIMARY por pantalla. */
@Composable
fun GpButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: GpButtonKind = GpButtonKind.PRIMARY,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val (container, content) = when (kind) {
        GpButtonKind.PRIMARY -> GlassPayColors.Accent to GlassPayColors.OnAccent
        GpButtonKind.SECONDARY -> GlassPayColors.Raised to GlassPayColors.Text
        GpButtonKind.GHOST -> Color.Transparent to GlassPayColors.Text
        GpButtonKind.DANGER -> GlassPayColors.ErrorSoft to GlassPayColors.Error
    }
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        border = if (kind == GpButtonKind.SECONDARY) BorderStroke(1.dp, GlassPayColors.Line) else null,
        interactionSource = interaction,
        modifier = modifier
            .heightIn(min = 56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (kind == GpButtonKind.GHOST) 12.dp else 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
            Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

/** Botón redondo de 48 dp con un ícono (volver, ojo, etc.). */
@Composable
fun CircleIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = GlassPayColors.Text,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = GlassPayColors.Raised,
        contentColor = tint,
        interactionSource = interaction,
        modifier = modifier.size(48.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = contentDescription, modifier = Modifier.size(24.dp))
        }
    }
}

/** Tarjeta: superficie con borde de 1 dp y esquinas de 24 dp. */
@Composable
fun GpCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(GlassPayColors.Card)
            .border(1.dp, GlassPayColors.Line, MaterialTheme.shapes.large)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Estado con punto + texto: nunca depende solo del color. */
@Composable
fun StatusPill(label: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val color = statusColor(kind)
    Row(
        modifier = modifier
            .background(statusSoftColor(kind), RoundedCornerShape(50))
            .padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/** Círculo con las iniciales del nombre visible. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, highlighted: Boolean = false) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    Box(
        modifier = modifier
            .size(size)
            .background(if (highlighted) GlassPayColors.AccentSoft else GlassPayColors.Raised, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlighted) GlassPayColors.Accent else GlassPayColors.Text,
        )
    }
}

/** Círculo con ícono (filas de movimientos, estados). */
@Composable
fun IconBadge(@DrawableRes icon: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = 44.dp, background: Color = GlassPayColors.Raised) {
    Box(modifier = modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = GlassPayColors.MutedText) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

/** Encabezado de pantalla secundaria: botón volver + título + acciones opcionales. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) CircleIconButton(R.drawable.ic_back, "Volver", onBack)
        Text(title, style = MaterialTheme.typography.headlineSmall, color = GlassPayColors.Text, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Aviso en línea (informativo, advertencia o error) con ícono. */
@Composable
fun InlineNotice(text: String, modifier: Modifier = Modifier, kind: StatusKind = StatusKind.NEUTRAL, @DrawableRes icon: Int? = null) {
    val color = if (kind == StatusKind.NEUTRAL) GlassPayColors.MutedText else statusColor(kind)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(statusSoftColor(kind), MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, color = if (kind == StatusKind.NEUTRAL) GlassPayColors.MutedText else color, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Colores de los campos de texto: borde de línea, acento al enfocar. */
@Composable
fun gpTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = GlassPayColors.Accent,
    unfocusedBorderColor = GlassPayColors.Line,
    focusedLabelColor = GlassPayColors.Accent,
    cursorColor = GlassPayColors.Accent,
)
