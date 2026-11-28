package com.karaokei.feature.karaoke.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsAlignerTest {

    private fun word(text: String, start: Double, end: Double) =
        KaraokeWord(text = text, start = start, end = end, confidence = 1f)

    private fun line(vararg words: KaraokeWord) =
        KaraokeLine(start = words.first().start, end = words.last().end, words = words.toList())

    private fun doc(vararg lines: KaraokeLine) =
        KaraokeDocument(songId = "s", language = "es", duration = 30.0, lines = lines.toList())

    @Test fun `matched words keep local times and typos are interpolated`() {
        // Local ASR output with garbled second word.
        val local = doc(
            line(word("te", 1.0, 1.2), word("juro", 1.2, 1.6), word("que", 1.6, 1.8), word("te", 1.8, 2.0), word("amo", 2.0, 2.5)),
        )
        val real = listOf(LyricsAligner.RealLine("te juro que te amo", null))
        val out = LyricsAligner.align(real, local)!!

        val words = out.lines[0].words
        assertThat(words.map { it.text }).containsExactly("te", "juro", "que", "te", "amo").inOrder()
        // Anchored words carry the local timings exactly.
        assertThat(words[0].start).isWithin(1e-6).of(1.0)
        assertThat(words[1].start).isWithin(1e-6).of(1.2)
        assertThat(words[4].end).isWithin(1e-6).of(2.5)
    }

    @Test fun `extra real words are interpolated between anchors`() {
        val local = doc(
            line(word("hola", 1.0, 1.5), word("mundo", 2.0, 2.5)),
        )
        // Real lyrics have an extra middle word the ASR missed.
        val real = listOf(LyricsAligner.RealLine("hola bonito mundo", null))
        val out = LyricsAligner.align(real, local)!!
        val words = out.lines[0].words
        assertThat(words).hasSize(3)
        assertThat(words[0].start).isWithin(1e-6).of(1.0)
        assertThat(words[2].start).isWithin(1e-6).of(2.0)
        // The interpolated word sits between the two anchors.
        assertThat(words[1].start).isAtLeast(1.5)
        assertThat(words[1].end).isAtMost(2.0)
    }

    @Test fun `lrc offset is measured and line structure preserved`() {
        // Local lines at 10s and 20s; LRC says 8s and 18s -> offset +2.
        val local = doc(
            line(word("uno", 10.0, 11.0)),
            line(word("dos", 20.0, 21.0)),
        )
        val real = listOf(
            LyricsAligner.RealLine("uno", 8.0),
            LyricsAligner.RealLine("dos", 18.0),
            LyricsAligner.RealLine("tres", 25.0), // no local anchor at all
        )
        val out = LyricsAligner.align(real, local)!!
        assertThat(out.lines).hasSize(3)
        // Anchored lines keep the local times.
        assertThat(out.lines[0].start).isWithin(1e-6).of(10.0)
        assertThat(out.lines[1].start).isWithin(1e-6).of(20.0)
        // The unanchored third line uses LRC time + measured offset (25+2).
        assertThat(out.lines[2].start).isWithin(1e-6).of(27.0)
    }

    @Test fun `rejects lyrics that do not match at all`() {
        val local = doc(line(word("hola", 1.0, 2.0)))
        val real = listOf(LyricsAligner.RealLine("completely different words here", null))
        assertThat(LyricsAligner.align(real, local)).isNull()
    }

    @Test fun `leading lines with LRC use their own times, not the uniform stretch`() {
        // The ASR missed the whole opening verse (first anchor is at
        // 20 s) but the fetched lyrics have LRC times at 10/15 s: the
        // opening must land near those times (plus the measured
        // offset), NOT stretched backwards at 0.35 s per word.
        val local = doc(line(word("final", 20.5, 21.5)))
        val real = listOf(
            LyricsAligner.RealLine("uno dos tres cuatro", 10.0),
            LyricsAligner.RealLine("cinco seis", 15.0),
            LyricsAligner.RealLine("final", 20.0),
        )
        val out = LyricsAligner.align(real, local)!!
        // offset = median(20.5 - 20.0) = +0.5
        val v1 = out.lines[0].words
        assertThat(v1.first().start).isWithin(0.2).of(10.5)
        assertThat(v1.last().end).isAtMost(16.0) // stays inside its LRC line span
        val v2 = out.lines[1].words
        assertThat(v2.first().start).isWithin(0.3).of(15.5)
    }

    @Test fun `no-anchor words are distributed inside their LRC line spans`() {
        val local = doc(line(word("x", 1.0, 2.0)))
        val real = listOf(
            LyricsAligner.RealLine("aaa bbb", 5.0),
            LyricsAligner.RealLine("ccc", 8.0),
        )
        val out = LyricsAligner.align(real, local)!!
        val l1 = out.lines[0].words
        // Both words live inside [5, 8) scaled by length, no overlap.
        assertThat(l1[0].start).isWithin(0.2).of(5.0)
        assertThat(l1[1].start).isGreaterThan(l1[0].end - 0.01)
        assertThat(out.lines[1].words[0].start).isWithin(0.3).of(8.0)
    }

    @Test fun `crammed ASR times are rescued with the LRC pacing`() {
        // Whisper packed a whole phrase into two tenths of a second;
        // matched words inherit the compressed times and the line
        // would flash by. The rescue pass re-places such lines using
        // the LRC span.
        val local = doc(
            line(word("sentimiento", 81.40, 81.46), word("jamas", 81.46, 81.47)),
        )
        val real = listOf(
            LyricsAligner.RealLine("y mi sentimiento", 81.1),
            LyricsAligner.RealLine("no lo cambiare jamas", 85.0),
        )
        val out = LyricsAligner.align(real, local)!!
        val line2 = out.lines[1]
        assertThat(line2.end - line2.start).isAtLeast(0.7)
        assertThat(line2.start).isWithin(0.6).of(85.3) // LRC + measured offset
    }

    @Test fun `dtw matches equal words and tolerates gaps`() {
        val out = LyricsAligner.dtwMatch(
            listOf("a", "b", "c"),
            listOf("a", "x", "b", "c"),
        )
        assertThat(out[0]).isEqualTo(0)
        assertThat(out[1]).isEqualTo(2)
        assertThat(out[2]).isEqualTo(3)
    }
}
