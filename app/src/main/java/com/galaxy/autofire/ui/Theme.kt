package com.galaxy.autofire.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Ink = Color(0xFF070A12)
    val Panel = Color(0xFF0F1623)
    val PanelHigh = Color(0xFF161F30)
    val Line = Color(0xFF1E2A3F)
    val Text = Color(0xFFE8EEF8)
    val Muted = Color(0xFF8A98B0)
    val Blue = Color(0xFF60A5FA)
    val Violet = Color(0xFFA78BFA)
    val Green = Color(0xFF34D399)
    val Red = Color(0xFFFB7185)
    val Idle = Color(0xFF64748B)
}

private val scheme = darkColorScheme(
    primary = Palette.Blue,
    onPrimary = Palette.Ink,
    secondary = Palette.Violet,
    background = Palette.Ink,
    onBackground = Palette.Text,
    surface = Palette.Panel,
    onSurface = Palette.Text,
    surfaceVariant = Palette.PanelHigh,
    onSurfaceVariant = Palette.Muted,
    outline = Palette.Line,
    error = Palette.Red,
)

private val base = Typography()
private val typography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
)

/** Tema oscuro fijo: el monitor se usa junto a un juego y debe evitar deslumbrar. */
@Composable
fun GalaxyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
