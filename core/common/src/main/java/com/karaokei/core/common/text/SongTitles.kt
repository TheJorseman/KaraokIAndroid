package com.karaokei.core.common.text

/**
 * Presentation helpers for song titles. Imports derive titles from
 * file names, so they arrive looking like `te_juro_que_te_amo` or
 * "03 - Song Name"; clean them up for display.
 */
object SongTitles {

    fun prettify(raw: String): String {
        val cleaned = raw
            .replace(Regex("[_]+"), " ")
            .replace(Regex("\\s*-\\s*\\d+\\s*-"), " ") // "03 - " track prefixes
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return raw
        return cleaned.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
            }
        }
    }
}
