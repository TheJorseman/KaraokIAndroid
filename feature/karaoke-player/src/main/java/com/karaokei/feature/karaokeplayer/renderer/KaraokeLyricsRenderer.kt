package com.karaokei.feature.karaokeplayer.renderer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.karaokei.core.designsystem.theme.KaraokePalette
import com.karaokei.feature.karaoke.engine.KaraokeLine
import com.karaokei.feature.karaoke.engine.KaraokeState

/**
 * Compose Canvas renderer for the karaoke lyrics.
 *
 * Layout:
 *  - The active line is centred vertically.
 *  - One previous line above (faded).
 *  - One next line below (faded, smaller).
 *
 * The active line is drawn with per-word illumination: a horizontal
 * gradient reveals each word up to its `wordProgress` (0..1).
 */
@Composable
fun KaraokeLyricsRenderer(
    state: KaraokeState,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier.fillMaxSize()) {
        when (state) {
            is KaraokeState.Idle -> Unit
            is KaraokeState.Active -> drawActive(state, measurer)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawActive(
    state: KaraokeState.Active,
    measurer: TextMeasurer,
) {
    val line = state.line
    val previous = previousLine(line)
    val next = nextLine(line)

    val centerY = size.height * 0.55f
    val activeStyle = baseStyle(36.sp)
    val fadedStyle = baseStyle(22.sp)

    if (previous != null) {
        drawCenteredLine(previous, measurer, fadedStyle, centerY - 80f, KaraokePalette.LyricPast)
    }
    drawLineWithProgressiveHighlight(line, state, measurer, activeStyle, centerY)
    if (next != null) {
        drawCenteredLine(next, measurer, fadedStyle, centerY + 80f, KaraokePalette.LyricPast)
    }
}

private fun previousLine(line: KaraokeLine): KaraokeLine? = null
private fun nextLine(line: KaraokeLine): KaraokeLine? = null

private fun baseStyle(size: TextUnit): TextStyle = TextStyle(
    fontSize = size,
    textAlign = TextAlign.Center,
    color = Color.White,
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.7f),
        offset = Offset(0f, 2f),
        blurRadius = 6f,
    ),
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenteredLine(
    line: KaraokeLine,
    measurer: TextMeasurer,
    style: TextStyle,
    centerY: Float,
    color: Color,
) {
    val text = line.words.joinToString(" ") { it.text }
    val result: TextLayoutResult = measurer.measure(text, style)
    val left = (size.width - result.size.width) / 2f
    val top = centerY - result.size.height / 2f
    drawText(result, color = color, topLeft = Offset(left, top))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLineWithProgressiveHighlight(
    line: KaraokeLine,
    state: KaraokeState.Active,
    measurer: TextMeasurer,
    style: TextStyle,
    centerY: Float,
) {
    // Pass 1: draw the full line in muted white to establish the
    // baseline. Pass 2: redraw each word up to the current one with
    // a horizontal gradient that respects wordProgress.
    val text = line.words.joinToString(" ") { it.text }
    val result = measurer.measure(text, style)
    val left = (size.width - result.size.width) / 2f
    val top = centerY - result.size.height / 2f
    drawText(result, color = KaraokePalette.LyricUpcoming, topLeft = Offset(left, top))

    val widthPerChar = result.size.width.toFloat() / text.length.coerceAtLeast(1)
    val highlightEndX = result.size.width.toFloat() * computeOverallProgress(line, state)
    val brush = Brush.horizontalGradient(
        colors = listOf(KaraokePalette.LyricActive, KaraokePalette.HighlightPrimary),
        startX = left,
        endX = left + highlightEndX,
    )
    drawText(
        result,
        brush = brush,
        topLeft = Offset(left, top),
    )
}

private fun computeOverallProgress(line: KaraokeLine, state: KaraokeState.Active): Float {
    val charsBefore = line.words.take(state.wordIndex).sumOf { it.text.length + 1 }
    val currentWord = line.words.getOrNull(state.wordIndex) ?: return 0f
    val totalChars = line.words.sumOf { it.text.length } + (line.words.size - 1)
    if (totalChars == 0) return 0f
    val charsNow = charsBefore + (currentWord.text.length * state.wordProgress).toInt()
    return (charsNow.toFloat() / totalChars).coerceIn(0f, 1f)
}
