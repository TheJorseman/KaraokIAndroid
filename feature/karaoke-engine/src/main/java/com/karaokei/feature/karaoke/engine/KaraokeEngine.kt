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
 */
class KaraokeEngine(
    private val document: KaraokeDocument,
) {
    private val resolver = KaraokePositionResolver(document)
    private val _state = MutableStateFlow(snapshotFor(0L))
    val state: StateFlow<KaraokeState> = _state.asStateFlow()

    fun onTick(positionMs: Long) {
        _state.value = snapshotFor(positionMs)
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
        val line: KaraokeLine,
    ) : KaraokeState
}
