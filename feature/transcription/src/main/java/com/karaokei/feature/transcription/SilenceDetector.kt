package com.karaokei.feature.transcription

import com.karaokei.core.common.audio.PcmFormat
import java.io.File
import kotlin.math.sqrt

/**
 * Decides whether the vocal track contains enough energy to bother
 * running Whisper. Tuned empirically; a more sophisticated VAD
 * (e.g. Silero) is a post-MVP item.
 *
 * Heuristic: compute the RMS of the entire file in 100 ms windows.
 * If fewer than 5% of windows are above a threshold (i.e. the song
 * looks mostly silent), the pipeline short-circuits and stores an
 * empty transcript instead of feeding garbage into Whisper (which
 * would otherwise hallucinate text on near-silent input).
 */
object SilenceDetector {

    fun isMostlySilent(wav: File, windowMs: Int = 100, rmsThreshold: Float = 0.01f): Boolean {
        if (!wav.exists() || wav.length() < 44L) return true
        val samples = com.karaokei.core.media.io.WavReader.readPcm16Mono(wav)
        return isMostlySilent(samples, windowMs, rmsThreshold)
    }

    fun isMostlySilent(
        samples: FloatArray,
        windowMs: Int = 100,
        rmsThreshold: Float = 0.01f,
    ): Boolean {
        val samplesPerWindow = (PcmFormat.SAMPLE_RATE_HZ * windowMs) / 1000
        if (samplesPerWindow <= 0) return true
        val totalWindows = (samples.size + samplesPerWindow - 1) / samplesPerWindow
        if (totalWindows == 0) return true
        var active = 0
        var idx = 0
        for (w in 0 until totalWindows) {
            val end = minOf(samples.size, idx + samplesPerWindow)
            var sum = 0.0
            for (i in idx until end) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            val rms = sqrt(sum / (end - idx).coerceAtLeast(1)).toFloat()
            if (rms >= rmsThreshold) active++
            idx = end
        }
        val ratio = active.toFloat() / totalWindows
        return ratio < MIN_ACTIVE_RATIO
    }

    private const val MIN_ACTIVE_RATIO: Float = 0.05f
}
