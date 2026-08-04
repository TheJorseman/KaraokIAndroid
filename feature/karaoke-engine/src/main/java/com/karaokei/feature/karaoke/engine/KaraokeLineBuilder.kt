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
    private const val MAX_WORDS_PER_LINE: Int = 14
    private val SPLIT_MARKERS = setOf(',', '—', '–', ';')

    fun build(transcript: TranscriptDocument): KaraokeDocument {
        val lines = transcript.segments.flatMap { segment -> linesFor(segment) }
        return KaraokeDocument(
            songId = transcript.songId,
            language = transcript.language,
            duration = transcript.duration,
            lines = lines,
        )
    }

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
