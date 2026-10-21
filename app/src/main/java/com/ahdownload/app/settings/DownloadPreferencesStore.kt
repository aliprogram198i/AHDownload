package com.ahdownload.app.settings

import android.content.Context
import com.ahdownload.core.common.AudioBitratePreference
import com.ahdownload.core.common.DownloadPreferences
import com.ahdownload.core.common.DownloadPreferencesProvider
import com.ahdownload.core.common.VideoQualityPreference

/**
 * Persists only the active download preferences. Retired preferences are removed during
 * initialization without touching favorites, download history, or diagnostics.
 */
class DownloadPreferencesStore(context: Context) : DownloadPreferencesProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        "ahdownload_download_preferences",
        Context.MODE_PRIVATE,
    )

    init {
        preferences.edit().remove("smart_download").apply()
    }

    override fun read(): DownloadPreferences = DownloadPreferences(
        wifiOnly = preferences.getBoolean("wifi_only", false),
        videoQuality = VideoQualityPreference.entries.firstOrNull {
            it.wireValue == preferences.getString("video_quality", "auto")
        } ?: VideoQualityPreference.AUTO,
        audioBitrate = AudioBitratePreference.entries.firstOrNull {
            it.kbps == preferences.getInt("audio_bitrate", 0)
        } ?: AudioBitratePreference.AUTO,
    )

    fun setWifiOnly(enabled: Boolean) {
        preferences.edit().putBoolean("wifi_only", enabled).apply()
    }

    fun setVideoQuality(value: VideoQualityPreference) {
        preferences.edit().putString("video_quality", value.wireValue).apply()
    }

    fun setAudioBitrate(value: AudioBitratePreference) {
        preferences.edit().putInt("audio_bitrate", value.kbps).apply()
    }
}
