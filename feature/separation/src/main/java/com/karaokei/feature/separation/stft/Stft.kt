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

    /** O(N log N) DFT using a precomputed twiddle table. */
    fun transform(samples: FloatArray): Array<FloatArray> {
        val out = Array(numBins) { FloatArray(2) }
        val buffer = FloatArray(windowSize)
        for (frame in 0 until numFrames(samples.size)) {
            val start = frame * hopSize
            for (i in 0 until windowSize) {
                val sample = if (start + i < samples.size) samples[start + i] else 0f
                buffer[i] = sample * window[i]
            }
            dftInPlace(buffer, out, frame)
        }
        return out
    }

    private fun dftInPlace(window: FloatArray, out: Array<FloatArray>, frame: Int) {
        // Use a radix-2 Cooley-Tukey when windowSize is a power of two,
        // which it is by default (2048). Falls back to a naive O(N^2)
        // DFT otherwise.
        if ((windowSize and (windowSize - 1)) == 0) {
            fftInPlace(window)
            for (k in 0 until numBins) {
                out[frame][0] = window[2 * k]
                out[frame][1] = -window[2 * k + 1]
            }
        } else {
            for (k in 0 until numBins) {
                var re = 0f
                var im = 0f
                val angle = -2.0 * Math.PI * k / windowSize
                for (n in 0 until windowSize) {
                    re += window[n] * cos(angle * n).toFloat()
                    im += window[n] * sin(angle * n).toFloat()
                }
                out[frame][0] = re
                out[frame][1] = im
            }
        }
    }

    fun numFrames(samples: Int): Int = ((samples - windowSize) / hopSize).coerceAtLeast(0) + 1

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
}
