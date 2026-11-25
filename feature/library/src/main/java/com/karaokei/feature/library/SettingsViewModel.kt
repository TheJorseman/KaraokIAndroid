package com.karaokei.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karaokei.core.data.db.entity.ModelTier
import com.karaokei.core.data.preferences.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: UserPreferences,
) : ViewModel() {

    val selectedTier: StateFlow<ModelTier> = preferences.selectedTier
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ModelTier.FAST)

    val preferredLanguage: StateFlow<String> = preferences.preferredLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "auto")

    val lyricsOnlineSync: StateFlow<Boolean> = preferences.lyricsOnlineSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), true)

    val highQualityTranscription: StateFlow<Boolean> = preferences.highQualityTranscription
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), true)

    val karaokeStyle: StateFlow<UserPreferences.KaraokeStyle> = preferences.karaokeStyle
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            UserPreferences.KaraokeStyle(),
        )

    fun setTier(tier: ModelTier) {
        viewModelScope.launch { preferences.setSelectedTier(tier) }
    }

    fun setLanguage(code: String) {
        viewModelScope.launch { preferences.setPreferredLanguage(code) }
    }

    fun setLyricsOnlineSync(enabled: Boolean) {
        viewModelScope.launch { preferences.setLyricsOnlineSync(enabled) }
    }

    fun setHighQualityTranscription(enabled: Boolean) {
        viewModelScope.launch { preferences.setHighQualityTranscription(enabled) }
    }

    fun setKaraokeStyle(style: UserPreferences.KaraokeStyle) {
        viewModelScope.launch { preferences.setKaraokeStyle(style) }
    }
}
