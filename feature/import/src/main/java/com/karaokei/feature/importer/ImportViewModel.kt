package com.karaokei.feature.importer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.data.repository.SongRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImportUiState(
    val isImporting: Boolean = false,
    val importedSongId: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val repository: SongRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun import(uri: Uri) {
        if (_state.value.isImporting) return
        _state.update { it.copy(isImporting = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.import(uri)) {
                is AppResult.Success -> _state.update {
                    it.copy(isImporting = false, importedSongId = result.value.entity.id)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(isImporting = false, error = result.error.message)
                }
            }
        }
    }
}
