package com.karaokei.feature.separation.stft

import kotlin.math.cos
import kotlin.math.sin

/**
 * Short-Time Fourier Transform, plain Kotlin.
 *
 * Used by the MDX-Net and Mel-Band RoFormer pipelines to convert
 * windowed PCM samples into the spectrogram tensors expected by the
 * ONNX graphs.
 *
 * The inverse (iSTFT) is also provided for the overlap-add
 * reconstruction of `vocals.wav` / `instrumental.wav`.
 *
 * Not the fastest possible implementation (no native SIMD), but
 * adequate for the MVP and trivially testable. The ONNX inference
 * dominates the wall-clock time anyway.
 */
class Stft(
    val windowSize: Int = 2048,
    val hopSize: Int = 512,
) {

    val fftSize: Int = windowSize
    val numBins: Int = windowSize / 2 + 1
    private val window: FloatArray = hannWindow(windowSize)

    /**
     * Forward STFT returning the full per-frame complex spectrogram
     * (real+imag packed per bin) so callers can build the dense
     * `[frames, numBins, complex]` tensors that MDX-Net / RoFormer
     * consume. Output shape: `[numFrames][2 * numBins]` where for
     * each frame `out[2*k]` = real and `out[2*k+1]` = `-imag`
     * (numpy `rfft` convention).
     */
    fun transform(samples: FloatArray): Array<FloatArray> {
        val frames = numFrames(samples.size)
        val out = Array(frames) { FloatArray(2 * numBins) }
        val buffer = FloatArray(windowSize * 2)
        for (frame in 0 until frames) {
            val start = frame * hopSize
            for (i in 0 until windowSize) {
                val sample = if (start + i < samples.size) samples[start + i] else 0f
                buffer[2 * i] = sample * window[i]
                buffer[2 * i + 1] = 0f
            }
            dftInPlace(buffer, out, frame)
        }
        return out
    }

    private fun dftInPlace(buffer: FloatArray, out: Array<FloatArray>, frame: Int) {
        // Use a radix-2 Cooley-Tukey when windowSize is a power of two,
        // which it is by default (2048). Falls back to a naive O(N^2)
        // DFT otherwise.
        if ((windowSize and (windowSize - 1)) == 0) {
            fftInPlace(buffer)
            for (k in 0 until numBins) {
                out[frame][2 * k] = buffer[2 * k]
                out[frame][2 * k + 1] = -buffer[2 * k + 1]
            }
        } else {
            for (k in 0 until numBins) {
                var re = 0f
                var im = 0f
                val angle = -2.0 * Math.PI * k / windowSize
                for (n in 0 until windowSize) {
                    re += buffer[2 * n] * cos(angle * n).toFloat()
                    im += buffer[2 * n] * sin(angle * n).toFloat()
                }
                out[frame][2 * k] = re
                out[frame][2 * k + 1] = -im
            }
        }
    }

    fun numFrames(samples: Int): Int = ((samples - windowSize) / hopSize).coerceAtLeast(0) + 1

    /**
     * Inverse STFT. Reconstructs a time-domain signal from a complex
     * spectrogram by inverse FFT, windowing, and overlap-add with the
     * Hann window-correction factor (3/8 for periodic Hann at 50%
     * overlap).
     *
     * @param real 2-D array `[numFrames][numBins]` of real parts.
     * @param imag 2-D array `[numFrames][numBins]` of imaginary parts.
     * @param expectedLength The expected number of samples. The output
     * is cropped to this length so trailing zero-pad artefacts are
     * dropped.
     */
    fun inverse(real: Array<FloatArray>, imag: Array<FloatArray>, expectedLength: Int): FloatArray {
        val numFrames = real.size
        val out = FloatArray(expectedLength + windowSize)
        val weight = FloatArray(expectedLength + windowSize)
        val buffer = FloatArray(windowSize * 2)
        for (frame in 0 until numFrames) {
            // `transform` writes `re - i*im` (numpy convention); flip
            // the imaginary sign so the FFT operates in the internal
            // `+i` convention.
            for (k in 0 until numBins) {
                buffer[2 * k] = real[frame][k]
                buffer[2 * k + 1] = -imag[frame][k]
            }
            for (k in numBins until windowSize) {
                val mirrored = windowSize - k
                buffer[2 * k] = real[frame][mirrored]
                buffer[2 * k + 1] = imag[frame][mirrored]
            }
            inverseFftInPlace(buffer)
            val start = frame * hopSize
            for (i in 0 until windowSize) {
                val sample = buffer[2 * i] * window[i]
                val idx = start + i
                if (idx < out.size) {
                    out[idx] += sample
                    weight[idx] += window[i] * window[i]
                }
            }
        }
        for (i in out.indices) {
            if (weight[i] > 1e-9f) out[i] /= weight[i]
        }
        return out.copyOfRange(0, expectedLength.coerceAtMost(out.size))
    }

    /**
     * Same as [inverse] but takes a single packed buffer `[numFrames][2 * numBins]`
     * (alternating real/imag per bin) for slightly cheaper construction.
     */
    fun inverse(packed: Array<FloatArray>, expectedLength: Int): FloatArray {
        val numFrames = packed.size
        val out = FloatArray(expectedLength + windowSize)
        val weight = FloatArray(expectedLength + windowSize)
        val buffer = FloatArray(windowSize * 2)
        for (frame in 0 until numFrames) {
            val row = packed[frame]
            for (k in 0 until numBins) {
                buffer[2 * k] = row[2 * k]
                buffer[2 * k + 1] = -row[2 * k + 1]
            }
            for (k in numBins until windowSize) {
                val mirrored = windowSize - k
                buffer[2 * k] = row[2 * mirrored]
                buffer[2 * k + 1] = row[2 * mirrored + 1]
            }
            inverseFftInPlace(buffer)
            val start = frame * hopSize
            for (i in 0 until windowSize) {
                val sample = buffer[2 * i] * window[i]
                val idx = start + i
                if (idx < out.size) {
                    out[idx] += sample
                    weight[idx] += window[i] * window[i]
                }
            }
        }
        for (i in out.indices) {
            if (weight[i] > 1e-9f) out[i] /= weight[i]
        }
        return out.copyOfRange(0, expectedLength.coerceAtMost(out.size))
    }

    private fun inverseFftInPlace(buffer: FloatArray) {
        // Same radix-2 FFT, then conjugate the imaginary parts so the
        // forward FFT becomes its inverse (scaled by N).
        fftInPlace(buffer)
        val n = windowSize
        for (i in 0 until n) {
            buffer[2 * i] /= n
            buffer[2 * i + 1] = -buffer[2 * i + 1] / n
        }
    }

    private fun fftInPlace(buffer: FloatArray) {
        // Standard iterative radix-2 FFT. Replaces [re, im, re, im, ...]
        // in place. See e.g. Sedgewick, "Algorithms".
        val n = windowSize
        var j = 0
        for (k in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (j > k) {
                val tr = buffer[2 * k]
                val ti = buffer[2 * k + 1]
                buffer[2 * k] = buffer[2 * j]
                buffer[2 * k + 1] = buffer[2 * j + 1]
                buffer[2 * j] = tr
                buffer[2 * j + 1] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val angleStep = -2.0 * Math.PI / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in 0 until half) {
                    val angle = angleStep * m
                    val wr = cos(angle).toFloat()
                    val wi = sin(angle).toFloat()
                    val xr = buffer[2 * (i + m + half)]
                    val xi = buffer[2 * (i + m + half) + 1]
                    val tre = wr * xr - wi * xi
                    val tim = wr * xi + wi * xr
                    buffer[2 * (i + m + half)] = buffer[2 * (i + m)] - tre
                    buffer[2 * (i + m + half) + 1] = buffer[2 * (i + m) + 1] - tim
                    buffer[2 * (i + m)] += tre
                    buffer[2 * (i + m) + 1] += tim
                    k += 2
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun hannWindow(size: Int): FloatArray {
        val out = FloatArray(size)
        for (i in 0 until size) {
            out[i] = (0.5 - 0.5 * cos(2.0 * Math.PI * i / (size - 1))).toFloat()
        }
        return out
    }

    companion object {
        /**
         * Periodic (DFT-even) Hann window. Slightly different from the
         * symmetric Hann produced by the symmetric formula; periodic
         * Hann is the right choice for STFT overlap-add.
         */
        fun periodicHann(size: Int): FloatArray {
            val out = FloatArray(size)
            for (i in 0 until size) {
                out[i] = (0.5 - 0.5 * cos(2.0 * Math.PI * i / size)).toFloat()
            }
            return out
        }
    }
}
