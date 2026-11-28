package com.karaokei.feature.karaoke.engine

/**
 * Aligns *real* lyrics (fetched online, with or without LRC
 * timestamps) with the locally generated karaoke document, producing a
 * document that keeps the real text but the local word timings — so
 * the highlight follows the user's own recording.
 *
 * Strategy:
 *  1. Flatten both sides to words and match them with a DTW alignment
 *     over normalized word forms (typos/ASR errors cost 1, matches 0).
 *  2. Matched words take the LOCAL word's start/end — that is the
 *     "sync with what was generated" contract.
 *  3. Unmatched real words (missed by the ASR or wrong in the fetch)
 *     are interpolated between their matched neighbours.
 *  4. When LRC timestamps exist, their median delta against the
 *     matched local anchors is measured as a global offset and used to
 *     place words that have no anchors at all.
 *
 * The result is discarded (null) when the match ratio is too low —
 * wrong lyrics are worse than approximate ones.
 */
object LyricsAligner {

    /** A real lyric line: text plus optional LRC start time (seconds). */
    data class RealLine(val text: String, val startSec: Double?)

    private const val MIN_MATCH_RATIO = 0.35

    fun align(realLines: List<RealLine>, local: KaraokeDocument): KaraokeDocument? {
        if (realLines.isEmpty() || local.lines.isEmpty()) return null

        // ---- flatten real words, remembering their line
        data class RealWord(val text: String, val norm: String, val lineIndex: Int)

        val realWords = ArrayList<RealWord>()
        realLines.forEachIndexed { lineIndex, line ->
            line.text.split(WHITESPACE).forEach { raw ->
                val clean = raw.replace(WHITESPACE, " ").trim()
                if (clean.isNotEmpty()) {
                    realWords += RealWord(clean, normalize(clean), lineIndex)
                }
            }
        }
        if (realWords.isEmpty()) return null

        // ---- flatten local words
        val localWords = ArrayList<KaraokeWord>()
        local.lines.forEach { line -> localWords += line.words }
        val localNorms = localWords.map { normalize(it.text) }

        // ---- DTW match real -> local
        val match = dtwMatch(realWords.map { it.norm }, localNorms)
        val matched = match.count { it >= 0 }
        if (matched.toFloat() / realWords.size < MIN_MATCH_RATIO) return null

        // ---- global offset from LRC (median of per-word deltas)
        val offsets = ArrayList<Double>()
        for ((i, w) in realWords.withIndex()) {
            val li = match[i]
            val lrcStart = realLines[w.lineIndex].startSec ?: continue
            if (li >= 0) offsets += localWords[li].start - lrcStart
        }
        val offset = offsets.sorted().getOrNull(offsets.size / 2) ?: 0.0

        // ---- times: matched -> local, unmatched -> interpolate
        val starts = DoubleArray(realWords.size) { Double.NaN }
        val ends = DoubleArray(realWords.size) { Double.NaN }
        for (i in realWords.indices) {
            val li = match[i]
            if (li >= 0) {
                starts[i] = localWords[li].start
                ends[i] = localWords[li].end
            }
        }
        fillGaps(
            starts = starts,
            ends = ends,
            texts = realWords.map { it.text },
            lineIndex = IntArray(realWords.size) { realWords[it].lineIndex },
            realLines = realLines,
            offset = offset,
        )

        // ---- rebuild lines with the real structure
        val outLines = ArrayList<KaraokeLine>()
        realLines.forEachIndexed { lineIndex, realLine ->
            val words = ArrayList<KaraokeWord>()
            for ((i, w) in realWords.withIndex()) {
                if (w.lineIndex != lineIndex) continue
                words += KaraokeWord(
                    text = w.text,
                    start = starts[i],
                    end = ends[i],
                    confidence = if (match[i] >= 0) 1f else 0.5f,
                )
            }
            if (words.isEmpty()) return@forEachIndexed
            outLines += KaraokeLine(
                start = words.first().start,
                end = words.maxOf { it.end },
                words = words,
            )
        }
        if (outLines.isEmpty()) return null
        rescueCrammedLines(outLines, realLines, offset)
        return KaraokeDocument(
            songId = local.songId,
            language = local.language,
            duration = local.duration,
            lines = outLines,
        )
    }

    /**
     * Fill words without a timing anchor:
     *  - between two anchored words: spread the gap proportionally to
     *    word length;
     *  - outside the anchored range: extend from the nearest anchor
     *    with a ~2.5 words/second estimate, or use the LRC line start
     *    plus the global offset when available.
     */
    private fun fillGaps(
        starts: DoubleArray,
        ends: DoubleArray,
        texts: List<String>,
        lineIndex: IntArray,
        realLines: List<RealLine>,
        offset: Double,
    ) {
        // LRC-based placement for unanchored words: each word gets a
        // slot inside its line's LRC span (this line's start -> the
        // next line's start, or +3 s for the last one), distributed by
        // word length and shifted by the measured global offset. NaN
        // for lines without LRC times. This keeps the shape of the
        // original timing (line pacing and pauses) instead of
        // stretching words uniformly.
        val lrcStart = DoubleArray(texts.size) { Double.NaN }
        val lrcEnd = DoubleArray(texts.size) { Double.NaN }
        val byLine = LinkedHashMap<Int, MutableList<Int>>()
        for (i in texts.indices) byLine.getOrPut(lineIndex[i]) { mutableListOf() }.add(i)
        for ((li, indices) in byLine) {
            val lineStart = realLines.getOrNull(li)?.startSec ?: continue
            val nextStart = realLines.getOrNull(li + 1)?.startSec
            val span = ((nextStart ?: (lineStart + 3.0)) - lineStart).coerceAtLeast(0.3)
            val total = indices.sumOf { texts[it].length.coerceAtLeast(1).toDouble() }
            var acc = 0.0
            for (i in indices) {
                val w = texts[i].length.coerceAtLeast(1).toDouble()
                lrcStart[i] = lineStart + offset + span * (acc / total)
                lrcEnd[i] = lineStart + offset + span * ((acc + w) / total)
                acc += w
            }
        }

        val anchored = starts.indices.filter { !starts[it].isNaN() }
        if (anchored.isEmpty()) {
            // No anchors at all: place purely from LRC (offset-corrected),
            // falling back to a running estimate without LRC times.
            var running = 0.0
            for (i in starts.indices) {
                val est = if (!lrcStart[i].isNaN()) lrcStart[i] else running
                starts[i] = est
                ends[i] = if (!lrcEnd[i].isNaN()) lrcEnd[i] else est + estimateDuration(texts[i])
                running = ends[i]
            }
            return
        }
        val first = anchored.first()
        val last = anchored.last()
        for (i in starts.indices) {
            if (!starts[i].isNaN()) continue
            val prev = (i downTo 0).firstOrNull { !starts[it].isNaN() }
            val next = (i until starts.size).firstOrNull { !starts[it].isNaN() }
            when {
                prev != null && next != null -> {
                    val gapStart = ends[prev]
                    val gapEnd = starts[next]
                    val weights = (prev + 1..next - 1).map { texts[it].length.coerceAtLeast(1).toDouble() }
                    val total = weights.sum().coerceAtLeast(1.0)
                    var t = gapStart
                    for ((k, idx) in (prev + 1 until next).withIndex()) {
                        val span = (gapEnd - gapStart) * (weights[k] / total)
                        starts[idx] = t
                        ends[idx] = t + span
                        t += span
                    }
                }
                prev != null -> {
                    // Trailing words after the last anchor: LRC wins
                    // when available, uniform estimate otherwise.
                    val est = if (!lrcStart[i].isNaN()) lrcStart[i] else ends[prev] + (i - prev) * 0.35
                    starts[i] = est
                    ends[i] = if (!lrcEnd[i].isNaN()) lrcEnd[i] else est + estimateDuration(texts[i])
                }
                else -> {
                    // Leading words before the first anchor: LRC wins
                    // when available — the uniform 0.35 s/word stretch
                    // used to garble the pacing of whole opening
                    // verses whenever the ASR missed the first lines.
                    val est = if (!lrcStart[i].isNaN()) lrcStart[i] else starts[first] - (first - i) * 0.35
                    starts[i] = est
                    ends[i] = if (!lrcEnd[i].isNaN()) lrcEnd[i] else est + estimateDuration(texts[i])
                }
            }
        }
    }

    private fun estimateDuration(text: String): Double =
        (text.length.coerceIn(2, 12)) * 0.06

    /**
     * ASR word times can be badly compressed for a phrase (whisper
     * sometimes packs several seconds of singing into a few tenths).
     * When a line with LRC times ends up occupying less than 25% of
     * its LRC span, its words are re-placed with the LRC pacing so a
     * whole verse never collapses into a flash.
     */
    private fun rescueCrammedLines(
        lines: MutableList<KaraokeLine>,
        realLines: List<RealLine>,
        offset: Double,
    ) {
        for (li in lines.indices) {
            val lrc = realLines.getOrNull(li)?.startSec ?: continue
            val nextLrc = realLines.getOrNull(li + 1)?.startSec
            val lrcSpan = ((nextLrc ?: (lrc + 3.0)) - lrc).coerceAtLeast(0.3)
            val line = lines[li]
            val span = line.end - line.start
            if (span >= 0.25 * lrcSpan) continue
            val total = line.words.sumOf { it.text.length.coerceAtLeast(1).toDouble() }
            var acc = 0.0
            val words = line.words.map { w ->
                val width = w.text.length.coerceAtLeast(1).toDouble()
                val start = lrc + offset + lrcSpan * (acc / total)
                acc += width
                w.copy(start = start, end = lrc + offset + lrcSpan * (acc / total))
            }
            lines[li] = line.copy(
                start = words.first().start,
                end = words.last().end,
                words = words,
            )
        }
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(NON_WORD, "")

    private val NON_WORD = Regex("[^\\p{L}\\p{N}']+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * DTW over words: substitution cost 0 when normalized forms match,
     * 1 otherwise; gaps allowed. Returns, for each word of [a], the
     * matched index in [b] or -1.
     */
    internal fun dtwMatch(a: List<String>, b: List<String>): IntArray {
        val n = a.size
        val m = b.size
        val result = IntArray(n) { -1 }
        if (n == 0 || m == 0) return result
        // cost[i][j] = best path cost to align a[..i] with b[..j]
        val cost = Array(n + 1) { DoubleArray(m + 1) { Double.POSITIVE_INFINITY } }
        cost[0][0] = 0.0
        for (i in 1..n) {
            for (j in 1..m) {
                val sub = if (a[i - 1] == b[j - 1]) 0.0 else 1.0
                cost[i][j] = minOf(cost[i - 1][j - 1] + sub, cost[i - 1][j] + 1.0, cost[i][j - 1] + 1.0)
            }
        }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val sub = if (a[i - 1] == b[j - 1]) 0.0 else 1.0
            when {
                cost[i][j] == cost[i - 1][j - 1] + sub -> {
                    if (sub == 0.0) result[i - 1] = j - 1
                    i--
                    j--
                }
                cost[i][j] == cost[i - 1][j] + 1.0 -> i--
                else -> j--
            }
        }
        return result
    }
}
