package com.karaokei.core.ai.ort

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.runCatchingResult
import java.util.EnumSet

/**
 * Builds ONNX Runtime sessions with the right Execution Provider stack.
 *
 * MVP stack:
 *  - XNNPACK (CPU optimised, default).
 *  - NNAPI (legacy fallback, only if XNNPACK is unavailable or fails).
 *
 * QNN (Qualcomm NPU) is documented as a post-MVP item — see
 * `docs/post-mvp.md`. The Qualcomm AI Engine SDK is required and
 * bound by NDA; integrating it would require a custom build of ORT
 * outside of Maven Central.
 */
object OrtSessionFactory {

    /**
     * Order matters: providers earlier in the list are preferred.
     * XNNPACK is always present in modern ORT builds; NNAPI is gated
     * behind an Android API check.
     */
    private val preferredProviders: List<String> = buildList {
        add("XNNPACK")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            add("NNAPI")
        }
    }

    fun createSessionOptions(environment: OrtEnvironment): AppResult<OrtSession.SessionOptions> {
        return runCatchingResult {
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
            preferredProviders.forEach { providerName ->
                try {
                    when (providerName) {
                        "XNNPACK" -> options.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                        "NNAPI" -> options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                        else -> Unit
                    }
                } catch (t: Throwable) {
                    // Provider not built into this ORT distribution; skip silently.
                }
            }
            options
        }.let { result ->
            when (result) {
                is AppResult.Success -> result
                is AppResult.Failure -> AppResult.Failure(
                    AppError.Model("failed to configure ORT providers: ${result.error.message}", result.error.cause)
                )
            }
        }
    }
}
