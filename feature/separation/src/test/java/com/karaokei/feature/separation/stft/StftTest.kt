package com.karaokei.feature.separation.stft

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StftTest {

    @Test fun `numFrames matches overlap-add math`() {
        val stft = Stft(windowSize = 2048, hopSize = 512)
        // 16384 samples = exactly 8 hops of 2048; frames = (16384-2048)/512 + 1 = 29.
        assertThat(stft.numFrames(16384)).isEqualTo(29)
    }

    @Test fun `transform produces expected bin count`() {
        val stft = Stft(windowSize = 1024, hopSize = 256)
        val input = FloatArray(4096) { it.toFloat() / 4096f }
        val spec = stft.transform(input)
        assertThat(spec.size).isEqualTo(stft.numFrames(input.size))
        spec.forEach { frame ->
            assertThat(frame).hasLength(2)
        }
    }
}
