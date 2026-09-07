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
 * Layout (vertical stack, top to bottom):
 *  - One previous line above the active line (faded, smaller).
 *  - The active line, centred vertically, large, with per-word
 *    progressive illumination.
 *  - One next line below (faded, smaller).
 *
 * Active line drawing:
 *  1. Draw the whole line in the "upcoming" colour so the reader
 *     sees the shape of the upcoming text.
 *  2. For each word whose start time is in the past, redraw it in
 *     the "active" colour. For the word currently being sung
 *     (state.wordIndex), draw a horizontal gradient that reveals it
 *     from the left up to wordProgress.
 *  3. Words not yet reached stay in the upcoming colour.
 *
 * Per-word measurement avoids the coarse "widthPerChar" estimate
 * the previous version used, which made the highlight drift when a
 * line mixed narrow and wide glyphs.
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
    val centerY = size.height * 0.55f
    val activeStyle = baseStyle(36.sp)
    val fadedStyle = baseStyle(22.sp)

    state.previousLine?.let { prev ->
        drawCenteredLine(prev, measurer, fadedStyle, centerY - 70f, KaraokePalette.LyricPast)
    }
    drawActiveLine(state, measurer, activeStyle, centerY)
    state.nextLine?.let { nxt ->
        drawCenteredLine(nxt, measurer, fadedStyle, centerY + 60f, KaraokePalette.LyricUpcoming)
    }
}

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

/**
 * Draw the active line with per-word illumination.
 *
 * Each word is laid out separately against the shared text baseline so
 * the highlight ends exactly at the current word's right edge. The
 * current word gets a horizontal gradient that reveals `wordProgress`
 * of its width.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawActiveLine(
    state: KaraokeState.Active,
    measurer: TextMeasurer,
    style: TextStyle,
    centerY: Float,
) {
    val words = state.line.words
    if (words.isEmpty()) return

    val wordGap = measurer.measure(" ", style).size.width.toFloat()
    val widths = FloatArray(words.size)
    for (i in words.indices) {
        widths[i] = measurer.measure(words[i].text, style).size.width.toFloat()
    }

    val totalWidth = widths.sum() + wordGap * (words.size - 1)
    val startX = (size.width - totalWidth) / 2f
    val baselineY = centerY - style.fontSize.toPx() / 2f * 0.8f

    // Pass 1 — upcoming text (faded white).
    for (i in words.indices) {
        val left = wordLeft(startX, widths, wordGap, i)
        val result = measurer.measure(words[i].text, style)
        drawText(result, color = KaraokePalette.LyricUpcoming, topLeft = Offset(left, baselineY))
    }

    // Pass 2 — fully-sung words in solid active colour.
    val sungUpTo = state.wordIndex
    for (i in 0 until sungUpTo) {
        if (i >= words.size) break
        val left = wordLeft(startX, widths, wordGap, i)
        val result = measurer.measure(words[i].text, style)
        drawText(result, color = KaraokePalette.LyricActive, topLeft = Offset(left, baselineY))
    }

    // Pass 3 — current word with horizontal gradient up to wordProgress.
    // The brush stops at `reveal` and turns transparent so the
    // upcoming text drawn in Pass 1 stays visible on the right.
    if (sungUpTo in words.indices) {
        val i = sungUpTo
        val left = wordLeft(startX, widths, wordGap, i)
        val result = measurer.measure(words[i].text, style)
        val wordWidth = result.size.width.toFloat()
        val reveal = (wordWidth * state.wordProgress.coerceIn(0f, 1f)).toFloat()
        if (reveal > 0f && wordWidth > 0f) {
            val revealStop = (reveal / wordWidth).coerceIn(0f, 1f)
            val brush = if (revealStop >= 1f) {
                Brush.horizontalGradient(
                    colors = listOf(KaraokePalette.LyricActive, KaraokePalette.HighlightPrimary),
                    startX = left,
                    endX = left + wordWidth,
                )
            } else {
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to KaraokePalette.LyricActive,
                        revealStop to KaraokePalette.HighlightPrimary,
                        revealStop to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    startX = left,
                    endX = left + wordWidth,
                )
            }
            drawText(
                textMeasurer = measurer,
                text = words[i].text,
                style = style.copy(brush = brush),
                topLeft = Offset(left, baselineY),
            )
        }
    }
}

/**
 * Compute the left X for word `i` given the cumulative widths and the
 * inter-word gap. Avoids allocating a running list per frame.
 */
private fun wordLeft(startX: Float, widths: FloatArray, wordGap: Float, i: Int): Float {
    var x = startX
    for (j in 0 until i) x += widths[j] + wordGap
    return x
}
