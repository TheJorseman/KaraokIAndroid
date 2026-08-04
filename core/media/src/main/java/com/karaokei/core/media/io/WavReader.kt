package com.karaokei.core.media.io

import com.karaokei.core.common.audio.PcmFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a 16-bit mono PCM WAV file into a float array in [-1, 1].
 *
 * The internal format of the AI pipeline is 16 kHz / 16-bit / mono
 * (see [PcmFormat]). Files that don't match are decoded best-effort
 * (the header is trusted and the buffer is sized accordingly).
 */
object WavReader {

    fun readPcm16Mono(file: File): FloatArray {
        require(file.exists()) { "wav does not exist: ${file.absolutePath}" }
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "wav too short: ${bytes.size} bytes" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "missing RIFF header" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAVE file" }

        val dataOffset = findDataChunkOffset(bytes)
            ?: throw IllegalStateException("missing data chunk")
        val dataSize = readIntLE(bytes, dataOffset + 4)
        val pcm = ByteBuffer.wrap(bytes, dataOffset + 8, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(dataSize / 2)
        for (i in samples.indices) samples[i] = pcm.short
        return FloatArray(samples.size) { samples[it] / 32768f }
    }

    private fun findDataChunkOffset(bytes: ByteArray): Int? {
        var offset = 12
        while (offset + 8 < bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = readIntLE(bytes, offset + 4)
            if (id == "data") return offset
            offset += 8 + size
        }
        return null
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
