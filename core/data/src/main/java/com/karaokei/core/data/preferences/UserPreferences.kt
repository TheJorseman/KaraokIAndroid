package com.karaokei.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.karaokei.core.data.db.entity.ModelTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User preferences stored in DataStore.
 *
 * Backed by a single Preferences DataStore named `karaoke_prefs` (see
 * the DI module for its construction). All reads return `Flow` so the
 * UI can react to changes without re-querying.
 */
@Singleton
class UserPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    data class KaraokeStyle(
        val fontSizeSp: Float = 36f,
        val activeColor: String = "#FFFFD740",
        val upcomingColor: String = "#B3E5FC",
        val shadowEnabled: Boolean = true,
    )

    val selectedTier: Flow<ModelTier> = dataStore.data.map { prefs ->
        val raw = prefs[PreferencesKeys.SELECTED_TIER] ?: return@map ModelTier.BALANCED
        runCatching { ModelTier.valueOf(raw) }.getOrDefault(ModelTier.BALANCED)
    }

    val preferredLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[PreferencesKeys.PREFERRED_LANGUAGE] ?: "auto"
    }

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        val raw = prefs[PreferencesKeys.THEME_MODE] ?: return@map ThemeMode.SYSTEM
        runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    val onboardingCompleted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PreferencesKeys.ONBOARDING_COMPLETED] ?: false
    }

    val nonCommercialLicenseAccepted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PreferencesKeys.ACCEPTED_NON_COMMERCIAL_LICENSE] ?: false
    }

    val pipelineAutoStart: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PreferencesKeys.PIPELINE_AUTO_START] ?: true
    }

    val karaokeStyle: Flow<KaraokeStyle> = dataStore.data.map { prefs ->
        KaraokeStyle(
            fontSizeSp = prefs[PreferencesKeys.KARAOKE_FONT_SIZE]?.toFloatOrNull() ?: 36f,
            activeColor = prefs[PreferencesKeys.KARAOKE_ACTIVE_COLOR] ?: "#FFFFD740",
            upcomingColor = prefs[PreferencesKeys.KARAOKE_UPCOMING_COLOR] ?: "#B3E5FC",
            shadowEnabled = prefs[PreferencesKeys.KARAOKE_SHADOW_ENABLED] ?: true,
        )
    }

    suspend fun setSelectedTier(tier: ModelTier) {
        dataStore.edit { it[PreferencesKeys.SELECTED_TIER] = tier.name }
    }

    suspend fun setPreferredLanguage(language: String) {
        dataStore.edit { it[PreferencesKeys.PREFERRED_LANGUAGE] = language }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode.name }
    }

    suspend fun setOnboardingCompleted(value: Boolean) {
        dataStore.edit { it[PreferencesKeys.ONBOARDING_COMPLETED] = value }
    }

    suspend fun setNonCommercialLicenseAccepted(value: Boolean) {
        dataStore.edit { it[PreferencesKeys.ACCEPTED_NON_COMMERCIAL_LICENSE] = value }
    }

    suspend fun setPipelineAutoStart(value: Boolean) {
        dataStore.edit { it[PreferencesKeys.PIPELINE_AUTO_START] = value }
    }

    suspend fun setKaraokeStyle(style: KaraokeStyle) {
        dataStore.edit {
            it[PreferencesKeys.KARAOKE_FONT_SIZE] = style.fontSizeSp.toString()
            it[PreferencesKeys.KARAOKE_ACTIVE_COLOR] = style.activeColor
            it[PreferencesKeys.KARAOKE_UPCOMING_COLOR] = style.upcomingColor
            it[PreferencesKeys.KARAOKE_SHADOW_ENABLED] = style.shadowEnabled
        }
    }
}
