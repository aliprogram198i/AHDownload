package com.ahdownload.domain.download

import java.io.OutputStream

interface AtomicFileSink {
    suspend fun openTemporary(
        destinationPath: String,
        append: Boolean = false,
        transferId: String? = null,
    ): OutputStream

    suspend fun temporarySize(destinationPath: String, transferId: String? = null): Long = 0L
    suspend fun commit(destinationPath: String, transferId: String? = null)
    suspend fun discard(destinationPath: String, transferId: String? = null)
}
