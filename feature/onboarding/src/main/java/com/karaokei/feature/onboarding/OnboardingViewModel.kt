package com.karaokei.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karaokei.core.data.preferences.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingState(val completed: Boolean = false)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: UserPreferences,
) : ViewModel() {

    val state: StateFlow<OnboardingState> = preferences.onboardingCompleted
        .map { OnboardingState(completed = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = OnboardingState(completed = false),
        )

    fun complete() {
        viewModelScope.launch { preferences.setOnboardingCompleted(true) }
    }
}
