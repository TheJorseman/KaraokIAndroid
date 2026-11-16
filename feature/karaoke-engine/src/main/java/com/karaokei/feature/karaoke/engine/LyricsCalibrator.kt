package com.karaokei.feature.karaoke.engine

/**
 * Automatic global-offset calibration for a karaoke document.
 *
 * Fetched LRC timestamps describe the *original* recording, not the
 * user's copy; and even ASR word times carry a little drift. The
 * calibrator compares lyric line starts against vocal-activity
 * onsets in the actual vocals track and shifts the whole document by
 * the median delta:
 *
 *  1. RMS envelope of the vocals in 50 ms windows.
 *  2. Onsets = windows crossing from below to above an adaptive
 *     threshold (noise floor + a fraction of the energy range).
 *  3. For each lyric line, the nearest onset within ±2 s contributes
 *     one delta sample; the median is the estimated offset.
 *  4. The document is shifted only when the estimate is confident
 *     (≥ 3 matched lines) and meaningful (|offset| ≥ 80 ms), clamped
 *     to ±2.5 s.
 */
object LyricsCalibrator {

    private const val WINDOW_SEC = 0.05
    private const val MAX_SEARCH_SEC = 2.0
    private const val MIN_SAMPLES = 3
    private const val MIN_SHIFT_SEC = 0.08
    private const val MAX_SHIFT_SEC = 2.5

    fun calibrate(doc: KaraokeDocument, vocals: FloatArray, sampleRate: Int): KaraokeDocument {
        if (doc.lines.isEmpty() || vocals.isEmpty() || sampleRate <= 0) return doc
        val offset = estimateOffsetSec(doc, vocals, sampleRate)
        if (offset == 0.0) return doc
        return shift(doc, offset)
    }

    /** Estimated global offset in seconds, or 0.0 when unconfident. */
    fun estimateOffsetSec(doc: KaraokeDocument, vocals: FloatArray, sampleRate: Int): Double {
        val onsets = vocalOnsets(vocals, sampleRate)
        if (onsets.isEmpty()) return 0.0
        val deltas = ArrayList<Double>()
        for (line in doc.lines) {
            val nearest = onsets.minByOrNull { kotlin.math.abs(it - line.start) } ?: continue
            val delta = nearest - line.start
            if (kotlin.math.abs(delta) <= MAX_SEARCH_SEC) deltas += delta
        }
        if (deltas.size < MIN_SAMPLES) return 0.0
        val median = deltas.sorted()[deltas.size / 2]
        if (kotlin.math.abs(median) < MIN_SHIFT_SEC) return 0.0
        return median.coerceIn(-MAX_SHIFT_SEC, MAX_SHIFT_SEC)
    }

    /** Times (seconds) where vocal energy starts. */
    internal fun vocalOnsets(vocals: FloatArray, sampleRate: Int): List<Double> {
        val window = (sampleRate * WINDOW_SEC).toInt().coerceAtLeast(1)
        val windows = vocals.size / window
        if (windows < 4) return emptyList()
        val energy = DoubleArray(windows) { w ->
            var sum = 0.0
            for (i in w * window until (w + 1) * window) {
                val v = vocals[i].toDouble()
                sum += v * v
            }
            kotlin.math.sqrt(sum / window)
        }
        val sorted = energy.sorted()
        val floor = sorted[sorted.size / 4]
        val peak = sorted[(sorted.size * 9) / 10]
        val threshold = floor + (peak - floor) * 0.25
        if (threshold <= 0.0) return emptyList()
        val onsets = ArrayList<Double>()
        for (w in 1 until windows) {
            if (energy[w] >= threshold && energy[w - 1] < threshold) {
                onsets += w * WINDOW_SEC
            }
        }
        return onsets
    }

    private fun shift(doc: KaraokeDocument, offsetSec: Double): KaraokeDocument {
        val lines = doc.lines.map { line ->
            line.copy(
                start = line.start + offsetSec,
                end = line.end + offsetSec,
                words = line.words.map { w ->
                    w.copy(start = w.start + offsetSec, end = w.end + offsetSec)
                },
            )
        }
        return doc.copy(lines = lines)
    }
}
