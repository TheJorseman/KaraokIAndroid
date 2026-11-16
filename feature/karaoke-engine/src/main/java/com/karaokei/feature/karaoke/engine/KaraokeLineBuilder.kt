package com.karaokei.feature.karaoke.engine

import com.karaokei.core.common.transcript.TranscriptDocument
import com.karaokei.core.common.transcript.TranscriptSegment
import com.karaokei.core.common.transcript.TranscriptWord

/**
 * Line builder for [KaraokeDocument]. Takes the segment-based
 * transcript emitted by Whisper and groups words into display lines.
 *
 * Heuristics:
 *  - Respect existing segment boundaries; Whisper already splits on
 *    pauses.
 *  - Within a segment, prefer a target of 6–10 words per line and
 *    never more than 14. Long segments are split at the closest
 *    comma / breath mark.
 *  - Line start = first word start. Line end = last word end.
 *  - When a word list is empty, fall back to the segment text on a
 *    single line.
 */
object KaraokeLineBuilder {

    private const val MIN_WORDS_PER_LINE: Int = 4
    private const val TARGET_WORDS_PER_LINE: Int = 8
    internal const val MAX_WORDS_PER_LINE: Int = 14
    private val SPLIT_MARKERS = setOf(',', '—', '–', ';')

    /**
     * Whisper hallucinates music/noise annotations on instrumental
     * passages ("( Música )", "[Music]", "♪"). They are not lyrics and
     * would render as fake karaoke lines, so drop whole segments whose
     * text is only such an annotation.
     */
    internal val NOISE_SEGMENT: Regex =
        Regex("""\W*[\(\[]?\s*(m[úu]sica|music|applause|aplausos|llanto|ruido|silencio|♪+|♫+)\s*[\)\]]?\s*\W*""", RegexOption.IGNORE_CASE)

    fun build(transcript: TranscriptDocument): KaraokeDocument {
        val lines = transcript.segments
            .filterNot { NOISE_SEGMENT.matches(it.text.trim()) }
            .flatMap { segment -> linesFor(segment) }
            .mapNotNull { it.normalized() }
            .filterConsecutiveDuplicates()
        return KaraokeDocument(
            songId = transcript.songId,
            language = transcript.language,
            duration = transcript.duration,
            lines = lines,
        )
    }

    /**
     * Display cleanup for one line: trim/collapse word whitespace,
     * drop symbol-only lines ("♪", "…"), and capitalize the first
     * letter so lines read like lyrics instead of raw decoder output.
     */
    private fun KaraokeLine.normalized(): KaraokeLine? {
        val words = words.mapNotNull { w ->
            val clean = w.text.replace(WHITESPACE, " ").trim()
            if (clean.isEmpty()) null else w.copy(text = clean)
        }
        if (words.isEmpty()) return null
        if (words.all { w -> w.text.none { it.isLetterOrDigit() } }) return null
        val first = words.first()
        val capitalized = first.copy(
            text = first.text.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
            },
        )
        return copy(words = listOf(capitalized) + words.drop(1))
    }

    /** Whisper occasionally repeats an identical line back to back. */
    private fun List<KaraokeLine>.filterConsecutiveDuplicates(): List<KaraokeLine> =
        fold(mutableListOf<KaraokeLine>()) { acc, line ->
            val sameAsPrevious = acc.lastOrNull()?.let {
                it.words.map(KaraokeWord::text) == line.words.map(KaraokeWord::text)
            } == true
            if (!sameAsPrevious) acc.add(line)
            acc
        }

    private val WHITESPACE = Regex("\\s+")

    private fun linesFor(segment: TranscriptSegment): List<KaraokeLine> {
        if (segment.words.isEmpty()) {
            return listOf(
                KaraokeLine(
                    start = segment.start,
                    end = segment.end,
                    words = listOf(
                        KaraokeWord(
                            text = segment.text.trim(),
                            start = segment.start,
                            end = segment.end,
                            confidence = segment.confidence,
                        ),
                    ),
                ),
            )
        }
        val words = segment.words
        if (words.size <= MAX_WORDS_PER_LINE) {
            return listOf(words.toLine(segment))
        }
        val result = mutableListOf<KaraokeLine>()
        var current = mutableListOf<TranscriptWord>()
        for (w in words) {
            current += w
            val endsWithMarker = w.text.trimEnd().lastOrNull() in SPLIT_MARKERS
            val reachesTarget = current.size >= TARGET_WORDS_PER_LINE
            val exceedsMax = current.size >= MAX_WORDS_PER_LINE
            if (endsWithMarker || reachesTarget || exceedsMax) {
                result += current.toLine(segment)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) {
            // Pad with a synthetic tail if the last group is too short
            // (better than a lonely 1–3 word trailing line).
            val last = result.removeLastOrNull() ?: return emptyList()
            val merged = last.words + current.toLine(segment).words
            result += KaraokeLine(
                start = merged.first().start,
                end = merged.last().end,
                words = merged,
            )
        }
        if (result.isEmpty()) return listOf(words.toLine(segment))
        if (result.first().words.size < MIN_WORDS_PER_LINE && result.size > 1) {
            val first = result.removeAt(0)
            val second = result.removeAt(0)
            val merged = first.words + second.words
            result.add(0, KaraokeLine(merged.first().start, merged.last().end, merged))
        }
        return result
    }

    private fun List<TranscriptWord>.toLine(segment: TranscriptSegment): KaraokeLine {
        val karaokeWords = map { it.toKaraoke() }
        return KaraokeLine(
            start = first().start,
            end = last().end,
            words = karaokeWords,
        )
    }

    private fun TranscriptWord.toKaraoke(): KaraokeWord = KaraokeWord(
        text = text,
        start = start,
        end = end,
        confidence = confidence,
    )
}
