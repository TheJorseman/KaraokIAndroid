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
 * The available providers are:
 *  - `XNNPACK` — CPU-optimised via XNNPACK, the default fallback.
 *  - `NNAPI`   — Android Neural Networks API. Offloads to the device's
 *                NPU/GPU/DSP when the model graph is supported. Falls
 *                back to CPU otherwise.
 *  - `CPU`     — Vanilla CPU execution. The slowest but most
 *                portable; useful for diagnostic baselines.
 *
 * The active backend is set by [activeBackend] (default: AUTO →
 * XNNPACK with NNAPI as a fallback). The user can override it at
 * runtime via `DebugPipelineTrigger.handleSetBackend("NNAPI" | "CPU"
 * | "XNNPACK" | "AUTO")` to compare precision / latency on a real
 * device.
 *
 * QNN (Qualcomm NPU) is documented as a post-MVP item — see
 * `docs/post-mvp.md`. The Qualcomm AI Engine SDK is required and
 * bound by NDA; integrating it would require a custom build of ORT
 * outside of Maven Central.
 */
object OrtSessionFactory {

    enum class Backend {
        AUTO,    // XNNPACK + NNAPI fallback (production default)
        XNNPACK, // CPU only, XNNPACK-optimised
        CPU,     // CPU only, plain ORT CPU EP
        NNAPI,   // NNAPI only
    }

    @Volatile var activeBackend: Backend = Backend.AUTO

    fun createSessionOptions(environment: OrtEnvironment): AppResult<OrtSession.SessionOptions> {
        return runCatchingResult {
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
            val backends: List<String> = when (activeBackend) {
                Backend.AUTO -> listOf("XNNPACK") + nnapiIfAvailable()
                Backend.XNNPACK -> listOf("XNNPACK")
                Backend.CPU -> listOf("CPU")
                Backend.NNAPI -> listOf("NNAPI")
            }
            backends.forEach { providerName ->
                try {
                    when (providerName) {
                        "XNNPACK" -> options.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                        "NNAPI" -> options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                        "CPU" -> Unit // CPU is the ORT default; no explicit registration
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

    private fun nnapiIfAvailable(): List<String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) listOf("NNAPI")
        else emptyList()
}
