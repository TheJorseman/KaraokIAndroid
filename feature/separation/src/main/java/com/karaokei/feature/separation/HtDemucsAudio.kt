package com.karaokei.feature.separation

import kotlin.math.min

/**
 * Lightweight mono / stereo and sample-rate conversions used to adapt
 * the pipeline's internal 16 kHz / mono PCM buffers to the HTDemucs
 * graph contract (44.1 kHz / stereo / 343980 samples).
 *
 * The resamplers are intentionally minimal:
 *
 * - Up-sample uses linear interpolation (good enough for vocals at
 *   16 → 44.1 kHz; the separation model itself is robust to mild
 *   spectral shaping).
 * - Down-sample averages neighbour samples (acts as a basic
 *   anti-alias filter for the 16 kHz pipeline format).
 *
 * No allocation tricks; the pipeline is single-shot and the buffers
 * are bounded by a song's length.
 */
internal object HtDemucsAudio {
    const val MODEL_SAMPLE_RATE_HZ: Int = 44_100
    const val PIPELINE_SAMPLE_RATE_HZ: Int = 16_000
    const val MODEL_SAMPLES_PER_WINDOW: Int = 343_980

    /**
     * Up-sample 16 kHz mono PCM to 44.1 kHz mono PCM using linear
     * interpolation. Output size is `(input.size * 44100 / 16000)`.
     */
    fun mono16kToMono44_1k(mono16k: FloatArray): FloatArray {
        if (mono16k.isEmpty()) return FloatArray(0)
        val ratio = MODEL_SAMPLE_RATE_HZ.toDouble() / PIPELINE_SAMPLE_RATE_HZ.toDouble()
        val outSize = (mono16k.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outSize)
        for (i in 0 until outSize) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt().coerceIn(0, mono16k.size - 1)
            val i1 = (i0 + 1).coerceAtMost(mono16k.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = mono16k[i0] * (1f - frac) + mono16k[i1] * frac
        }
        return out
    }

    /**
     * Broadcast mono PCM into stereo by duplicating the signal on both
     * channels. Output layout is `[L0, L1, ..., Ln-1, R0, R1, ..., Rn-1]`
     * — the same layout the HTDemucs model expects for `[batch=1,
     * channels=2, samples]`.
     */
    fun monoToStereo(mono: FloatArray): FloatArray {
        val stereo = FloatArray(mono.size * 2)
        val half = mono.size
        System.arraycopy(mono, 0, stereo, 0, half)
        System.arraycopy(mono, 0, stereo, half, half)
        return stereo
    }

    /**
     * Take the first [MODEL_SAMPLES_PER_WINDOW] samples, padding with
     * silence when the source is shorter. The HTDemucs graph requires
     * a fixed window length; longer songs would need a sliding-window
     * implementation which is out of scope for the MVP.
     */
    fun padOrTruncate(mono: FloatArray, length: Int): FloatArray {
        if (mono.size == length) return mono
        val out = FloatArray(length)
        val copy = min(mono.size, length)
        System.arraycopy(mono, 0, out, 0, copy)
        return out
    }

    /**
     * Average two stereo channels into a single mono buffer at the
     * same sample rate.
     */
    fun stereo44_1kToMono(stereo44_1k: FloatArray): FloatArray {
        val half = stereo44_1k.size / 2
        val out = FloatArray(half)
        for (i in 0 until half) {
            out[i] = (stereo44_1k[i] + stereo44_1k[half + i]) * 0.5f
        }
        return out
    }

    /**
     * Down-sample 44.1 kHz mono PCM to 16 kHz mono PCM with a basic
     * averaging filter. Output size is `(input.size * 16000 / 44100)`.
     */
    fun mono44_1kToMono16k(mono44_1k: FloatArray): FloatArray {
        if (mono44_1k.isEmpty()) return FloatArray(0)
        val ratio = PIPELINE_SAMPLE_RATE_HZ.toDouble() / MODEL_SAMPLE_RATE_HZ.toDouble()
        val outSize = (mono44_1k.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outSize)
        val step = 1.0 / ratio
        for (i in 0 until outSize) {
            val start = (i * step).toInt().coerceIn(0, mono44_1k.size - 1)
            val end = ((i + 1) * step).toInt().coerceIn(start + 1, mono44_1k.size)
            var sum = 0f
            var count = 0
            for (j in start until end) {
                sum += mono44_1k[j]
                count++
            }
            out[i] = if (count > 0) sum / count else mono44_1k[start]
        }
        return out
    }
}
