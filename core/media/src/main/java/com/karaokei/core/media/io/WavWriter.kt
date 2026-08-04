package com.karaokei.core.media.io

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAV writer for 16-bit mono PCM.
 *
 * Used by the AI pipeline to materialize `vocals.wav` and
 * `instrumental.wav` from the float buffers produced by the
 * separation / transcription stages. The internal format is fixed at
 * 16 kHz / 16-bit / mono to match [PcmFormat].
 */
object WavWriter {

    fun writePcm16Mono(
        output: File,
        samples: FloatArray,
        sampleRateHz: Int = 16000,
    ) {
        output.parentFile?.mkdirs()
        val dataBytes = samples.size * 2
        val fileSize = 36 + dataBytes

        RandomAccessFile(output, "rw").use { raf ->
            raf.setLength(0)
            raf.write(buildHeader(sampleRateHz, channels = 1, bitsPerSample = 16, dataBytes, fileSize))
            val buf = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                val clamped = s.coerceIn(-1f, 1f)
                buf.putShort((clamped * 32767f).toInt().toShort())
            }
            raf.write(buf.array())
        }
    }

    private fun buildHeader(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataBytes: Int,
        fileSize: Int,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(fileSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // subchunk1 size
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }
}
