package com.ahdownload.app.settings

import android.content.Context
import com.ahdownload.core.designsystem.AHThemeMode

class ThemePreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )

    fun read(): AHThemeMode =
        AHThemeMode.entries.firstOrNull { mode ->
            mode.storageValue == preferences.getString(KEY_THEME, AHThemeMode.SYSTEM.storageValue)
        } ?: AHThemeMode.SYSTEM

    fun set(mode: AHThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.storageValue).apply()
    }

    private companion object {
        const val PREFS = "ahdownload_appearance_preferences"
        const val KEY_THEME = "theme_mode"
    }
}
