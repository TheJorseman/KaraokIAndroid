package com.karaokei.feature.karaoke.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsCalibratorTest {

    private val sampleRate = 16000

    private fun word(text: String, start: Double, end: Double) =
        KaraokeWord(text = text, start = start, end = end, confidence = 1f)

    private fun line(start: Double, end: Double, text: String) =
        KaraokeLine(start = start, end = end, words = listOf(word(text, start, end)))

    private fun doc(vararg lines: KaraokeLine) =
        KaraokeDocument(songId = "s", language = "es", duration = 60.0, lines = lines.toList())

    /** Silence with loud vocal bursts starting at the given seconds. */
    private fun vocalsWithBursts(burstStartsSec: List<Double>, burstSec: Double = 1.0): FloatArray {
        val total = 60 * sampleRate
        val out = FloatArray(total)
        for (b in burstStartsSec) {
            val from = (b * sampleRate).toInt()
            val to = from + (burstSec * sampleRate).toInt()
            for (i in from until minOf(to, total)) {
                out[i] = 0.5f
            }
        }
        return out
    }

    @Test fun `detects vocal onsets from bursts`() {
        val vocals = vocalsWithBursts(listOf(5.0, 20.0, 40.0))
        val onsets = LyricsCalibrator.vocalOnsets(vocals, sampleRate)
        assertThat(onsets).hasSize(3)
        assertThat(onsets[0]).isWithin(0.2).of(5.0)
        assertThat(onsets[1]).isWithin(0.2).of(20.0)
        assertThat(onsets[2]).isWithin(0.2).of(40.0)
    }

    @Test fun `shifts the document by the measured offset`() {
        // Vocals actually start at 10/25/40 s; lyrics claim 9.6/24.6/39.6
        // -> consistent +0.4 s offset -> document shifts by +0.4.
        val vocals = vocalsWithBursts(listOf(10.0, 25.0, 40.0))
        val d = doc(
            line(9.6, 11.0, "uno"),
            line(24.6, 26.0, "dos"),
            line(39.6, 41.0, "tres"),
        )
        val out = LyricsCalibrator.calibrate(d, vocals, sampleRate)
        assertThat(out.lines[0].start).isWithin(0.15).of(10.0)
        assertThat(out.lines[1].start).isWithin(0.15).of(25.0)
        assertThat(out.lines[2].start).isWithin(0.15).of(40.0)
        // word times shift together
        assertThat(out.lines[0].words[0].end).isWithin(0.15).of(11.4)
    }

    @Test fun `does not shift when the delta is negligible`() {
        val vocals = vocalsWithBursts(listOf(10.0, 25.0, 40.0))
        val d = doc(
            line(10.0, 11.0, "uno"),
            line(25.0, 26.0, "dos"),
            line(40.0, 41.0, "tres"),
        )
        val out = LyricsCalibrator.calibrate(d, vocals, sampleRate)
        assertThat(out).isSameInstanceAs(d)
    }

    @Test fun `does not shift with too few matched lines`() {
        val vocals = vocalsWithBursts(listOf(10.0))
        val d = doc(line(9.0, 11.0, "uno"))
        val out = LyricsCalibrator.calibrate(d, vocals, sampleRate)
        assertThat(out).isSameInstanceAs(d)
    }

    @Test fun `does not shift without vocal activity`() {
        val d = doc(line(1.0, 2.0, "uno"), line(3.0, 4.0, "dos"), line(5.0, 6.0, "tres"))
        val out = LyricsCalibrator.calibrate(d, FloatArray(sampleRate * 60), sampleRate)
        assertThat(out).isSameInstanceAs(d)
    }
}
