package com.karaokei.core.ai.model

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OnnxFp32UpcastTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private fun fixture(): File {
        val stream = javaClass.classLoader!!.getResourceAsStream("tiny_fp16.onnx")
            ?: error("fixture missing from test resources")
        val out = temp.newFile("tiny_fp16.onnx")
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    @Test fun `detects fp16 initializers`() {
        assertThat(OnnxFp32Upcast.containsFp16Initializers(fixture())).isTrue()
    }

    @Test fun `upcast converts fp16 initializers and keeps the model parseable`() {
        val input = fixture()
        val output = File(temp.root, "tiny_fp32.onnx")
        val converted = OnnxFp32Upcast.upcast(input, output)

        assertThat(converted).isEqualTo(1)
        assertThat(output.exists()).isTrue()
        assertThat(OnnxFp32Upcast.containsFp16Initializers(output)).isFalse()
    }

    @Test fun `half float conversion is exact for normals`() {
        // 1.5 = 0x3E00, -2.25 = 0xC080, 3.0 = 0x4200, 0.0 = 0x0000
        assertThat(bits(OnnxFp32Upcast.halfToFloatBits(0x3E00))).isEqualTo(1.5f)
        assertThat(bits(OnnxFp32Upcast.halfToFloatBits(0xC080))).isEqualTo(-2.25f)
        assertThat(bits(OnnxFp32Upcast.halfToFloatBits(0x4200))).isEqualTo(3.0f)
        assertThat(bits(OnnxFp32Upcast.halfToFloatBits(0x0000))).isEqualTo(0.0f)
        // subnormal half (0x0001) = 2^-24
        assertThat(bits(OnnxFp32Upcast.halfToFloatBits(0x0001))).isWithin(1e-12f).of(5.9604645e-8f)
        // +inf
        assertThat(OnnxFp32Upcast.halfToFloatBits(0x7C00)).isEqualTo(0x7F800000)
    }

    private fun bits(b: Int): Float = Float.fromBits(b)
}
