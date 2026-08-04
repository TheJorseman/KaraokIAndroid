package com.karaokei.core.common.hash

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Hex-encoded SHA-256 of the file contents.
 *
 * Used as the canonical `song_id` for the cache key and Room primary key.
 * Computed streaming so it works on multi-gigabyte inputs.
 */
object Sha256 {

    fun ofFile(file: File): String {
        require(file.exists()) { "file does not exist: ${file.absolutePath}" }
        file.inputStream().use { return ofStream(it) }
    }

    fun ofStream(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    fun ofBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String {
        val hex = CharArray(size * 2)
        for ((index, byte) in this.withIndex()) {
            val value = byte.toInt() and 0xFF
            hex[index * 2] = HEX_CHARS[value ushr 4]
            hex[index * 2 + 1] = HEX_CHARS[value and 0x0F]
        }
        return String(hex)
    }

    private const val BUFFER_SIZE = 64 * 1024
    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
