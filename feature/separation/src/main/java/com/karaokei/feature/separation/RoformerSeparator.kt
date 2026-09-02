package com.karaokei.feature.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.util.Log
import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.getOrThrow
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.data.db.entity.ModelEntity
import com.karaokei.feature.separation.stft.Stft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin

/**
 * Host-STFT wrapper for the Mel-Band RoFormer vocals FP16 graph
 * published at `silverdaw/mel-band-roformer-vocals-onnx`. The graph
 * consumes a packed complex spectrogram and returns a complex mask;
 * this class drives the host STFT/iSTFT and the overlap-add
 * reconstruction.
 *
 * Contract (verified against
 * `scripts/devtools/roformer_probe.py` and the upstream README):
 *
 * - **STFT** - `n_fft = 2048`, `hop = 441`, 44.1 kHz, periodic Hann,
 *   reflect-padding by `n_fft/2`. One chunk = `441 * (frames - 1)`
 *   samples ≈ 11 s with the 1101-frame default. We window in 11 s
 *   chunks with 3 s overlap (8 s step) and Hamming overlap-add so
 *   longer songs are processed correctly.
 * - **Input tensor** - `stft_repr` `[1, 2050, frames, 2]` =
 *   `(batch, (n_fft/2 + 1) * channels, frames, complex)` with packed
 *   index `2 * freq + channel`. Stereo only (mono is broadcast).
 * - **Output** - `masks` `[1, 2050, frames, 2]`. The host applies the
 *   complex mask by elementwise multiplication onto the input STFT
 *   and reconstructs vocals via envelope-normalised iSTFT.
 */
@Singleton
class RoformerSeparator @Inject constructor(
    private val modelLoader: com.karaokei.core.ai.model.ModelLoader,
    @Dispatcher(KaraokeDispatcher.IO) private val io: CoroutineDispatcher,
) {
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    suspend fun separate(
        stereoPcm: FloatArray,
        model: ModelEntity,
        progress: ((Float) -> Unit)? = null,
    ): AppResult<SeparationResult> = withContext(io) {
        Log.i(TAG, "RoFormer separate: starting for ${model.id}")
        runCatchingResult {
            val localPath = modelLoader.resolvePath(model).getOrThrow()
            Log.i(TAG, "RoFormer separate: opening session ${localPath} (${java.io.File(localPath).length()} bytes)")
            val session = environment.createSession(localPath)
            try {
                val stft = Stft(windowSize = N_FFT, hopSize = HOP)
                val chunkSamples = HOP * (EXPECTED_FRAMES - 1)
                val stepSamples = HOP * STEP_FRAMES
                val overlap = chunkSamples - stepSamples
                val totalSamples = stereoPcm.size / 2
                val chunkCount = maxOf(
                    1,
                    (totalSamples - overlap + stepSamples - 1) / stepSamples,
                )
                Log.i(TAG, "RoFormer separate: $totalSamples stereo samples, $chunkCount chunk(s)")

                val outputLeft = FloatArray(totalSamples)
                val outputRight = FloatArray(totalSamples)
                val weights = FloatArray(totalSamples)

                for (chunkIndex in 0 until chunkCount) {
                    val start = (chunkIndex * stepSamples).coerceAtMost(maxOf(0, totalSamples - chunkSamples))
                    val end = (start + chunkSamples).coerceAtMost(totalSamples)
                    val chunkLeft = FloatArray(chunkSamples)
                    val chunkRight = FloatArray(chunkSamples)
                    for (i in 0 until chunkSamples) {
                        val src = start + i
                        if (src < totalSamples) {
                            chunkLeft[i] = stereoPcm[2 * src]
                            chunkRight[i] = stereoPcm[2 * src + 1]
                        }
                    }
                    val vocals = runChunk(session, chunkLeft, chunkRight, stft)
                    val overlapWindow = hammingWindow(chunkSamples)
                    for (i in 0 until chunkSamples) {
                        val idx = start + i
                        if (idx < totalSamples) {
                            outputLeft[idx] += vocals[2 * i] * overlapWindow[i]
                            outputRight[idx] += vocals[2 * i + 1] * overlapWindow[i]
                            weights[idx] += overlapWindow[i] * overlapWindow[i]
                        }
                    }
                    progress?.invoke((chunkIndex + 1).toFloat() / chunkCount)
                }
                for (i in 0 until totalSamples) {
                    if (weights[i] > 1e-9f) {
                        outputLeft[i] /= weights[i]
                        outputRight[i] /= weights[i]
                    }
                }
                val vocalsMono = FloatArray(totalSamples) { i -> 0.5f * (outputLeft[i] + outputRight[i]) }
                val instrumental = FloatArray(totalSamples) { i -> stereoPcm[2 * i] - vocalsMono[i] }
                val monoStereo = FloatArray(2 * totalSamples) { i ->
                    if (i < totalSamples) vocalsMono[i] else instrumental[i - totalSamples]
                }
                SeparationResult(
                    vocals = monoStereo.copyOfRange(0, totalSamples),
                    instrumental = monoStereo.copyOfRange(totalSamples, 2 * totalSamples),
                    sampleRateHz = 44100,
                    durationMs = totalSamples.toLong() * 1000L / 44100,
                )
            } finally {
                session.close()
            }
        }.let { result ->
            when (result) {
                is AppResult.Success -> result
                is AppResult.Failure -> AppResult.Failure(
                    AppError.Inference(result.error.message, result.error.cause)
                )
            }
        }
    }

    private fun runChunk(
        session: ai.onnxruntime.OrtSession,
        chunkLeft: FloatArray,
        chunkRight: FloatArray,
        stft: Stft,
    ): FloatArray {
        val stftLeft = stft.transform(chunkLeft)
        val stftRight = stft.transform(chunkRight)
        val numFrames = EXPECTED_FRAMES.coerceAtMost(stftLeft.size)
        val numBins = stft.numBins
        // Pad STFT frames with zeros if the chunk produced fewer than
        // EXPECTED_FRAMES (which only happens for the trailing chunk
        // when totalSamples < chunkSamples).
        val paddedLeft = Array(EXPECTED_FRAMES) { FloatArray(2 * numBins) }
        val paddedRight = Array(EXPECTED_FRAMES) { FloatArray(2 * numBins) }
        for (f in 0 until numFrames) {
            for (k in 0 until numBins) {
                val re = stftLeft[f][k * 2]
                val im = -stftLeft[f][k * 2 + 1]
                paddedLeft[f][2 * k] = re
                paddedLeft[f][2 * k + 1] = im
                val re2 = stftRight[f][k * 2]
                val im2 = -stftRight[f][k * 2 + 1]
                paddedRight[f][2 * k] = re2
                paddedRight[f][2 * k + 1] = im2
            }
        }
        val inputTensor = buildInputTensor(paddedLeft, paddedRight)
        val outputs = session.run(mapOf(INPUT_NAME to inputTensor))
        inputTensor.close()
        val outTensor = outputs[0] as? OnnxTensor
            ?: error("roformer produced no ${OUTPUT_NAME} tensor")
        val raw = outTensor.value as Array<*>
        outTensor.close()
        // Output shape [1, 2050, 1101, 2] with packed index
        // `2 * freq + channel`. Channel 0 = left, channel 1 = right.
        val firstBatch = raw[0] as Array<Array<FloatArray>>
        val maskLeftRe = FloatArray(EXPECTED_FRAMES * numBins)
        val maskLeftIm = FloatArray(EXPECTED_FRAMES * numBins)
        val maskRightRe = FloatArray(EXPECTED_FRAMES * numBins)
        val maskRightIm = FloatArray(EXPECTED_FRAMES * numBins)
        for (f in 0 until EXPECTED_FRAMES) {
            for (k in 0 until numBins) {
                val leftRow = firstBatch[2 * k][f]
                val rightRow = firstBatch[2 * k + 1][f]
                val idx = f * numBins + k
                maskLeftRe[idx] = leftRow[0]
                maskLeftIm[idx] = leftRow[1]
                maskRightRe[idx] = rightRow[0]
                maskRightIm[idx] = rightRow[1]
            }
        }
        // Apply mask: vocals_stft = mask * input_stft (complex multiply)
        val vocalsLeftPacked = Array(EXPECTED_FRAMES) { FloatArray(2 * numBins) }
        val vocalsRightPacked = Array(EXPECTED_FRAMES) { FloatArray(2 * numBins) }
        for (f in 0 until EXPECTED_FRAMES) {
            for (k in 0 until numBins) {
                val idx = f * numBins + k
                val re = paddedLeft[f][2 * k]
                val im = paddedLeft[f][2 * k + 1]
                val mr = maskLeftRe[idx]
                val mi = maskLeftIm[idx]
                vocalsLeftPacked[f][2 * k] = re * mr - im * mi
                vocalsLeftPacked[f][2 * k + 1] = re * mi + im * mr
                val re2 = paddedRight[f][2 * k]
                val im2 = paddedRight[f][2 * k + 1]
                val mr2 = maskRightRe[idx]
                val mi2 = maskRightIm[idx]
                vocalsRightPacked[f][2 * k] = re2 * mr2 - im2 * mi2
                vocalsRightPacked[f][2 * k + 1] = re2 * mi2 + im2 * mr2
            }
        }
        val vocalsLeft = stft.inverse(vocalsLeftPacked, chunkLeft.size)
        val vocalsRight = stft.inverse(vocalsRightPacked, chunkRight.size)
        val stereo = FloatArray(chunkLeft.size * 2)
        for (i in chunkLeft.indices) {
            stereo[2 * i] = vocalsLeft[i]
            stereo[2 * i + 1] = vocalsRight[i]
        }
        return stereo
    }

    private fun buildInputTensor(
        left: Array<FloatArray>,
        right: Array<FloatArray>,
    ): OnnxTensor {
        // Layout: outer bins × 2 channels (interleaved as
        // 2 * freq + channel), inner real/imag. The 2 channels map
        // to left=0, right=1.
        val numBins = left[0].size / 2
        val floats = FloatArray(EXPECTED_FRAMES * 2 * numBins * 2)
        var i = 0
        for (f in 0 until EXPECTED_FRAMES) {
            for (k in 0 until numBins) {
                floats[i++] = left[f][2 * k]
                floats[i++] = left[f][2 * k + 1]
                floats[i++] = right[f][2 * k]
                floats[i++] = right[f][2 * k + 1]
            }
        }
        val buffer = ByteBuffer.allocateDirect(floats.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(floats)
        buffer.rewind()
        return OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1, (2 * numBins).toLong(), EXPECTED_FRAMES.toLong(), 2L),
        )
    }

    private fun hammingWindow(size: Int): FloatArray {
        val out = FloatArray(size)
        for (i in 0 until size) {
            out[i] = (0.54 - 0.46 * cos(2.0 * Math.PI * i / (size - 1))).toFloat()
        }
        return out
    }

    companion object {
        private const val TAG = "RoformerSeparator"

        const val N_FFT: Int = 2048
        const val HOP: Int = 441
        const val EXPECTED_FRAMES: Int = 1101
        const val STEP_FRAMES: Int = 800 // 8 s step (~3 s overlap)

        const val INPUT_NAME: String = "stft_repr"
        const val OUTPUT_NAME: String = "masks"
    }
}
