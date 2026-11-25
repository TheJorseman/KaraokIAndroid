package com.karaokei.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karaokei.core.data.db.entity.SongEntity
import com.karaokei.core.data.repository.SongRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SongSort { TITLE, RECENT, DURATION }

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

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _sort = MutableStateFlow(SongSort.RECENT)
    val sort: StateFlow<SongSort> = _sort

    /** Songs with the current search filter and sort applied. */
    val visibleSongs: StateFlow<List<SongEntity>> = combine(songs, _query, _sort) { list, rawQuery, sort ->
        val query = rawQuery.trim()
        val filtered = if (query.isEmpty()) list else list.filter { song ->
            song.title.contains(query, ignoreCase = true) ||
                song.artist.orEmpty().contains(query, ignoreCase = true)
        }
        when (sort) {
            SongSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SongSort.RECENT -> filtered // Room already orders by createdAt DESC
            SongSort.DURATION -> filtered.sortedByDescending { it.durationMs }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList(),
    )

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setSort(value: SongSort) {
        _sort.value = value
    }

    fun import(uri: android.net.Uri) {
        viewModelScope.launch(EXCEPTION_HANDLER) { repository.import(uri) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private val EXCEPTION_HANDLER = CoroutineExceptionHandler { _, _ -> }
    }
}
