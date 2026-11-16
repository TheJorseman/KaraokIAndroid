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
        assertThat(out.lines[0].words.map { it.text }).containsExactly("Hello", "world").inOrder()
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

    @Test fun `music annotations are dropped from the karaoke lines`() {
        fun segment(text: String, start: Double, end: Double) =
            com.karaokei.core.common.transcript.TranscriptSegment(
                start = start,
                end = end,
                text = text,
                words = listOf(word(text, start, end)),
            )
        val transcript = com.karaokei.core.common.transcript.TranscriptDocument(
            songId = "x",
            language = "es",
            duration = 20.0,
            modelId = "m",
            segments = listOf(
                segment("( Música )", 0.0, 5.0),
                segment("[Music]", 5.0, 8.0),
                segment("♪", 8.0, 9.0),
                segment("hola cariño", 10.0, 15.0),
                segment(" (Música) ", 15.0, 20.0),
            ),
        )
        val out = KaraokeLineBuilder.build(transcript)
        assertThat(out.lines).hasSize(1)
        assertThat(out.lines[0].words.map { it.text }).containsExactly("Hola cariño")
    }

    @Test fun `lyrics containing the word musica are kept`() {
        val transcript = com.karaokei.core.common.transcript.TranscriptDocument(
            songId = "x",
            language = "es",
            duration = 10.0,
            modelId = "m",
            segments = listOf(
                com.karaokei.core.common.transcript.TranscriptSegment(
                    start = 0.0,
                    end = 9.0,
                    text = "pon la música fuerte",
                    words = listOf(word("pon", 0.0, 2.0), word("la música fuerte", 2.0, 9.0)),
                ),
            ),
        )
        val out = KaraokeLineBuilder.build(transcript)
        assertThat(out.lines).hasSize(1)
    }

    @Test fun `words are trimmed and the line is capitalized`() {
        val transcript = com.karaokei.core.common.transcript.TranscriptDocument(
            songId = "x",
            language = "es",
            duration = 10.0,
            modelId = "m",
            segments = listOf(
                com.karaokei.core.common.transcript.TranscriptSegment(
                    start = 0.0,
                    end = 9.0,
                    text = "hola cariño",
                    words = listOf(word("  hola", 0.0, 2.0), word("cariño  ", 2.0, 9.0)),
                ),
            ),
        )
        val out = KaraokeLineBuilder.build(transcript)
        assertThat(out.lines[0].words.map { it.text }).containsExactly("Hola", "cariño").inOrder()
    }

    @Test fun `symbol-only lines are dropped`() {
        fun seg(text: String, start: Double, end: Double) =
            com.karaokei.core.common.transcript.TranscriptSegment(
                start = start, end = end, text = text,
                words = listOf(word(text, start, end)),
            )
        val transcript = com.karaokei.core.common.transcript.TranscriptDocument(
            songId = "x",
            language = "es",
            duration = 20.0,
            modelId = "m",
            segments = listOf(seg("♪ ♪", 0.0, 2.0), seg("…", 2.0, 3.0), seg("hola", 5.0, 9.0)),
        )
        val out = KaraokeLineBuilder.build(transcript)
        assertThat(out.lines).hasSize(1)
        assertThat(out.lines[0].words.map { it.text }).containsExactly("Hola")
    }

    @Test fun `consecutive duplicate lines are collapsed`() {
        fun seg(text: String, start: Double, end: Double) =
            com.karaokei.core.common.transcript.TranscriptSegment(
                start = start, end = end, text = text,
                words = listOf(word(text, start, end)),
            )
        val transcript = com.karaokei.core.common.transcript.TranscriptDocument(
            songId = "x",
            language = "es",
            duration = 20.0,
            modelId = "m",
            segments = listOf(seg("hola", 0.0, 2.0), seg("hola", 2.0, 4.0), seg("adios", 5.0, 9.0)),
        )
        val out = KaraokeLineBuilder.build(transcript)
        assertThat(out.lines.map { it.words.first().text }).containsExactly("Hola", "Adios").inOrder()
    }
}
