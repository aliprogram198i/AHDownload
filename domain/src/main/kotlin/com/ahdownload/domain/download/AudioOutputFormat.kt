package com.ahdownload.domain.download

/**
 * User-visible audio output formats produced by the on-device transcoder.
 *
 * The source codec/container is independent from this choice. AHDownload first
 * obtains a usable media source, then FFmpeg converts its first audio stream to
 * the selected output format.
 */
enum class AudioOutputFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val defaultBitrateKbps: Int? = null,
    val qualityLabel: String,
) {
    Mp3(
        label = "MP3",
        extension = ".mp3",
        mimeType = "audio/mpeg",
        defaultBitrateKbps = 192,
        qualityLabel = "192 kbps",
    ),
    M4a(
        label = "M4A",
        extension = ".m4a",
        mimeType = "audio/mp4",
        defaultBitrateKbps = 192,
        qualityLabel = "AAC · 192 kbps",
    ),
    Aac(
        label = "AAC",
        extension = ".aac",
        mimeType = "audio/aac",
        defaultBitrateKbps = 192,
        qualityLabel = "AAC · 192 kbps",
    ),
    Opus(
        label = "OPUS",
        extension = ".opus",
        mimeType = "audio/ogg",
        defaultBitrateKbps = 128,
        qualityLabel = "128 kbps",
    ),
    Ogg(
        label = "OGG",
        extension = ".ogg",
        mimeType = "audio/ogg",
        defaultBitrateKbps = 192,
        qualityLabel = "Vorbis · 192 kbps",
    ),
    Flac(
        label = "FLAC",
        extension = ".flac",
        mimeType = "audio/flac",
        defaultBitrateKbps = null,
        qualityLabel = "Lossless",
    ),
    Wav(
        label = "WAV",
        extension = ".wav",
        mimeType = "audio/wav",
        defaultBitrateKbps = null,
        qualityLabel = "PCM · 16-bit · 44.1 kHz",
    ),
}
