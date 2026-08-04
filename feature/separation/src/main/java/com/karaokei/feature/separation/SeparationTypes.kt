package com.karaokei.feature.separation

/**
 * Strategy for loading a separation model. Two production cases:
 *
 *  - **MDX-Net Kim Vocal 2 (Fast)**: ONNX graph expects a 4D
 *    `[1, 4, T, F]` complex spectrogram magnitude input and produces a
 *    mask `[1, 1, T, F]` to be applied per-stem. Internally MDX-Net
 *    uses 4 stems: vocals, drums, bass, other. We sum `drums + bass +
 *    other` to obtain the instrumental.
 *
 *  - **Mel-Band RoFormer (Balanced/HQ)**: ONNX graph expects
 *    `[1, F, T, 2]` real+imag spectrogram and produces the vocals
 *    spectrogram directly. Pipeline is otherwise similar.
 */
enum class SeparationBackend { MDX_NET, MEL_BAND_ROFORMER }

/**
 * Result of a separation run.
 *
 * The vocals buffer is the primary output (used for transcription).
 * The instrumental buffer is the karaoke playback output.
 */
data class SeparationResult(
    val vocals: FloatArray,
    val instrumental: FloatArray,
    val sampleRateHz: Int,
    val durationMs: Long,
)
