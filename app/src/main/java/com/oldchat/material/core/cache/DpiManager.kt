package com.oldchat.material.core.cache

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

/**
 * Manages display DPI / font scale settings.
 * Mirrors AppDpiManager from original client §2.1 step 2.
 *
 * Modern implementation: instead of modifying system DPI (which requires root
 * or special permissions on modern Android), we manage Compose-level scale factors:
 * - fontScale: global text size multiplier (0.75x - 1.5x)
 * - displayScale: global UI element scale multiplier (0.75x - 1.5x)
 *
 * These scales are exposed via Flow and applied in the theme layer.
 */
class DpiManager(private val preferencesManager: PreferencesManager) {

    /**
     * Global font scale. Default 1.0.
     */
    val fontScale: Flow<Float> = preferencesManager.dataStore.data.map { prefs ->
        prefs[KEY_FONT_SCALE] ?: DEFAULT_SCALE
    }

    suspend fun setFontScale(scale: Float) {
        preferencesManager.dataStore.edit { prefs ->
            prefs[KEY_FONT_SCALE] = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        }
    }

    /**
     * Global display scale. Default 1.0.
     * Affects padding, icon sizes, etc. independently from font.
     */
    val displayScale: Flow<Float> = preferencesManager.dataStore.data.map { prefs ->
        prefs[KEY_DISPLAY_SCALE] ?: DEFAULT_SCALE
    }

    suspend fun setDisplayScale(scale: Float) {
        preferencesManager.dataStore.edit { prefs ->
            prefs[KEY_DISPLAY_SCALE] = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        }
    }

    /**
     * DPI level preset. Default 0 (system default).
     * 0 = system default, 1 = small, 2 = normal, 3 = large, 4 = xlarge
     */
    val dpiLevel: Flow<Int> = preferencesManager.dataStore.data.map { prefs ->
        prefs[KEY_DPI_LEVEL] ?: 0
    }

    suspend fun setDpiLevel(level: Int) {
        preferencesManager.dataStore.edit { prefs ->
            prefs[KEY_DPI_LEVEL] = level.coerceIn(0, 4)
        }
        // Apply corresponding scales
        val (font, display) = dpiLevelToScales(level)
        preferencesManager.dataStore.edit { prefs ->
            prefs[KEY_FONT_SCALE] = font
            prefs[KEY_DISPLAY_SCALE] = display
        }
    }

    /**
     * Convert DPI level preset to scale factors.
     */
    fun dpiLevelToScales(level: Int): Pair<Float, Float> {
        return when (level) {
            1 -> 0.85f to 0.85f   // Small
            2 -> 1.0f to 1.0f     // Normal
            3 -> 1.15f to 1.15f   // Large
            4 -> 1.3f to 1.3f     // XLarge
            else -> 1.0f to 1.0f  // System default
        }
    }

    /**
     * Apply the saved DPI settings on app start.
     * Called from OldChatApplication.onCreate().
     */
    suspend fun apply() {
        // Load saved scales; they will be read by Compose theme
        // This is a no-op in modern implementation — scales are read reactively via flows
    }

    companion object {
        const val DEFAULT_SCALE = 1.0f
        const val MIN_SCALE = 0.75f
        const val MAX_SCALE = 1.5f

        private val KEY_FONT_SCALE = floatPreferencesKey("dpi_font_scale")
        private val KEY_DISPLAY_SCALE = floatPreferencesKey("dpi_display_scale")
        private val KEY_DPI_LEVEL = intPreferencesKey("dpi_level")
    }
}
