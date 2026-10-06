package com.ahdownload.domain.resolver

import com.ahdownload.domain.model.MediaKind

enum class MediaContainer {
    Mp4,
    Webm,
    Mkv,
    Mov,
    M4a,
    Mp3,
    Aac,
    Ogg,
    Flac,
    ThreeGp,
    Avi,
    Unknown,
}

data class MediaFormat(
    val id: String,
    val kind: MediaKind,
    val container: MediaContainer,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val bitrateKbps: Int? = null,
    val fileSizeBytes: Long? = null,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
)
