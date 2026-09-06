package com.karaokei.feature.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.getOrThrow
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.data.db.entity.ModelEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ONNX Runtime wrapper for HTDemucs FP16 4-stem / 6-stem and HTDemucs
 * FT-Vocals models.
 *
 * Confirmed contract (see `scripts/models/_probe_htdemucs.py`):
 * - input `mix`:  float32[batch=1, channels=2, samples=343980]
 * - output `stems`: float32[batch=1, num_stems, channels=2, samples]
 *
 * The default is 44.1 kHz and 343980 samples. The audio sent to the
 * model is resampled at the host if the input differs, see
 * [SeparateSongUseCase]. This class only loads the ONNX model and
 * splits the output into `vocals` and `instrumental = mix - vocals`.
 */
@Singleton
class HtDemucsSeparator @Inject constructor(
    private val modelLoader: com.karaokei.core.ai.model.ModelLoader,
    @Dispatcher(KaraokeDispatcher.IO) private val io: CoroutineDispatcher,
) {
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    suspend fun separate(
        stereoPcm: FloatArray,
        model: ModelEntity,
        onProgress: ((Float) -> Unit)? = null,
    ): AppResult<SeparationResult> = withContext(io) {
        Log.i(TAG, "HtDemucs separate: starting for ${model.id}")
        runCatchingResult {
            val localPath = modelLoader.resolvePath(model).getOrThrow()
            Log.i(TAG, "HtDemucs separate: opening session ${localPath} (${java.io.File(localPath).length()} bytes)")
            val session = environment.createSession(localPath)
            try {
                val numStems = stemCount(session)
                val windowSize = sampleLength(session)
                val totalSamples = stereoPcm.size / 2
                require(stereoPcm.size == totalSamples * 2) {
                    "htdemucs input must be interleaved stereo (L-then-R)"
                }
                val vocalsStem = vocalsStemIndex(model.id, numStems)
                Log.i(
                    TAG,
                    "HtDemucs separate: num_stems=$numStems window=$windowSize " +
                        "total=$totalSamples vocalsStem=$vocalsStem",
                )

                // Overlap-add chunking (mirrors the reference
                // `infer.py::separate`): 25% overlap with a linear
                // fade in/out, normalised by the summed window weights.
                val overlap = windowSize / 4
                val stride = windowSize - overlap
                val nChunks = maxOf(1, (totalSamples + stride - 1) / stride)
                val window = makeWindow(windowSize, overlap)
                Log.i(TAG, "HtDemucs separate: $nChunks chunk(s), overlap=$overlap stride=$stride")

                val vocalsOut = FloatArray(totalSamples * 2)
                val weight = FloatArray(totalSamples)

                for (chunkIndex in 0 until nChunks) {
                    val start = chunkIndex * stride
                    val end = minOf(start + windowSize, totalSamples)
                    val chunkLen = end - start
                    val chunk = extractStereoChunk(stereoPcm, totalSamples, start, chunkLen, windowSize)
                    val mixTensor = makeMixTensor(chunk, windowSize)
                    val outputs = session.run(mapOf("mix" to mixTensor))
                    mixTensor.close()
                    val stems = readStems(outputs[0] as? OnnxTensor, numStems, windowSize)
                    val vocalsChunk = stems[vocalsStem]
                    for (j in 0 until chunkLen) {
                        val w = window[j]
                        val outIdx = start + j
                        vocalsOut[outIdx] += vocalsChunk[j] * w
                        vocalsOut[totalSamples + outIdx] += vocalsChunk[windowSize + j] * w
                        weight[outIdx] += w
                    }
                    onProgress?.invoke((chunkIndex + 1).toFloat() / nChunks)
                }
                for (i in 0 until totalSamples) {
                    val w = maxOf(weight[i], 1e-8f)
                    vocalsOut[i] /= w
                    vocalsOut[totalSamples + i] /= w
                }
                // Instrumental is recomputed by the caller at 16 kHz mono
                // (`SeparateSongUseCase.htDemucsSeparation`), so avoid the
                // extra full-resolution stereo allocation here.
                Log.i(TAG, "HtDemucs separate: done, vocals=$vocalsStem")
                SeparationResult(
                    vocals = vocalsOut,
                    instrumental = FloatArray(0),
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

    private fun makeWindow(n: Int, overlap: Int): FloatArray {
        val w = FloatArray(n) { 1f }
        for (i in 0 until overlap) {
            val fade = (i + 1).toFloat() / overlap.toFloat()
            w[i] = fade
            w[n - 1 - i] = fade
        }
        return w
    }

    private fun extractStereoChunk(
        stereo: FloatArray,
        totalSamples: Int,
        start: Int,
        chunkLen: Int,
        windowSize: Int,
    ): FloatArray {
        val out = FloatArray(windowSize * 2)
        for (j in 0 until chunkLen) {
            val src = start + j
            out[j] = stereo[src]
            out[windowSize + j] = stereo[totalSamples + src]
        }
        return out
    }

    private fun stemCount(session: OrtSession): Int {
        val info = session.outputInfo.values.firstOrNull()
            ?: error("htdemucs model has no outputs")
        val shape = (info.info as ai.onnxruntime.TensorInfo).shape
        check(shape.size == 4) { "htdemucs output must be 4D" }
        return shape[1].toInt()
    }

    private fun sampleLength(session: OrtSession): Int {
        val info = session.inputInfo["mix"] ?: error("htdemucs model missing 'mix' input")
        val shape = (info.info as ai.onnxruntime.TensorInfo).shape
        return shape[2].toInt()
    }

    private fun makeMixTensor(stereo: FloatArray, samples: Int): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(stereo.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(stereo)
        buffer.position(0)
        return OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1, 2, samples.toLong()),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readStems(
        output: ai.onnxruntime.OnnxTensor?,
        numStems: Int,
        samples: Int,
    ): Array<FloatArray> {
        requireNotNull(output) { "htdemucs model produced no output" }
        val raw = output.value as Array<*>
        val first = raw[0] as Array<*>
        return Array(numStems) { i ->
            val chPair = first[i] as Array<*>
            val c0 = chPair[0] as FloatArray
            val c1 = chPair[1] as FloatArray
            FloatArray(samples * 2).also { out ->
                System.arraycopy(c0, 0, out, 0, samples)
                System.arraycopy(c1, 0, out, samples, samples)
            }
        }
    }

    /**
     * Stem order for the public HTDemucs ONNX models (confirmed by the
     * StemSplitio model cards):
     *
     * - 4-stem: `[drums, bass, other, vocals]`  → vocals = 3
     * - 6-stem: `[drums, bass, other, vocals, guitar, piano]` → vocals = 3
     * - FT-Vocals outputs a single stem (vocals) → index 0
     */
    private fun vocalsStemIndex(modelId: String, numStems: Int): Int = when {
        numStems == 1 -> 0
        numStems == 4 -> 3
        numStems == 6 -> 3
        else -> numStems - 1
    }

    companion object {
        private const val TAG = "HtDemucsSeparator"
    }
}
