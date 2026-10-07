package com.ahdownload.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VideoQualityPreference(val label: String, val height: Int?) {
    AUTO("تلقائي", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
}

enum class AudioBitratePreference(val label: String, val bitrateKbps: Int?) {
    BEST("الأفضل", null),
    KBPS320("320 kbps", 320),
    KBPS256("256 kbps", 256),
    KBPS128("128 kbps", 128),
}

data class DownloadPreferences(
    val videoQuality: VideoQualityPreference = VideoQualityPreference.AUTO,
    val audioBitrate: AudioBitratePreference = AudioBitratePreference.BEST,
    val wifiOnly: Boolean = false,
)

class DownloadPreferencesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )
    private val _state = MutableStateFlow(read())
    val state: StateFlow<DownloadPreferences> = _state.asStateFlow()

    fun current(): DownloadPreferences = _state.value

    fun setVideoQuality(value: VideoQualityPreference) {
        update(_state.value.copy(videoQuality = value))
    }

    fun setAudioBitrate(value: AudioBitratePreference) {
        update(_state.value.copy(audioBitrate = value))
    }

    fun setWifiOnly(value: Boolean) {
        update(_state.value.copy(wifiOnly = value))
    }

    private fun update(value: DownloadPreferences) {
        preferences.edit()
            .putString(KEY_VIDEO, value.videoQuality.name)
            .putString(KEY_AUDIO, value.audioBitrate.name)
            .putBoolean(KEY_WIFI_ONLY, value.wifiOnly)
            .apply()
        _state.value = value
    }

    private fun read(): DownloadPreferences =
        DownloadPreferences(
            videoQuality = preferences.getString(KEY_VIDEO, null)
                ?.let { runCatching { VideoQualityPreference.valueOf(it) }.getOrNull() }
                ?: VideoQualityPreference.AUTO,
            audioBitrate = preferences.getString(KEY_AUDIO, null)
                ?.let { runCatching { AudioBitratePreference.valueOf(it) }.getOrNull() }
                ?: AudioBitratePreference.BEST,
            wifiOnly = preferences.getBoolean(KEY_WIFI_ONLY, false),
        )

    private companion object {
        const val PREFS = "ahdownload_download_preferences"
        const val KEY_VIDEO = "video_quality"
        const val KEY_AUDIO = "audio_bitrate"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}
