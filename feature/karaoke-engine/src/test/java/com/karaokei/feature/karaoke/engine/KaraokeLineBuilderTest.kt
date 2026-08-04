package com.karaokei.feature.karaoke.engine

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class KaraokeLineBuilderTest {

    private fun word(text: String, start: Double, end: Double, confidence: Float = 1f) =
        com.karaokei.core.common.transcript.TranscriptWord(
            text = text,
            start = start,
            end = end,
            confidence = confidence,
        )

    @Test fun `short segment becomes a single line`() {
        val transcript = com.karaokei.core.common.transcript.TranscriptDocument(
            songId = "x",
            language = "en",
            duration = 5.0,
            modelId = "m",
            segments = listOf(
                com.karaokei.core.common.transcript.TranscriptSegment(
                    start = 0.0,
                    end = 4.0,
                    text = "hello world",
                    words = listOf(word("hello", 0.0, 1.5), word("world", 1.5, 4.0)),
                ),
            ),
        )
        val out = KaraokeLineBuilder.build(transcript)
        assertThat(out.lines).hasSize(1)
        assertThat(out.lines[0].words.map { it.text }).containsExactly("hello", "world").inOrder()
    }

    @Test fun `long segment is split at marker or target size`() {
        val words = (1..20).map { word("w$it", it.toDouble(), it + 0.5) }
        val transcript = com.karaokei.core.common.transcript.TranscriptDocument(
            songId = "x",
            language = "en",
            duration = 25.0,
            modelId = "m",
            segments = listOf(
                com.karaokei.core.common.transcript.TranscriptSegment(
                    start = 0.0,
                    end = 25.0,
                    text = words.joinToString(" ") { it.text },
                    words = words,
                ),
            ),
        )
        val out = KaraokeLineBuilder.build(transcript)
        assertThat(out.lines.size).isAtLeast(2)
        out.lines.forEach { line ->
            assertThat(line.words.size).isAtMost(KaraokeLineBuilder.MAX_WORDS_PER_LINE)
        }
    }
}
