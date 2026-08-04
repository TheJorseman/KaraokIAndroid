package com.karaokei.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karaokei.core.data.db.entity.SongEntity
import com.karaokei.core.data.repository.SongRepository
import com.karaokei.feature.pipeline.PipelineOrchestrator
import com.karaokei.feature.pipeline.PipelineStageName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SongDetailState(
    val song: SongEntity? = null,
    val transientError: String? = null,
)

@HiltViewModel
class SongDetailViewModel @Inject constructor(
    private val songRepository: SongRepository,
    private val orchestrator: PipelineOrchestrator,
) : ViewModel() {

    private val _state = MutableStateFlow(SongDetailState())
    val state: StateFlow<SongDetailState> = _state.asStateFlow()

    val pipelineStage: StateFlow<PipelineStageName> = orchestrator.state
        .map { it.stage }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = PipelineStageName.IDLE,
        )

    fun load(songId: String) {
        viewModelScope.launch {
            songRepository.observeById(songId).collect { song ->
                _state.update { it.copy(song = song) }
            }
        }
    }

    fun invalidate(songId: String) {
        viewModelScope.launch { orchestrator.invalidate(songId) }
    }

    fun retry(songId: String) {
        clearTransientError()
        orchestrator.runAsync(songId)
    }

    fun reportTransientError(message: String) {
        _state.update { it.copy(transientError = message) }
    }

    fun clearTransientError() {
        _state.update { it.copy(transientError = null) }
    }
}
