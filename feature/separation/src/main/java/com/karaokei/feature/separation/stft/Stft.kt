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
 * The convention matches PyTorch's `torch.stft` / `torch.istft` with
 * `center=True`, `pad_mode='reflect'`, periodic Hann, so the host-side
 * STFT feeds the core ONNX a spectrogram identical to the one the
 * upstream weights were trained on:
 *
 *  - forward: reflect-pad `windowSize/2` on each side, periodic Hann,
 *    `numFrames = 1 + n / hop`. Output `[numFrames][2 * numBins]` with
 *    `out[2k] = Re`, `out[2k+1] = -Im` (numpy rfft sign convention).
 *  - inverse: conjugate-mirror to a full spectrum, positive-sign FFT
 *    (i.e. `angleStep = +2π / len`) scaled by `1/N`, Hann overlap-add,
 *    crop `windowSize/2` from each side.
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
    private val pad: Int = windowSize / 2
    private val window: FloatArray = periodicHann(windowSize)

    /**
     * Forward STFT (centered, reflect-padded). Output shape:
     * `[numFrames][2 * numBins]` with `out[2k] = Re`, `out[2k+1] = -Im`.
     */
    fun transform(samples: FloatArray): Array<FloatArray> {
        val frames = numFrames(samples.size)
        val out = Array(frames) { FloatArray(2 * numBins) }
        val buffer = FloatArray(windowSize * 2)
        for (frame in 0 until frames) {
            val frameStart = frame * hopSize
            for (i in 0 until windowSize) {
                val paddedIdx = frameStart + i
                val origIdx = paddedIdx - pad
                buffer[2 * i] = reflectRead(samples, origIdx) * window[i]
                buffer[2 * i + 1] = 0f
            }
            dftInPlace(buffer, out, frame)
        }
        return out
    }

    private fun reflectRead(samples: FloatArray, idx: Int): Float {
        val n = samples.size
        return when {
            idx < 0 -> samples[-idx]
            idx >= n -> samples[2 * n - 2 - idx]
            else -> samples[idx]
        }
    }

    private fun dftInPlace(buffer: FloatArray, out: Array<FloatArray>, frame: Int) {
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

    /**
     * Frame count for a centered STFT. With `center=True` the padded
     * length is `n + 2 * pad`, so the number of frames is
     * `1 + (n + 2 * pad - windowSize) / hop = 1 + n / hop`.
     */
    fun numFrames(samples: Int): Int = 1 + samples / hopSize

    /**
     * Inverse STFT. Reconstructs a time-domain signal from a complex
     * spectrogram by inverse FFT, windowing, and overlap-add, with the
     * `windowSize/2` center pad cropped from each end. Output is always
     * exactly `expectedLength` samples (padded with zeros at the tail
     * if the iSTFT naturally yields fewer samples — mirrors PyTorch's
     * `torch.istft(..., length=n)` padding/cropping).
     */
    fun inverse(real: Array<FloatArray>, imag: Array<FloatArray>, expectedLength: Int): FloatArray {
        val numFrames = real.size
        val outLen = (numFrames - 1) * hopSize + windowSize
        val out = FloatArray(outLen)
        val weight = FloatArray(outLen)
        val buffer = FloatArray(windowSize * 2)
        for (frame in 0 until numFrames) {
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
                out[idx] += sample
                weight[idx] += window[i] * window[i]
            }
        }
        for (i in out.indices) {
            if (weight[i] > 1e-9f) out[i] /= weight[i]
        }
        val reconstructed = out.copyOfRange(pad, pad + (outLen - 2 * pad))
        // Mirror torch.istft(..., length=n): always return exactly n
        // samples, zero-padding the tail if the iSTFT produced fewer.
        if (reconstructed.size >= expectedLength) return reconstructed.copyOfRange(0, expectedLength)
        val padded = FloatArray(expectedLength)
        System.arraycopy(reconstructed, 0, padded, 0, reconstructed.size)
        return padded
    }

    /**
     * Same as [inverse] but takes a single packed buffer `[numFrames][2 * numBins]`
     * (alternating real/imag per bin) for slightly cheaper construction.
     */
    fun inverse(packed: Array<FloatArray>, expectedLength: Int): FloatArray {
        val numFrames = packed.size
        val outLen = (numFrames - 1) * hopSize + windowSize
        val out = FloatArray(outLen)
        val weight = FloatArray(outLen)
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
                out[idx] += sample
                weight[idx] += window[i] * window[i]
            }
        }
        for (i in out.indices) {
            if (weight[i] > 1e-9f) out[i] /= weight[i]
        }
        val reconstructed = out.copyOfRange(pad, pad + (outLen - 2 * pad))
        if (reconstructed.size >= expectedLength) return reconstructed.copyOfRange(0, expectedLength)
        val padded = FloatArray(expectedLength)
        System.arraycopy(reconstructed, 0, padded, 0, reconstructed.size)
        return padded
    }

    private fun inverseFftInPlace(buffer: FloatArray) {
        ifftInPlace(buffer)
    }

    private fun fftInPlace(buffer: FloatArray) {
        // Standard iterative radix-2 FFT with negative twiddle.
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
        butterfly(buffer, negTwiddle = true)
    }

    private fun ifftInPlace(buffer: FloatArray) {
        // Inverse FFT: same bit-reversal + butterfly with positive
        // twiddle, then divide by N. The forward/inverse share the
        // butterfly core; only the twiddle sign differs.
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
        butterfly(buffer, negTwiddle = false)
        val invN = 1f / n
        for (i in 0 until n) {
            buffer[2 * i] *= invN
            buffer[2 * i + 1] *= invN
        }
    }

    private fun butterfly(buffer: FloatArray, negTwiddle: Boolean) {
        val n = windowSize
        var len = 2
        while (len <= n) {
            val half = len / 2
            val angleStep = if (negTwiddle) -2.0 * Math.PI / len else 2.0 * Math.PI / len
            var i = 0
            while (i < n) {
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
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        /**
         * Periodic (DFT-even) Hann window. Matches PyTorch's
         * `torch.hann_window(n, periodic=True)` default, so the
         * forward STFT feeds the ONNX core a spectrogram consistent
         * with the upstream training pre-processing.
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
