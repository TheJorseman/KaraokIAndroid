package com.karaokei.core.media.extraction

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.karaokei.core.common.audio.PcmFormat
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.media.io.WavWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts the audio of an arbitrary media file into a 16 kHz / 16-bit
 * / mono PCM WAV using the platform MediaExtractor + MediaCodec APIs.
 *
 * Covers mp3, aac, flac, wav, mp4, m4a, mkv (the codecs the platform
 * MediaCodec decodes). For exotic formats the optional
 * `media3-decoder-ffmpeg` extension would be the respaldo (T3.4), pero
 * no se publica en Maven Central; los extractores nativos cubren el MVP.
 *
 * Implementation note: MediaCodec decodes to PCM 16-bit. We then
 * downmix to mono and resample to 16 kHz with a simple linear
 * resampler (buena calidad para speech; la separación IA re-muestrea
 * de todos modos en su pre-procesamiento).
 */
@Singleton
class AudioExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun extractToWav(
        input: Uri,
        output: File,
        startPositionMs: Long = 0L,
        endPositionMs: Long = Long.MAX_VALUE,
    ): AppResult<Long> = runCatchingResult {
        require(output.parentFile?.exists() == true) {
            "output parent dir must exist: ${output.parentFile?.absolutePath}"
        }
        decodeToPcm(input, startPositionMs, endPositionMs).let { (pcm, durationMs, sampleRate) ->
            val mono = downmixToMono(pcm, channelCount = lastChannels)
            val resampled = resampleLinear(mono, sampleRate, PcmFormat.SAMPLE_RATE_HZ)
            WavWriter.writePcm16Mono(output, resampled, PcmFormat.SAMPLE_RATE_HZ)
            durationMs
        }
    }.let { result ->
        when (result) {
            is AppResult.Success -> result
            is AppResult.Failure -> AppResult.Failure(
                AppError.Audio(result.error.message, result.error.cause)
            )
        }
    }

    @Volatile private var lastChannels: Int = 1

    private suspend fun decodeToPcm(
        input: Uri,
        startPositionMs: Long,
        endPositionMs: Long,
    ): Triple<FloatArray, Long, Int> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, input, null)
            val trackIndex = findAudioTrack(extractor)
            require(trackIndex >= 0) { "no audio track found in $input" }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            lastChannels = channels
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()

                if (startPositionMs > 0) {
                    extractor.seekTo(startPositionMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }

                val pcmChunks = ArrayList<FloatArray>()
                var totalSamples = 0
                val info = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                while (!sawOutputEOS) {
                    currentCoroutineContext().ensureActive()
                    if (!sawInputEOS) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            val currentPositionMs = extractor.sampleTime / 1000L
                            if (size < 0 || currentPositionMs > endPositionMs) {
                                sawInputEOS = true
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                val flags = extractor.sampleFlags
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, flags)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outputIndex >= 0 -> {
                            val buffer = codec.getOutputBuffer(outputIndex)!!
                            val floats = decodePcmToFloat(buffer, info, format)
                            if (floats.isNotEmpty()) {
                                pcmChunks.add(floats)
                                totalSamples += floats.size
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEOS = true
                            }
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // El formato de salida puede cambiar tras el primer
                            // buffer; no necesitamos acción especial aquí porque
                            // ya tenemos sampleRate/channels del formato de entrada.
                        }
                    }
                }

                val merged = FloatArray(totalSamples)
                var offset = 0
                for (chunk in pcmChunks) {
                    System.arraycopy(chunk, 0, merged, offset, chunk.size)
                    offset += chunk.size
                }
                return Triple(merged, durationUs / 1000L, sampleRate)
            } finally {
                try { codec.stop() } catch (_: Throwable) {}
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun decodePcmToFloat(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        format: MediaFormat,
    ): FloatArray {
        val encoding = try {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } catch (_: Throwable) {
            ENCODING_PCM_16BIT
        }
        val size = info.size
        if (size <= 0) return FloatArray(0)
        buffer.position(info.offset)
        buffer.limit(info.offset + size)
        return when (encoding) {
            ENCODING_PCM_16BIT -> {
                val out = FloatArray(size / 2)
                val shorts = buffer.asShortBuffer()
                for (i in out.indices) out[i] = shorts.get() / 32768f
                out
            }
            ENCODING_PCM_8BIT -> {
                val out = FloatArray(size)
                for (i in out.indices) out[i] = (buffer.get().toInt() and 0xFF - 128) / 128f
                out
            }
            ENCODING_PCM_FLOAT -> {
                val out = FloatArray(size / 4)
                for (i in out.indices) out[i] = buffer.float
                out
            }
            else -> FloatArray(0)
        }
    }

    private fun downmixToMono(samples: FloatArray, channelCount: Int): FloatArray {
        if (channelCount <= 1) return samples
        val frames = samples.size / channelCount
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channelCount) sum += samples[f * channelCount + c]
            out[f] = sum / channelCount
        }
        return out
    }

    private fun resampleLinear(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outSize = (samples.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outSize)
        for (i in out.indices) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceIn(0, samples.size - 1)
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = samples[i0] * (1f - frac) + samples[i1] * frac
        }
        return out
    }

    companion object {
        private const val TIMEOUT_US: Long = 10_000L
        private const val ENCODING_PCM_16BIT = 2
        private const val ENCODING_PCM_8BIT = 3
        private const val ENCODING_PCM_FLOAT = 4
    }
}
