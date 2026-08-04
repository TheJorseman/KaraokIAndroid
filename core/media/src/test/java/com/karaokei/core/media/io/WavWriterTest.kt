package com.karaokei.core.media.io

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class WavWriterTest {

    @Test fun `round trip preserves samples`(@TempDir dir: Path) {
        val output = File(dir.toFile(), "round.wav")
        val samples = FloatArray(2048) { i -> (i.toFloat() / 2048f) * 0.5f - 0.25f }
        WavWriter.writePcm16Mono(output, samples, sampleRateHz = 16000)
        val read = WavReader.readPcm16Mono(output)
        assertThat(read.size).isEqualTo(samples.size)
        // Allow for the 16-bit quantisation noise (~3e-5).
        for (i in samples.indices) {
            val a = samples[i]
            val b = read[i]
            assertThat(kotlin.math.abs(a - b)).isLessThan(5e-5f)
        }
    }
}
