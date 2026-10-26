package com.glasspay.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object GlassPayColors {
    val Background = Color(0xFF0B1020)
    val Card = Color(0xFF161D36)
    val Success = Color(0xFFB7F7C0)
    val Warning = Color(0xFFFFDDB3)
    val Error = Color(0xFFFFB4AB)
    val MutedText = Color(0xFFB7D8FF)
}

fun statusColor(kind: StatusKind): Color {
    return when (kind) {
        StatusKind.SUCCESS -> GlassPayColors.Success
        StatusKind.PENDING -> GlassPayColors.Warning
        StatusKind.FAILURE -> GlassPayColors.Error
        StatusKind.NEUTRAL -> GlassPayColors.MutedText
    }
}

private val GlassPayColorScheme = darkColorScheme(
    primary = Color(0xFF6650A4),
    onPrimary = Color.White,
    background = GlassPayColors.Background,
    onBackground = Color.White,
    surface = GlassPayColors.Card,
    onSurface = Color.White,
    surfaceVariant = GlassPayColors.Card,
    onSurfaceVariant = GlassPayColors.MutedText,
    error = GlassPayColors.Error,
)

@Composable
fun GlassPayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GlassPayColorScheme, content = content)
}
