package com.karaokei.core.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object PreferencesKeys {
    val SELECTED_TIER = stringPreferencesKey("selected_tier")
    val PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val ACCEPTED_NON_COMMERCIAL_LICENSE = booleanPreferencesKey("accepted_noncommercial_license")
    val PIPELINE_AUTO_START = booleanPreferencesKey("pipeline_auto_start")
    val KARAOKE_FONT_SIZE = stringPreferencesKey("karaoke_font_size")
    val KARAOKE_ACTIVE_COLOR = stringPreferencesKey("karaoke_active_color")
    val KARAOKE_UPCOMING_COLOR = stringPreferencesKey("karaoke_upcoming_color")
    val KARAOKE_SHADOW_ENABLED = booleanPreferencesKey("karaoke_shadow_enabled")
    /**
     * Per-song timing offset in milliseconds applied on top of the
     * WordSync timestamps so users can shift the lyrics forward
     * (positive) or backward (negative) when the karaoke timing is
     * off. Defaults to 0.
     */
    val LYRICS_OFFSET_MS = intPreferencesKey("lyrics_offset_ms")
}
