package com.ahdownload.core.common

enum class VideoQualityPreference(val wireValue: String, val label: String, val maxHeight: Int?) {
    AUTO("auto", "تلقائي", null),
    P2160("2160", "2160p", 2160),
    P1440("1440", "1440p", 1440),
    P1080("1080", "1080p", 1080),
    P720("720", "720p", 720),
    P480("480", "480p", 480),
}

enum class AudioBitratePreference(val kbps: Int, val label: String) {
    AUTO(0, "تلقائي"),
    K320(320, "320 kbps"),
    K256(256, "256 kbps"),
    K192(192, "192 kbps"),
    K128(128, "128 kbps"),
}

data class DownloadPreferences(
    val wifiOnly: Boolean = false,
    val videoQuality: VideoQualityPreference = VideoQualityPreference.AUTO,
    val audioBitrate: AudioBitratePreference = AudioBitratePreference.AUTO,
)

interface DownloadPreferencesProvider {
    fun read(): DownloadPreferences
}
