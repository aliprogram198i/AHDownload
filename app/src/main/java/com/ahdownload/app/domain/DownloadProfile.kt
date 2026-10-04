package com.ahdownload.app.domain

enum class DownloadProfile {
    BALANCED,
    COMPATIBILITY,
    HIGHEST_QUALITY;

    companion object {
        fun from(value: String?): DownloadProfile =
            entries.firstOrNull { it.name == value } ?: BALANCED
    }
}
