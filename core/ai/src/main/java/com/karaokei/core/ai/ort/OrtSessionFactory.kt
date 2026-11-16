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
 * The active backend is set by [activeBackend] (default: CPU).
 *
 * Benchmarks on a Snapdragon 8 Gen 3 (SM8650) with HTDemucs FT
 * measured NNAPI ~20% faster than CPU (26.6 s vs 32.5 s per 30 s of
 * audio). Output quality for the non-CPU backends is not yet
 * validated against the CPU reference on real devices, so CPU stays
 * the default until an A/B on the same model confirms NNAPI.
 *
 * QNN (Qualcomm NPU) is documented as a post-MVP item — see
 * `docs/post-mvp.md`. The Qualcomm AI Engine SDK is required and
 * bound by NDA; integrating it would require a custom build of ORT
 * outside of Maven Central.
 */
object OrtSessionFactory {

    enum class Backend {
        AUTO,    // NNAPI first, then XNNPACK, CPU fallback
        XNNPACK, // CPU only, XNNPACK-optimised
        CPU,     // CPU only, plain ORT CPU EP
        NNAPI,   // NNAPI only (falls back to CPU for unsupported nodes)
    }

    @Volatile var activeBackend: Backend = Backend.CPU

    /**
     * Intra-op thread count. Historically fixed at 2 to cap the peak
     * activation footprint on 4–6 GB devices (HTDemucs / RoFormer OOM
     * otherwise). On high-RAM flagships that leaves most of the CPU
     * idle, so the default now adapts to the device.
     */
    @Volatile var intraOpThreads: Int = defaultIntraOpThreads()

    private fun defaultIntraOpThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        // Memory is the binding constraint for the big separation
        // graphs: each intra-op thread keeps its own scratch buffers.
        // <= 6 GB devices keep the conservative 2; bigger devices can
        // afford half the cores (big.LITTLE aware, capped at 6).
        val totalMemBytes = readTotalMemKb() * 1024L
        val highRam = totalMemBytes == 0L || totalMemBytes >= 6L * 1024 * 1024 * 1024
        return if (highRam) (cores / 2).coerceIn(2, 6) else 2
    }

    private fun readTotalMemKb(): Long = runCatching {
        java.io.File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal:") }
                ?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    fun createSessionOptions(environment: OrtEnvironment): AppResult<OrtSession.SessionOptions> =
        createSessionOptions(environment, activeBackend)

    /**
     * Build a session with an explicit [backend] instead of the
     * process-wide [activeBackend]. Used by [MdxNetSeparator] which
     * forces `CPU` because XNNPACK produces `Infinity`/`NaN` for the
     * UVR Karaoke 2 graph on x86_64 (the model's internal activation
     * overflow isn't handled by the fused XNNPACK kernels).
     */
    fun createSessionOptions(environment: OrtEnvironment, backend: Backend): AppResult<OrtSession.SessionOptions> {
        return runCatchingResult {
            val options = OrtSession.SessionOptions()
            // RAM budget: each intra-op thread keeps its own scratch
            // buffers for the graph, so the thread count trades speed
            // against peak activation footprint. See [intraOpThreads].
            options.setIntraOpNumThreads(intraOpThreads)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Memory-pattern optimisation allocates the arena for EVERY
            // node output of the graph up-front (one-shot plan per input
            // shape). For the RoFormer T=1100 graph that measured ~10 GB
            // of RSS on the emulator before the LMKD killed the process,
            // while the true live working set is a fraction of it.
            // Lazy allocation + arena reuse keeps peak RSS bounded at the
            // cost of a small per-run planning overhead.
            options.setMemoryPatternOptimization(false)
            val backends: List<String> = when (backend) {
                // EP registration order = priority: NNAPI takes what it
                // can, XNNPACK picks up the rest, CPU handles whatever
                // is left (implicit ORT fallback).
                Backend.AUTO -> nnapiIfAvailable() + listOf("XNNPACK")
                Backend.XNNPACK -> listOf("XNNPACK")
                Backend.CPU -> listOf("CPU")
                Backend.NNAPI -> listOf("NNAPI")
            }
            backends.forEach { providerName ->
                try {
                    when (providerName) {
                        "XNNPACK" -> options.addXnnpack(mapOf("intra_op_num_threads" to intraOpThreads.toString()))
                        "NNAPI" -> options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                        "CPU" -> Unit // CPU is the ORT default; no explicit registration
                        else -> Unit
                    }
                } catch (t: Throwable) {
                    // Provider not built into this ORT distribution or
                    // refused to register: log it so backend benchmarks
                    // can't silently fall back to CPU.
                    android.util.Log.w(
                        "OrtSessionFactory",
                        "provider $providerName failed to register (${t.message}); falling back to CPU",
                    )
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
