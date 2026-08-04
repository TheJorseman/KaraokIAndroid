package com.karaokei.core.ai.ort

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.getOrThrow
import com.karaokei.core.common.result.runCatchingResult
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Manages the lifetime of an ONNX Runtime session.
 *
 * Important: only one session should be alive at a time across the
 * app (T3.7). Callers should scope this object within a coroutine
 * block, run their inference, and let it close as soon as possible.
 */
class OrtSessionHandle private constructor(
    val session: OrtSession,
    val environment: OrtEnvironment,
) : Closeable {

    private var closed = false

    fun run(inputName: String, buffer: FloatBuffer, shape: LongArray): AppResult<FloatArray> {
        check(!closed) { "session is closed" }
        return runCatchingResult {
            val tensor = OnnxTensor.createTensor(environment, buffer, shape)
            try {
                val outputs = session.run(mapOf(inputName to tensor))
                @Suppress("UNCHECKED_CAST")
                val firstOutput = outputs[0].value as Array<Array<FloatArray>>
                firstOutput[0][0]
            } finally {
                tensor.close()
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

    override fun close() {
        if (closed) return
        closed = true
        runCatching { session.close() }
        // Note: we do NOT close the OrtEnvironment here because it is
        // a process-wide singleton. Closing it would invalidate every
        // other session.
    }

    companion object {

        fun open(modelBytes: ByteArray): AppResult<OrtSessionHandle> = runCatchingResult {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSessionFactory.createSessionOptions(environment).getOrThrow()
            val session = environment.createSession(modelBytes, options)
            OrtSessionHandle(session, environment)
        }.let { result ->
            when (result) {
                is AppResult.Success -> result
                is AppResult.Failure -> AppResult.Failure(
                    AppError.Model("failed to open ORT session: ${result.error.message}", result.error.cause)
                )
            }
        }

        fun openFile(modelPath: String): AppResult<OrtSessionHandle> = runCatchingResult {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSessionFactory.createSessionOptions(environment).getOrThrow()
            OrtSessionHandle(environment.createSession(modelPath, options), environment)
        }.let { result ->
            when (result) {
                is AppResult.Success -> result
                is AppResult.Failure -> AppResult.Failure(
                    AppError.Model("failed to open ORT model file: ${result.error.message}", result.error.cause)
                )
            }
        }
    }
}

/** Convenience: build a 1D float tensor of length [size]. */
fun floatTensor(values: FloatArray, shape: LongArray): OnnxTensor {
    val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()
    buffer.put(values)
    buffer.position(0)
    return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), buffer, shape)
}

/** Convenience: float buffer ready for OnnxTensor creation. */
fun floatBuffer(values: FloatArray): FloatBuffer {
    val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()
    buffer.put(values)
    buffer.position(0)
    return buffer
}
