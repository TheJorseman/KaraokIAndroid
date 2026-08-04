package com.karaokei.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karaokei.core.data.db.entity.SongEntity
import com.karaokei.core.data.repository.SongRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: SongRepository,
) : ViewModel() {

    val songs: StateFlow<List<SongEntity>> = repository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    fun import(uri: android.net.Uri) {
        viewModelScope.launch(EXCEPTION_HANDLER) { repository.import(uri) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private val EXCEPTION_HANDLER = CoroutineExceptionHandler { _, _ -> }
    }
}
