package com.ahdownload.app.settings

import android.content.Context

/**
 * Persists completion of the introductory welcome screen.
 *
 * This preference is intentionally independent from appearance and download settings:
 * clearing the app data resets first-run onboarding without coupling it to other stores.
 */
class WelcomePreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun hasSeenWelcome(): Boolean = preferences.getBoolean(KEY_HAS_SEEN_WELCOME, false)

    fun markWelcomeSeen() {
        preferences.edit().putBoolean(KEY_HAS_SEEN_WELCOME, true).apply()
    }

    private companion object {
        const val PREFERENCES = "ahdownload_welcome_preferences"
        const val KEY_HAS_SEEN_WELCOME = "has_seen_welcome"
    }
}
