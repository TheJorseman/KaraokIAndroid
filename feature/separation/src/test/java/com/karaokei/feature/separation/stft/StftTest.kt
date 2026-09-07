package com.karaokei.feature.separation.stft

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sin

class StftTest {

    @Test fun `numFrames matches centered STFT math`() {
        val stft = Stft(windowSize = 2048, hopSize = 512)
        // 16384 samples -> 1 + 16384/512 = 33 frames with center=True.
        assertThat(stft.numFrames(16384)).isEqualTo(33)
    }

    @Test fun `transform produces expected frame and bin count`() {
        val stft = Stft(windowSize = 1024, hopSize = 256)
        val input = FloatArray(4096) { it.toFloat() / 4096f }
        val spec = stft.transform(input)
        assertThat(spec.size).isEqualTo(stft.numFrames(input.size))
        spec.forEach { frame ->
            assertThat(frame.size).isEqualTo(2 * stft.numBins)
        }
    }

    @Test fun `transform frame count matches RoFormer chunk`() {
        val stft = Stft(windowSize = 2048, hopSize = 441)
        // 112455 samples -> 1 + 112455/441 = 256 frames (RoFormer chunk).
        assertThat(stft.numFrames(112455)).isEqualTo(256)
    }

    @Test fun `inverse round-trip preserves the interior of the signal`() {
        val stft = Stft(windowSize = 2048, hopSize = 441)
        val n = 16384
        val samples = FloatArray(n) { i ->
            (0.5 * sin(2.0 * Math.PI * 440 * i / 44100)).toFloat()
        }
        val spec = stft.transform(samples)
        val recovered = stft.inverse(spec, n)
        assertThat(recovered.size).isEqualTo(n)
        // Center pad crops the first/last pad samples; correlation
        // should still be positive in the interior.
        val pad = stft.windowSize / 2
        var corr = 0.0
        for (i in pad until n - pad) {
            corr += samples[i] * recovered[i]
        }
        assertThat(corr).isGreaterThan(0.0)
    }
}
