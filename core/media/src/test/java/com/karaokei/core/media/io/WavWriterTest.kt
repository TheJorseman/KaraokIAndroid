package com.karaokei.core.media.io

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WavWriterTest {

    // JUnit 4 (the suite standard here) instead of JUnit 5's
    // @TempDir, which is not on the test classpath.
    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test fun `round trip preserves samples`() {
        val output: File = tempFolder.newFile("round.wav")
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
