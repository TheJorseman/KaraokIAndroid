package com.karaokei.feature.karaokeplayer.renderer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
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
 * Layout (vertical stack, centred on the active block):
 *  - Up to two previous lines above (fading out with distance).
 *  - The active line, large, with per-word progressive illumination.
 *    Lines wider than the screen wrap to a second row at the same
 *    font size (shrinking to a readable floor first) instead of
 *    squeezing everything into one clipped row.
 *  - Up to two next lines below (fading out with distance).
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
            is KaraokeState.Upcoming -> drawUpcoming(state, measurer)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUpcoming(
    state: KaraokeState.Upcoming,
    measurer: TextMeasurer,
) {
    val maxWidth = size.width * 0.92f
    val centerY = size.height * 0.52f
    var style = baseStyle(ACTIVE_FONT, FontWeight.Bold)
    var rows = wrapWords(state.nextLine.words, measurer, style, maxWidth)
    if (rows.size > MAX_ACTIVE_ROWS) {
        style = baseStyle(ACTIVE_FONT_MIN, FontWeight.Bold)
        rows = wrapWords(state.nextLine.words, measurer, style, maxWidth)
    }
    val lineHeight = style.fontSize.toPx() * 1.35f
    val blockHeight = rows.size * lineHeight
    val topY = centerY - blockHeight / 2f

    // The upcoming line is dimmed — nothing has been sung yet.
    val dim = KaraokePalette.LyricUpcoming
    for ((r, row) in rows.withIndex()) {
        val gap = measurer.measure(" ", style).size.width.toFloat()
        val count = row.to - row.from
        val widths = FloatArray(count) { i -> measurer.measure(state.nextLine.words[row.from + i].text, style).size.width.toFloat() }
        val rowWidth = widths.sum() + gap * (count - 1).coerceAtLeast(0)
        var x = ((size.width - rowWidth) / 2f).coerceAtLeast(0f)
        for (i in 0 until count) {
            val text = state.nextLine.words[row.from + i].text
            drawText(measurer.measure(text, style), color = dim, topLeft = Offset(x, topY + r * lineHeight))
            x += widths[i] + gap
        }
    }
    state.nextLine2?.let { nxt ->
        drawCenteredLine(
            nxt,
            measurer,
            baseStyle(CONTEXT_FONT, FontWeight.Normal),
            topY + blockHeight + 28f,
            KaraokePalette.LyricUpcoming.copy(alpha = 0.45f),
        )
    }
}

private const val MAX_ACTIVE_ROWS = 2
private val ACTIVE_FONT = 34.sp
private val ACTIVE_FONT_MIN = 22.sp
private val CONTEXT_FONT = 20.sp

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawActive(
    state: KaraokeState.Active,
    measurer: TextMeasurer,
) {
    val maxWidth = size.width * 0.92f
    val centerY = size.height * 0.52f

    // ---- active block: pick the largest font that fits the wrap budget
    var activeStyle = baseStyle(ACTIVE_FONT, FontWeight.Bold)
    var rows = wrapWords(state.line.words, measurer, activeStyle, maxWidth)
    if (rows.size > MAX_ACTIVE_ROWS) {
        activeStyle = baseStyle(ACTIVE_FONT_MIN, FontWeight.Bold)
        rows = wrapWords(state.line.words, measurer, activeStyle, maxWidth)
    }
    val lineHeight = activeStyle.fontSize.toPx() * 1.35f
    val blockHeight = rows.size * lineHeight

    // ---- context lines
    val contextStyle = baseStyle(CONTEXT_FONT, FontWeight.Normal)
    val contextLineHeight = contextStyle.fontSize.toPx() * 1.45f
    val above = listOfNotNull(state.previousLine2, state.previousLine)
    val below = listOfNotNull(state.nextLine, state.nextLine2)

    var y = centerY - blockHeight / 2f - contextLineHeight * above.size - 28f
    above.forEachIndexed { index, line ->
        // The closer to the active line, the brighter.
        val t = (index + 1f) / (above.size + 1f)
        drawCenteredLine(line, measurer, contextStyle, y, KaraokePalette.LyricPast.copy(alpha = 0.35f + 0.5f * t))
        y += contextLineHeight
    }
    y += 28f

    drawActiveRows(state, rows, measurer, activeStyle, y, lineHeight)
    y += blockHeight + 28f

    below.forEachIndexed { index, line ->
        val t = 1f - index.toFloat() / (below.size + 1f)
        drawCenteredLine(line, measurer, contextStyle, y, KaraokePalette.LyricUpcoming.copy(alpha = 0.35f + 0.5f * t))
        y += contextLineHeight
    }
}

private fun baseStyle(size: TextUnit, weight: FontWeight): TextStyle = TextStyle(
    fontSize = size,
    fontWeight = weight,
    textAlign = TextAlign.Center,
    color = Color.White,
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.75f),
        offset = Offset(0f, 2f),
        blurRadius = 8f,
    ),
)

/** A wrapped row: a slice of word indices that fit within [maxWidth]. */
private class WordRow(val from: Int, val to: Int)

private fun wrapWords(
    words: List<com.karaokei.feature.karaoke.engine.KaraokeWord>,
    measurer: TextMeasurer,
    style: TextStyle,
    maxWidth: Float,
): List<WordRow> {
    if (words.isEmpty()) return emptyList()
    val gap = measurer.measure(" ", style).size.width.toFloat()
    val rows = ArrayList<WordRow>()
    var from = 0
    var width = 0f
    for (i in words.indices) {
        val w = measurer.measure(words[i].text, style).size.width.toFloat()
        val projected = if (i == from) w else width + gap + w
        if (projected > maxWidth && i > from) {
            rows.add(WordRow(from, i))
            from = i
            width = w
        } else {
            width = projected
        }
    }
    rows.add(WordRow(from, words.size))
    return rows
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawActiveRows(
    state: KaraokeState.Active,
    rows: List<WordRow>,
    measurer: TextMeasurer,
    style: TextStyle,
    topY: Float,
    lineHeight: Float,
) {
    val gap = measurer.measure(" ", style).size.width.toFloat()
    for ((r, row) in rows.withIndex()) {
        val count = row.to - row.from
        val widths = FloatArray(count) { i ->
            measurer.measure(words(state, row.from + i), style).size.width.toFloat()
        }
        val rowWidth = widths.sum() + gap * (count - 1).coerceAtLeast(0)
        val startX = ((size.width - rowWidth) / 2f).coerceAtLeast(0f)
        val baselineY = topY + r * lineHeight

        var x = startX
        for (i in 0 until count) {
            val wordIndex = row.from + i
            drawWord(state, wordIndex, measurer, style, x, baselineY, widths[i])
            x += widths[i] + gap
        }
    }
}

private fun words(state: KaraokeState.Active, index: Int): String =
    state.line.words.getOrNull(index)?.text ?: ""

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWord(
    state: KaraokeState.Active,
    wordIndex: Int,
    measurer: TextMeasurer,
    style: TextStyle,
    left: Float,
    top: Float,
    width: Float,
) {
    val text = words(state, wordIndex)
    when {
        wordIndex < state.wordIndex ->
            drawText(measurer.measure(text, style), color = KaraokePalette.LyricActive, topLeft = Offset(left, top))
        wordIndex == state.wordIndex -> {
            val result = measurer.measure(text, style)
            val reveal = (width * state.wordProgress.coerceIn(0f, 1f))
            if (reveal <= 0f || width <= 0f) {
                drawText(result, color = KaraokePalette.LyricUpcoming, topLeft = Offset(left, top))
                return
            }
            val revealStop = (reveal / width).coerceIn(0f, 1f)
            val brush = if (revealStop >= 1f) {
                Brush.horizontalGradient(
                    colors = listOf(KaraokePalette.LyricActive, KaraokePalette.HighlightPrimary),
                    startX = left,
                    endX = left + width,
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
                    endX = left + width,
                )
            }
            drawText(
                textMeasurer = measurer,
                text = text,
                style = style.copy(brush = brush),
                topLeft = Offset(left, top),
            )
        }
        else ->
            drawText(measurer.measure(text, style), color = KaraokePalette.LyricUpcoming, topLeft = Offset(left, top))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenteredLine(
    line: KaraokeLine,
    measurer: TextMeasurer,
    style: TextStyle,
    centerY: Float,
    color: Color,
) {
    val text = line.words.joinToString(" ") { it.text }
    var result: TextLayoutResult = measurer.measure(text, style)
    val maxWidth = size.width * 0.92f
    if (result.size.width > maxWidth && result.size.width > 0) {
        val scaled = style.copy(fontSize = style.fontSize * (maxWidth / result.size.width))
        result = measurer.measure(text, scaled)
    }
    val left = ((size.width - result.size.width) / 2f).coerceAtLeast(0f)
    val top = centerY - result.size.height / 2f
    drawText(result, color = color, topLeft = Offset(left, top))
}
