package com.glasspay.android

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tokens del tema oscuro; mismos nombres que la colección "Color" del archivo de Figma. */
object GlassPayColors {
    val Background = Color(0xFF0A0D14) // bg/base
    val Card = Color(0xFF131826) // bg/surface
    val Raised = Color(0xFF1B2233) // bg/raised
    val Line = Color(0xFF283044) // line/default
    val Text = Color(0xFFF5F7FB) // text/primary
    val MutedText = Color(0xFFA3ADC2) // text/muted
    val OnAccent = Color(0xFF00151C) // text/on-accent
    val Accent = Color(0xFF7FE7FF) // accent/default
    val AccentSoft = Color(0xFF12303A) // accent/soft
    val Success = Color(0xFF63E6A0)
    val SuccessSoft = Color(0xFF10301F)
    val Warning = Color(0xFFFFC56B)
    val WarningSoft = Color(0xFF33260F)
    val Error = Color(0xFFFF8A80)
    val ErrorSoft = Color(0xFF3A1614)
    val QrPaper = Color(0xFFFFFFFF)
    // Series de gráficas (chart/cobrado y chart/enviado de Figma), validadas para daltonismo sobre Card.
    val ChartPaid = Color(0xFF14A3C4)
    val ChartSent = Color(0xFF9878F0)
}

fun statusColor(kind: StatusKind): Color {
    return when (kind) {
        StatusKind.SUCCESS -> GlassPayColors.Success
        StatusKind.PENDING -> GlassPayColors.Warning
        StatusKind.FAILURE -> GlassPayColors.Error
        StatusKind.NEUTRAL -> GlassPayColors.MutedText
    }
}

fun statusSoftColor(kind: StatusKind): Color {
    return when (kind) {
        StatusKind.SUCCESS -> GlassPayColors.SuccessSoft
        StatusKind.PENDING -> GlassPayColors.WarningSoft
        StatusKind.FAILURE -> GlassPayColors.ErrorSoft
        StatusKind.NEUTRAL -> GlassPayColors.Raised
    }
}

private val GlassPayColorScheme = darkColorScheme(
    primary = GlassPayColors.Accent,
    onPrimary = GlassPayColors.OnAccent,
    primaryContainer = GlassPayColors.AccentSoft,
    onPrimaryContainer = GlassPayColors.Accent,
    secondaryContainer = GlassPayColors.AccentSoft,
    onSecondaryContainer = GlassPayColors.Accent,
    background = GlassPayColors.Background,
    onBackground = GlassPayColors.Text,
    surface = GlassPayColors.Card,
    onSurface = GlassPayColors.Text,
    surfaceVariant = GlassPayColors.Raised,
    onSurfaceVariant = GlassPayColors.MutedText,
    surfaceContainerLowest = GlassPayColors.Background,
    surfaceContainerLow = GlassPayColors.Card,
    surfaceContainer = GlassPayColors.Card,
    surfaceContainerHigh = GlassPayColors.Raised,
    surfaceContainerHighest = GlassPayColors.Raised,
    outline = GlassPayColors.Line,
    outlineVariant = GlassPayColors.Line,
    error = GlassPayColors.Error,
    onError = GlassPayColors.OnAccent,
)

// Escala de los estilos de texto de Figma (Roboto, la fuente del sistema).
private val GlassPayTypography = Typography(
    displayLarge = TextStyle(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)

private val GlassPayShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun GlassPayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GlassPayColorScheme,
        typography = GlassPayTypography,
        shapes = GlassPayShapes,
        content = content,
    )
}
