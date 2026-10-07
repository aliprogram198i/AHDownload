package com.ahdownload.app.settings

import android.content.Context
import com.ahdownload.core.common.AudioBitratePreference
import com.ahdownload.core.common.DownloadPreferences
import com.ahdownload.core.common.DownloadPreferencesProvider
import com.ahdownload.core.common.VideoQualityPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DownloadPreferencesStore(context: Context) : DownloadPreferencesProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )
    private val _state = MutableStateFlow(read())
    val state: StateFlow<DownloadPreferences> = _state.asStateFlow()

    override fun current(): DownloadPreferences = _state.value

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
