package com.karaokei.core.ai.model

import org.junit.Test
import java.io.File

/** Scratch validation against the real HTDemucs model; run manually. */
class RealModelScratchTest {
    @Test
    fun upcastRealModel() {
        // Opt-in integration check: point KARAOKEI_MODELS_DIR at a
        // directory with htdemucs_ft_vocals_fp16weights.onnx to run it.
        val dir = System.getenv("KARAOKEI_MODELS_DIR") ?: run {
            println("SKIP: set KARAOKEI_MODELS_DIR to run the real-model check")
            return
        }
        val src = File(dir, "htdemucs_ft_vocals_fp16weights.onnx")
        if (!src.exists()) {
            println("SKIP: model not found at ${src.absolutePath}")
            return
        }
        val out = File(src.parentFile, "htdemucs_ft_fp32weights_kotlin.onnx")
        val n = OnnxFp32Upcast.upcast(src, out)
        println("converted=$n out=${out.length()} bytes")
        // The reference (python) conversion must be byte-comparable in size.
        val ref = File(src.parentFile, "htdemucs_ft_fp32weights.onnx")
        if (ref.exists()) {
            println("python out=${ref.length()} bytes")
            check(out.length() == ref.length()) { "size mismatch: kotlin=${out.length()} python=${ref.length()}" }
        }
    }
}
