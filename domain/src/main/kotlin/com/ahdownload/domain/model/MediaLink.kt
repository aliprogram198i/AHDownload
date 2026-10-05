package com.ahdownload.domain.model

enum class MediaPlatform {
    YouTube,
    Instagram,
    Facebook,
    TikTok,
    X,
    DirectMedia,
    Unknown,
}

enum class MediaKind {
    Video,
    Audio,
    Image,
    Unknown,
}

data class MediaLink(
    val originalUrl: String,
    val normalizedUrl: String,
    val platform: MediaPlatform,
    val kind: MediaKind,
)
