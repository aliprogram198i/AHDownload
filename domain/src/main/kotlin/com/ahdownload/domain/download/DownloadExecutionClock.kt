package com.ahdownload.domain.download

fun interface DownloadExecutionClock {
    fun nowEpochMs(): Long
}

object SystemDownloadExecutionClock : DownloadExecutionClock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}
