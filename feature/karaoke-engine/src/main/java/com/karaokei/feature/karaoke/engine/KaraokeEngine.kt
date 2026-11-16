package com.karaokei.feature.karaoke.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State holder for the karaoke player. Owns the [KaraokePositionResolver]
 * and exposes a [StateFlow] of [KaraokeState] that the renderer
 * subscribes to.
 *
 * The [onTick] callback is invoked from the player loop (T6.5) on
 * every position update (typically every animation frame). Internally
 * it updates the state without any allocation in the hot path.
 *
 * [setOffsetMs] lets the user nudge the lyrics forward / backward
 * from the player UI when the karaoke timing is off. The offset is
 * clamped to ±5 s and is applied to every word boundary by the
 * resolver.
 */
class KaraokeEngine(
    private val document: KaraokeDocument,
    initialOffsetMs: Int = 0,
) {
    private val resolver = KaraokePositionResolver(document, offsetMs = initialOffsetMs)
    private val _state = MutableStateFlow(snapshotFor(0L))
    val state: StateFlow<KaraokeState> = _state.asStateFlow()

    /** Current per-song timing offset. Negative values delay lyrics. */
    val offsetMs: Int get() = resolver.offsetMs

    fun onTick(positionMs: Long) {
        _state.value = snapshotFor(positionMs)
    }

    fun setOffsetMs(value: Int) {
        resolver.offsetMs = value
    }

    private fun computeLineProgress(line: KaraokeLine, positionMs: Long): Float {
        val startMs = line.start * 1000.0
        val endMs = line.end * 1000.0
        val span = (endMs - startMs).coerceAtLeast(1.0)
        return ((positionMs - startMs) / span).toFloat().coerceIn(0f, 1f)
    }

    private fun snapshotFor(positionMs: Long): KaraokeState {
        return when (val pos = resolver.resolve(positionMs)) {
            is KaraokePositionResolver.Position.None -> upcomingState(positionMs)
            is KaraokePositionResolver.Position.At -> {
                val line = document.lines[pos.line]
                KaraokeState.Active(
                    lineIndex = pos.line,
                    wordIndex = pos.word,
                    wordProgress = pos.wordProgress,
                    line = line,
                    previousLine = document.lines.getOrNull(pos.line - 1),
                    previousLine2 = document.lines.getOrNull(pos.line - 2),
                    nextLine = document.lines.getOrNull(pos.line + 1),
                    nextLine2 = document.lines.getOrNull(pos.line + 2),
                    lineProgress = computeLineProgress(line, positionMs),
                )
            }
        }
    }

    /**
     * During instrumental gaps the position falls outside every line;
     * instead of a blank screen, preview the upcoming line so the
     * singer knows what comes next.
     */
    private fun upcomingState(positionMs: Long): KaraokeState {
        val positionSeconds = positionMs / 1000.0
        val nextIndex = document.lines.indexOfFirst { it.start > positionSeconds }
        if (nextIndex < 0) return KaraokeState.Idle
        return KaraokeState.Upcoming(
            nextLine = document.lines[nextIndex],
            nextLine2 = document.lines.getOrNull(nextIndex + 1),
        )
    }
}

sealed interface KaraokeState {
    data object Idle : KaraokeState

    /** Instrumental gap: the next line is shown as a preview. */
    data class Upcoming(
        val nextLine: KaraokeLine,
        val nextLine2: KaraokeLine? = null,
    ) : KaraokeState
    data class Active(
        val lineIndex: Int,
        val wordIndex: Int,
        val wordProgress: Float,
        val lineProgress: Float,
        val line: KaraokeLine,
        val previousLine: KaraokeLine? = null,
        val previousLine2: KaraokeLine? = null,
        val nextLine: KaraokeLine? = null,
        val nextLine2: KaraokeLine? = null,
    ) : KaraokeState
}
