package com.ahdownload.domain.download

import java.io.OutputStream

interface AtomicFileSink {
    suspend fun openTemporary(destinationPath: String): OutputStream
    suspend fun commit(destinationPath: String)
    suspend fun discard(destinationPath: String)
}
