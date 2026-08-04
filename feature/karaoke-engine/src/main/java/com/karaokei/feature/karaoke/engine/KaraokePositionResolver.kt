package com.karaokei.feature.karaoke.engine

/**
 * Maps a playback position (in milliseconds) to the current line and
 * word in a [KaraokeDocument]. Used by the renderer (T6.x) and the
 * SeekablePlayer (T6.5).
 *
 * Implementation: binary search over `lines` (O(log n)) and a
 * sequential scan inside the matched line. Adequate for any
 * reasonable song length (a 4-minute song has ~50–80 lines).
 */
class KaraokePositionResolver(
    private val document: KaraokeDocument,
) {

    fun resolve(positionMs: Long): Position {
        val positionSeconds = positionMs / 1000.0
        val lineIndex = findLineIndex(positionSeconds)
        if (lineIndex < 0) {
            return Position.None
        }
        val line = document.lines[lineIndex]
        val wordIndex = findWordIndex(line, positionSeconds)
        val wordProgress = wordProgress(line, positionSeconds, wordIndex)
        return Position.At(lineIndex, wordIndex, wordProgress)
    }

    private fun findLineIndex(positionSeconds: Double): Int {
        val lines = document.lines
        if (lines.isEmpty()) return -1
        if (positionSeconds < lines.first().start) return -1
        if (positionSeconds >= lines.last().end) {
            // Hold on the last line; renderer clamps progress.
            return lines.lastIndex
        }
        var lo = 0
        var hi = lines.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val l = lines[mid]
            when {
                positionSeconds < l.start -> hi = mid - 1
                positionSeconds >= l.end -> lo = mid + 1
                else -> return mid
            }
        }
        // Between two lines: snap to the upcoming line.
        return lo.coerceAtMost(lines.lastIndex)
    }

    private fun findWordIndex(line: KaraokeLine, positionSeconds: Double): Int {
        val words = line.words
        if (words.isEmpty()) return -1
        if (positionSeconds < words.first().start) return -1
        for ((i, w) in words.withIndex()) {
            if (positionSeconds in w.start..w.end) return i
        }
        return words.lastIndex
    }

    private fun wordProgress(line: KaraokeLine, positionSeconds: Double, wordIndex: Int): Float {
        if (wordIndex < 0 || wordIndex >= line.words.size) return 0f
        val word = line.words[wordIndex]
        val total = (word.end - word.start).coerceAtLeast(1e-3)
        return ((positionSeconds - word.start) / total).coerceIn(0.0, 1.0).toFloat()
    }

    sealed interface Position {
        data object None : Position
        data class At(val line: Int, val word: Int, val wordProgress: Float) : Position
    }
}
