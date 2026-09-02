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
    ): AppResult<SeparationResult> = withContext(io) {
        Log.i(TAG, "HtDemucs separate: starting for ${model.id}")
        runCatchingResult {
            val localPath = modelLoader.resolvePath(model).getOrThrow()
            Log.i(TAG, "HtDemucs separate: opening session ${localPath} (${java.io.File(localPath).length()} bytes)")
            val session = environment.createSession(localPath)
            val numStems = stemCount(session)
            val expectedSamples = sampleLength(session)
            Log.i(TAG, "HtDemucs separate: session opened, num_stems=$numStems samples=$expectedSamples")
            require(stereoPcm.size == 2 * expectedSamples) {
                "htdemucs input mismatch: got ${stereoPcm.size / 2} samples, " +
                    "expected $expectedSamples"
            }
            val mixTensor = makeMixTensor(stereoPcm, expectedSamples)
            Log.i(TAG, "HtDemucs separate: tensor built, running inference")
            val outputs = session.run(mapOf("mix" to mixTensor))
            mixTensor.close()
            Log.i(TAG, "HtDemucs separate: inference done, reading stems")
            val stems = readStems(outputs[0] as? OnnxTensor, numStems, expectedSamples)
            session.close()
            val vocalsStem = vocalsStemIndex(model.id, numStems)
            val vocals = flatten(stems, vocalsStem)
            val instrumental = mixMinusVocals(stereoPcm, vocals)
            Log.i(TAG, "HtDemucs separate: vocals=$vocalsStem, mix-minus-vocals produced")
            SeparationResult(
                vocals = vocals,
                instrumental = instrumental,
                sampleRateHz = 44100,
                durationMs = expectedSamples * 1000L / 44100,
            )
        }.let { result ->
            when (result) {
                is AppResult.Success -> result
                is AppResult.Failure -> AppResult.Failure(
                    AppError.Inference(result.error.message, result.error.cause)
                )
            }
        }
    }

    private fun stemCount(session: OrtSession): Int {
        val info = session.inputInfo["mix"] ?: error("htdemucs model missing 'mix' input")
        val shape = (info.info as ai.onnxruntime.TensorInfo).shape
        check(shape.size == 3) { "htdemucs input must be 3D" }
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
        buffer.asFloatBuffer().put(stereo)
        buffer.rewind()
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

    private fun flatten(stems: Array<FloatArray>, index: Int): FloatArray = stems[index]

    private fun mixMinusVocals(mix: FloatArray, vocals: FloatArray): FloatArray {
        val out = FloatArray(mix.size)
        for (i in mix.indices) {
            out[i] = mix[i] - vocals[i]
        }
        return out
    }

    /**
     * Confirmed order for the public models. The probe ran the FP16
     * 4-stem base and only stem index 2 had audible energy on a
     * synthetic mix; that matches vocals for that configuration. The
     * FT-Vocals variant outputs a single stem (vocals) directly.
     */
    private fun vocalsStemIndex(modelId: String, numStems: Int): Int = when {
        numStems == 1 -> 0
        numStems == 4 -> 2
        numStems == 6 -> 0
        else -> numStems - 1
    }

    companion object {
        private const val TAG = "HtDemucsSeparator"
    }
}
