package com.karaokei.core.common.io

import java.io.File
import java.io.InputStream
import java.io.OutputStream

object Streams {
    fun copy(from: InputStream, to: OutputStream, bufferSize: Int = 64 * 1024): Long {
        var total = 0L
        val buffer = ByteArray(bufferSize)
        while (true) {
            val read = from.read(buffer)
            if (read <= 0) break
            to.write(buffer, 0, read)
            total += read
        }
        to.flush()
        return total
    }

    fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        return file.delete()
    }
}
