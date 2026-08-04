package com.karaokei.core.common.audio

/**
 * Audio format constants used by the AI pipeline.
 *
 * PCM WAV 16-bit, mono, 16 kHz is the internal format for both the
 * separation and transcription stages. Resampling/decoding is done by
 * Media3 (T3.3), not by FFmpeg.
 */
object PcmFormat {
    const val SAMPLE_RATE_HZ: Int = 16_000
    const val CHANNELS: Int = 1
    const val BITS_PER_SAMPLE: Int = 16
    const val BYTES_PER_SAMPLE: Int = BITS_PER_SAMPLE / 8
    const val BYTES_PER_SECOND: Int = SAMPLE_RATE_HZ * CHANNELS * BYTES_PER_SAMPLE
}
