package com.ahdownload.domain.download

import java.io.OutputStream

interface AtomicFileSink {
    suspend fun openTemporary(destinationPath: String, append: Boolean = false): OutputStream
    suspend fun temporarySize(destinationPath: String): Long = 0L
    suspend fun commit(destinationPath: String)
    suspend fun discard(destinationPath: String)
}
