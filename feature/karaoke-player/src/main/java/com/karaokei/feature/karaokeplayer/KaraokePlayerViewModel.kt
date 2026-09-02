package com.karaokei.feature.karaokeplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karaokei.core.data.cache.SongCacheLayout
import com.karaokei.core.data.db.entity.SongEntity
import com.karaokei.core.data.preferences.UserPreferences
import com.karaokei.core.data.repository.SongRepository
import com.karaokei.core.media.player.KaraokePlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.karaokei.feature.karaoke.engine.KaraokeDocument
import com.karaokei.feature.karaoke.engine.KaraokeEngine
import com.karaokei.feature.karaoke.engine.KaraokeState
import java.io.File
import javax.inject.Inject

data class PlayerUiState(
    val song: SongEntity? = null,
    val karaoke: KaraokeDocument? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
    val lyricsOffsetMs: Int = 0,
)

@HiltViewModel
class KaraokePlayerViewModel @Inject constructor(
    private val songRepository: SongRepository,
    private val player: KaraokePlayer,
    private val cacheLayout: SongCacheLayout,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Live offset so the player UI updates without restarting. */
    val lyricsOffsetMs: StateFlow<Int> =
        userPreferences.lyricsOffsetMs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0,
        )

    private var engine: KaraokeEngine? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun load(songId: String) {
        viewModelScope.launch {
            val song = songRepository.findById(songId)
            if (song == null) {
                _state.update { it.copy(error = "Canción no encontrada") }
                return@launch
            }
            val karaokeFile: File = cacheLayout.karaokeFile(songId)
            if (!karaokeFile.exists()) {
                _state.update { it.copy(song = song, error = "Aún no se ha generado el karaoke") }
                return@launch
            }
            val doc = withContext(Dispatchers.IO) {
                json.decodeFromString(KaraokeDocument.serializer(), karaokeFile.readText())
            }
            val offsetMs = lyricsOffsetMs.value
            engine = KaraokeEngine(doc, initialOffsetMs = offsetMs)
            _state.update {
                it.copy(
                    song = song,
                    karaoke = doc,
                    durationMs = song.durationMs,
                    lyricsOffsetMs = offsetMs,
                )
            }
            val instrumental = cacheLayout.instrumentalFile(songId)
            val source = if (instrumental.exists()) {
                "file://${instrumental.absolutePath}"
            } else {
                song.fileUri
            }
            player.setUri(android.net.Uri.parse(source))
            player.play()
            _state.update { it.copy(isPlaying = true) }
            startTicker()
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying()) {
            player.pause()
            _state.update { it.copy(isPlaying = false) }
        } else {
            player.play()
            _state.update { it.copy(isPlaying = true) }
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        engine?.onTick(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    /**
     * Bump the lyrics offset by [deltaMs] (positive = lyrics come
     * later, i.e. the music "leads" the karaoke view). Persists
     * immediately to DataStore and pushes the change to the engine
     * so the renderer picks it up on the next `onTick`.
     */
    fun nudgeLyricsOffset(deltaMs: Int) {
        val current = lyricsOffsetMs.value
        val next = (current + deltaMs)
            .coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        viewModelScope.launch {
            userPreferences.setLyricsOffsetMs(next)
        }
        engine?.setOffsetMs(next)
        _state.update { it.copy(lyricsOffsetMs = next) }
    }

    private fun startTicker() {
        viewModelScope.launch {
            while (true) {
                val position = player.currentPositionMs()
                engine?.onTick(position)
                _state.update { it.copy(positionMs = position) }
                delay(33L) // ~30 fps; cheap because state is a data class.
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.pause()
    }
}

/**
 * ±5 s is more than enough for any real song. Mirrors the engine
 * module's own constant so the player UI can enforce the same
 * range without depending on the engine module directly.
 */
private const val MAX_OFFSET_MS: Int = 5_000
