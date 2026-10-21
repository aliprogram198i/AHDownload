package com.ahdownload.app.settings

import android.content.Context
import com.ahdownload.core.designsystem.AHThemeMode

class ThemePreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )

    fun read(): AHThemeMode {
        // Migrate previously stored System, Indigo Dark, and Dark Tech values.
        // Crystal Blue is now the only supported theme.
        if (preferences.getString(KEY_THEME, null) != AHThemeMode.LIGHT.storageValue) {
            preferences.edit().putString(KEY_THEME, AHThemeMode.LIGHT.storageValue).apply()
        }
        return AHThemeMode.LIGHT
    }

    fun set(mode: AHThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.storageValue).apply()
    }

    private companion object {
        const val PREFS = "ahdownload_appearance_preferences"
        const val KEY_THEME = "theme_mode"
    }
}
