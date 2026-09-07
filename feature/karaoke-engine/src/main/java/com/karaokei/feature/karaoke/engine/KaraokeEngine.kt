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
            is KaraokePositionResolver.Position.None -> KaraokeState.Idle
            is KaraokePositionResolver.Position.At -> {
                val line = document.lines[pos.line]
                KaraokeState.Active(
                    lineIndex = pos.line,
                    wordIndex = pos.word,
                    wordProgress = pos.wordProgress,
                    line = line,
                    previousLine = document.lines.getOrNull(pos.line - 1),
                    nextLine = document.lines.getOrNull(pos.line + 1),
                    lineProgress = computeLineProgress(line, positionMs),
                )
            }
        }
    }
}

sealed interface KaraokeState {
    data object Idle : KaraokeState
    data class Active(
        val lineIndex: Int,
        val wordIndex: Int,
        val wordProgress: Float,
        val lineProgress: Float,
        val line: KaraokeLine,
        val previousLine: KaraokeLine? = null,
        val nextLine: KaraokeLine? = null,
    ) : KaraokeState
}
