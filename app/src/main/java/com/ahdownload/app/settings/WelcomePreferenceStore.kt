package com.ahdownload.app.settings

import android.content.Context

/**
 * Persists completion of the introductory welcome screen.
 *
 * The old app already persisted an appearance preference on launch. Treat that marker as
 * an existing installation during migration so an app update does not show onboarding to
 * a returning user for the first time.
 */
class WelcomePreferenceStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun hasSeenWelcome(): Boolean {
        if (preferences.contains(KEY_HAS_SEEN_WELCOME)) {
            return preferences.getBoolean(KEY_HAS_SEEN_WELCOME, false)
        }

        // This migration marker prevents a new installation from being mistaken for an
        // old one after ThemePreferenceStore writes its first value during the same launch.
        if (preferences.getBoolean(KEY_MIGRATION_EVALUATED, false)) return false

        val hasLegacyUsageMarker = appContext
            .getSharedPreferences(LEGACY_APPEARANCE_PREFERENCES, Context.MODE_PRIVATE)
            .contains(LEGACY_THEME_KEY)

        preferences.edit().putBoolean(KEY_MIGRATION_EVALUATED, true).apply()
        if (hasLegacyUsageMarker) {
            markWelcomeSeen()
            return true
        }
        return false
    }

    fun markWelcomeSeen() {
        preferences.edit().putBoolean(KEY_HAS_SEEN_WELCOME, true).apply()
    }

    private companion object {
        const val PREFERENCES = "ahdownload_welcome_preferences"
        const val KEY_HAS_SEEN_WELCOME = "has_seen_welcome"
        const val KEY_MIGRATION_EVALUATED = "migration_evaluated"
        const val LEGACY_APPEARANCE_PREFERENCES = "ahdownload_appearance_preferences"
        const val LEGACY_THEME_KEY = "theme_mode"
    }
}
