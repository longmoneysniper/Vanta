package com.vanta.media.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OledObsidian = Color(0xFF050507)
val ObsidianSurface = Color(0xFF0E0F14)
val FrostedGlassFill = Color(0x261B1D26)
val FrostedBorder = Color(0x33FFFFFF)
val AmberCorona = Color(0xFFFF8A1F)
val CrimsonFlare = Color(0xFFE11D48)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)

private val VantaDarkColorScheme = darkColorScheme(
    primary = AmberCorona,
    secondary = CrimsonFlare,
    background = OledObsidian,
    surface = ObsidianSurface,
    surfaceVariant = FrostedGlassFill,
    onPrimary = OledObsidian,
    onSecondary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = FrostedBorder
)

@Composable
fun VantaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VantaDarkColorScheme,
        content = content
    )
}
