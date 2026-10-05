package com.ahdownload.domain.download

data class DownloadTask(
    val id: String,
    val sourceUrl: String,
    val destinationPath: String,
)
