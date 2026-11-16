package com.karaokei.feature.karaoke.engine

/**
 * Minimal LRC (synced lyrics) parser.
 *
 * Supports the common subset: one or more `[mm:ss.xx]` time tags per
 * line (the same text shared by several stamps), fractional seconds
 * with 1–3 digits, and ignores metadata tags like `[ar:...]`.
 */
object LrcParser {

    data class LrcLine(val startSec: Double, val text: String)

    private val TIME_TAG = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val META_TAG = Regex("""\[[a-zA-Z]+:.*]""")

    fun parse(lrc: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (raw in lrc.lineSequence()) {
            if (META_TAG.matches(raw.trim())) continue
            val stamps = TIME_TAG.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = TIME_TAG.replace(raw, "").trim()
            if (text.isEmpty()) continue
            for (stamp in stamps) {
                val minutes = stamp.groupValues[1].toLong()
                val seconds = stamp.groupValues[2].toLong()
                val fracRaw = stamp.groupValues[3]
                val frac = when {
                    fracRaw.isEmpty() -> 0.0
                    fracRaw.length == 1 -> fracRaw.toLong() / 10.0
                    fracRaw.length == 2 -> fracRaw.toLong() / 100.0
                    else -> fracRaw.toLong() / 1000.0
                }
                out += LrcLine(minutes * 60.0 + seconds + frac, text)
            }
        }
        return out.sortedBy { it.startSec }
    }
}
