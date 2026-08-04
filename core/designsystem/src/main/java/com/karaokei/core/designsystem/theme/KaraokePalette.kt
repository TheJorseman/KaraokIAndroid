package com.karaokei.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// Karaoke scene palette (independent of Material light/dark).
// Used by the karaoke player for the line illumination gradient and
// background overlay.
object KaraokePalette {
    val StageBackgroundDark = Color(0xFF0E0B1A)
    val StageBackgroundLight = Color(0xFF1B1F3A)
    val LyricActive = Color(0xFFFFD740)
    val LyricUpcoming = Color(0xFFB3E5FC)
    val LyricPast = Color(0x66FFFFFF)
    val HighlightPrimary = Color(0xFFFF4081)
    val HighlightSecondary = Color(0xFF7C4DFF)
}
