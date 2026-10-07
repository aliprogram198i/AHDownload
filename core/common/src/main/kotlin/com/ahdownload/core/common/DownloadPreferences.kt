package com.ahdownload.core.common

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

interface DownloadPreferencesProvider {
    fun current(): DownloadPreferences
}
