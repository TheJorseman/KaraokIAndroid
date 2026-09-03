package com.karaokei.feature.separation

import ai.onnxruntime.OnnxTensor
import android.util.Log
import com.karaokei.core.ai.model.ModelLoader
import com.karaokei.core.ai.ort.OrtSessionFactory
import com.karaokei.core.ai.ort.OrtSessionHandle
import com.karaokei.core.common.audio.PcmFormat
import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.getOrThrow
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.data.db.entity.ModelEntity
import com.karaokei.core.data.db.entity.ModelType
import com.karaokei.feature.separation.stft.Stft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * MDX-Net vocal separator for the UVR `Karaoke 2` / `Voc_FT` family
 * of public ONNX graphs.
 *
 * Contract (verified with `scripts/devtools/mdxnet_compare.py`):
 *
 * - **STFT** - `n_fft = 4096`, `hop = 512`, periodic Hann, 16 kHz
 *   mono. The graph expects `[1, 4, numBins, frames]` = `(batch,
 *   real, imag, mag, phase, freq, time)`.
 * - **Output** - same shape; the first two channels (real, imag)
 *   hold the vocal estimate directly (UVR convention; no extra
 *   masking required).
 *
 * Memory budget: long songs are streamed through the model in
 * `WINDOW_SAMPLES` chunks (= ~10 s). Per chunk we hold:
 *
 *  - `mixChunkPcm`     - `WINDOW_SAMPLES` floats
 *  - `mixChunkSpec`    - `(numFramesInChunk) × 4098` floats
 *  - `vocalsPcmChunk`  - `WINDOW_SAMPLES` floats
 *
 * For a 4-minute song the peak memory footprint is dominated by a
 * single 10-s window (~16 MB) instead of the full song (~480 MB),
 * which is what was OOM-ing the 4 GB emulator. Cross-fade overlap-add
 * between consecutive windows eliminates boundary clicks.
 */
@Singleton
class MdxNetSeparator @Inject constructor(
    private val modelLoader: ModelLoader,
    @Dispatcher(KaraokeDispatcher.IO) private val io: CoroutineDispatcher,
) {

    suspend fun separate(
        monoPcm: FloatArray,
        model: ModelEntity,
        testFixture: Boolean = false,
    ): AppResult<SeparationResult> = runCatchingResult {
        require(model.type == ModelType.SEPARATION) { "not a separation model" }
        val localPath = modelLoader.resolvePath(model).getOrThrow()
        Log.i(TAG, "Running MDX-Net ${model.id} (${monoPcm.size} samples, $N_FFT-pt STFT)")
        // Force the plain CPU provider: XNNPACK's fused kernels overflow
        // the UVR Karaoke 2 graph and emit Infinity/NaN on x86_64, which
        // turns vocals.wav / instrumental.wav into silence downstream.
        OrtSessionHandle.openFile(localPath, OrtSessionFactory.Backend.CPU).getOrThrow().use { session ->
            runStreaming(monoPcm, session)
        }
    }.let { result ->
        when (result) {
            is AppResult.Success -> result
            is AppResult.Failure -> AppResult.Failure(
                AppError.Inference(result.error.message, result.error.cause)
            )
        }
    }

    private suspend fun runStreaming(monoPcm: FloatArray, session: OrtSessionHandle): SeparationResult {
        val stft = Stft(windowSize = N_FFT, hopSize = HOP)
        val numBins = stft.numBins // 2049 for n_fft=4096
        require(numBins - 1 == EXPECTED_BINS) {
            "mdx-net model expects $EXPECTED_BINS freq bins, STFT produces ${numBins - 1}"
        }
        val packedStride = 2 * numBins // 4098: real + imag per bin (matches Stft.transform output)
        val totalSamples = monoPcm.size
        val windowSamples = WINDOW_SAMPLES
        val overlap = WINDOW_OVERLAP_SAMPLES
        val step = windowSamples - overlap

        val vocalsPcm = FloatArray(totalSamples)
        val instrumentalPcm = FloatArray(totalSamples)
        val weight = FloatArray(totalSamples)

        var position = 0
        var chunkIndex = 0
        while (position < totalSamples) {
            currentCoroutineContext().ensureActive()
            val start = position
            val end = (start + windowSamples).coerceAtMost(totalSamples)
            val windowSize = end - start
            // Pad with zeros at both ends so the chunk always has
            // exactly windowSamples samples (and the STFT produces
            // the right number of frames).
            val padded = FloatArray(windowSamples)
            System.arraycopy(monoPcm, start, padded, 0, windowSize)
            val spec = stft.transform(padded)
            val chunkFrames = spec.size
            val vocalsSpec = runChunk(session, spec)
            val vocalsChunkPcm = stft.inverse(vocalsSpec, windowSamples)
            // Hann-window crossfade so adjacent chunks blend smoothly.
            val blend = hannWindow(windowSamples)
            for (i in 0 until windowSamples) {
                val idx = start + i
                if (idx >= totalSamples) break
                val mixBack = monoPcm[idx]
                val vocalsSample = vocalsChunkPcm[i] * blend[i]
                val weightDelta = blend[i] * blend[i]
                vocalsPcm[idx] += vocalsSample
                instrumentalPcm[idx] += (mixBack - vocalsChunkPcm[i]) * blend[i]
                weight[idx] += weightDelta
            }
            Log.i(TAG, "MDX-Net chunk=$chunkIndex frames=$chunkFrames sample=$start..$end")
            chunkIndex++
            position += step
        }
        // Normalise the overlap-add so the per-sample weight matches
        // the unit energy (sum of `weight[i]` over all windows).
        for (i in 0 until totalSamples) {
            if (weight[i] > 1e-9f) {
                vocalsPcm[i] /= weight[i]
                instrumentalPcm[i] /= weight[i]
            }
        }
        // The UVR Karaoke 2 graph outputs an unnormalised vocal
        // estimate that is ~1000x louder than the input mix. WavWriter
        // clamps at [-1, 1], so without normalisation the vocals turn
        // into a clipped square wave that Whisper cannot transcribe.
        // Scale the vocal estimate so its RMS is a fixed fraction of
        // the input mix RMS, then recompute `instrumental = mix - vocals`
        // to keep `vocals + instrumental = mix` exactly.
        val mixRms = rms(monoPcm)
        val vocalsRms = rms(vocalsPcm)
        if (vocalsRms > 1e-6f && mixRms > 1e-6f) {
            val targetRms = mixRms * VOCALS_TO_MIX_RATIO
            val scale = targetRms / vocalsRms
            for (i in 0 until totalSamples) {
                vocalsPcm[i] *= scale
                instrumentalPcm[i] = monoPcm[i] - vocalsPcm[i]
            }
        }
        Log.i(TAG, "MDX-Net streaming done: vocals=${vocalsPcm.size} instrumental=${instrumentalPcm.size}")
        return SeparationResult(
            vocals = vocalsPcm,
            instrumental = instrumentalPcm,
            sampleRateHz = PcmFormat.SAMPLE_RATE_HZ,
            durationMs = totalSamples.toLong() * 1000L / PcmFormat.SAMPLE_RATE_HZ,
        )
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }

    private fun runChunk(session: OrtSessionHandle, mixSpec: Array<FloatArray>): Array<FloatArray> {
        val numFrames = mixSpec.size
        val numSubChunks = (numFrames + MDX_FRAMES_PER_CHUNK - 1) / MDX_FRAMES_PER_CHUNK
        val packedStride = mixSpec[0].size // real + imag per bin (4098 for n_fft=4096)
        val vocalsSpec = Array(numFrames) { FloatArray(packedStride) }
        for (sub in 0 until numSubChunks) {
            val subStart = sub * MDX_FRAMES_PER_CHUNK
            val subEnd = (subStart + MDX_FRAMES_PER_CHUNK).coerceAtMost(numFrames)
            val actualFrames = subEnd - subStart
            val input = FloatArray(1 * 4 * EXPECTED_BINS * MDX_FRAMES_PER_CHUNK)
            for (f in 0 until actualFrames) {
                val globalFrame = subStart + f
                for (k in 0 until EXPECTED_BINS) {
                    val re = mixSpec[globalFrame][2 * k]
                    val im = -mixSpec[globalFrame][2 * k + 1] // numpy +imag convention
                    val mag = sqrt(re * re + im * im)
                    val phase = atan2(im, re)
                    val outIdx = ((f * EXPECTED_BINS) + k) * 4
                    input[outIdx] = re
                    input[outIdx + 1] = im
                    input[outIdx + 2] = mag
                    input[outIdx + 3] = phase
                }
            }
            val floatBuffer = ByteBuffer
                .allocateDirect(input.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            floatBuffer.put(input)
            floatBuffer.position(0)
            val tensor = OnnxTensor.createTensor(
                session.environment,
                floatBuffer,
                longArrayOf(1, 4, EXPECTED_BINS.toLong(), MDX_FRAMES_PER_CHUNK.toLong()),
            )
            val outputs = session.session.run(mapOf(MDX_INPUT_NAME to tensor))
            tensor.close()
            val resultTensor = outputs[0] as? OnnxTensor
                ?: error("mdx-net produced no output tensor")
            val raw = resultTensor.value as Array<*>
            val batch = raw[0] as Array<*>
            // Output shape `[batch, 4, EXPECTED_BINS, MDX_FRAMES_PER_CHUNK]`.
            val channelReal = batch[0] as Array<*>
            val channelImag = batch[1] as Array<*>
            for (f in 0 until actualFrames) {
                val globalFrame = subStart + f
                for (k in 0 until EXPECTED_BINS) {
                    val re = (channelReal[k] as FloatArray)[f]
                    val im = (channelImag[k] as FloatArray)[f]
                    vocalsSpec[globalFrame][2 * k] = re
                    vocalsSpec[globalFrame][2 * k + 1] = im
                }
            }
            // Pad bins 2048..numBins-1 with zeros so the iSTFT
            // mirror loop sees matching (real, imag) pairs in the
            // conjugate bin range.
            for (f in 0 until actualFrames) {
                val globalFrame = subStart + f
                for (k in EXPECTED_BINS until mixSpec[globalFrame].size / 2) {
                    vocalsSpec[globalFrame][2 * k] = 0f
                    vocalsSpec[globalFrame][2 * k + 1] = 0f
                }
            }
            outputs.close()
        }
        return vocalsSpec
    }

    private fun hannWindow(size: Int): FloatArray {
        // Symmetric Hann for the overlap-add blend (constant overlap
        // for 50% overlap-add yields a flat sum of squared windows,
        // so the per-sample normalisation just needs `weight`).
        val out = FloatArray(size)
        for (i in 0 until size) {
            out[i] = (0.5 - 0.5 * cos(2.0 * Math.PI * i / (size - 1))).toFloat()
        }
        return out
    }

    companion object {
        private const val TAG = "MdxNetSeparator"

        // Match the UVR `Karaoke 2` / `Voc_FT` ONNX exports: n_fft=4096,
        // 256-frame windows, 4 channels (real, imag, mag, phase).
        const val N_FFT: Int = 4096
        const val HOP: Int = 512
        const val EXPECTED_BINS: Int = N_FFT / 2
        const val MDX_FRAMES_PER_CHUNK: Int = 256
        const val MDX_INPUT_NAME: String = "input"

        // 10 s window at 16 kHz. 1 s overlap.
        const val WINDOW_SAMPLES: Int = 160_000
        const val WINDOW_OVERLAP_SAMPLES: Int = 16_000

        /** Target vocal RMS as a fraction of the input mix RMS. */
        private const val VOCALS_TO_MIX_RATIO: Float = 0.7f
    }
}
