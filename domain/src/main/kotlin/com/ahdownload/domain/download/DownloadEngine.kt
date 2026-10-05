package com.ahdownload.domain.download

interface DownloadEngine {
    suspend fun download(
        task: DownloadTask,
        onState: suspend (DownloadState) -> Unit,
    )
}
