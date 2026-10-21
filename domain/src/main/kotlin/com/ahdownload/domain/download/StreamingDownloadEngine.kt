package com.ahdownload.domain.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class StreamingDownloadEngine(
    private val source: DownloadByteStream,
    private val sink: AtomicFileSink,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val onStorageFailure: (stage: String, error: Throwable) -> Unit = { _, _ -> },
) : DownloadEngine {

    init {
        require(bufferSize > 0)
    }

    override suspend fun download(
        task: DownloadTask,
        onState: suspend (DownloadState) -> Unit,
    ) {
        onState(DownloadState.Queued)
        onState(DownloadState.Preparing)

        var partialBytes = try {
            sink.temporarySize(task.destinationPath, task.id).coerceAtLeast(0L)
        } catch (cancelled: CancellationException) {
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (error: Throwable) {
            reportStorageFailure("inspect_partial", error)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
            return
        }

        suspend fun openResponse(rangeStart: Long): DownloadResponse? = try {
            source.open(task.sourceUrl, rangeStart, task.requestHeaders)
        } catch (cancelled: CancellationException) {
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (_: IOException) {
            onState(DownloadState.Failed(DownloadFailure.NetworkError))
            null
        } catch (_: Throwable) {
            onState(DownloadState.Failed(DownloadFailure.NetworkError))
            null
        }

        suspend fun discardPartial(): Boolean = try {
            sink.discard(task.destinationPath, task.id)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            reportStorageFailure("discard_partial", error)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
            false
        }

        var response = openResponse(partialBytes) ?: return

        // A 200 response to a Range request is a full representation; replace the stale part,
        // but reuse this response body instead of issuing a redundant second request.
        if (partialBytes > 0L && response.statusCode == 200) {
            if (!discardPartial()) {
                response.body.close()
                return
            }
            partialBytes = 0L
        } else if (
            partialBytes > 0L &&
            (response.statusCode == 416 ||
                (response.statusCode == 206 && !hasValidContentRange(response, partialBytes)))
        ) {
            response.body.close()
            if (!discardPartial()) return
            partialBytes = 0L
            response = openResponse(0L) ?: return
        }

        if (response.statusCode !in 200..299) {
            response.body.close()
            onState(DownloadState.Failed(DownloadFailure.HttpError(response.statusCode)))
            return
        }

        if (response.statusCode == 206 && !hasValidContentRange(response, partialBytes)) {
            response.body.close()
            onState(DownloadState.Failed(DownloadFailure.InvalidResponse))
            return
        }

        val expectedBytes = task.expectedBytes?.takeIf { it > 0L }
        // Content-Length on a 206 response describes only that response's range, not
        // the complete media object. A range end is not proof of total length when total='*'.
        val declaredResponseTotal = if (response.statusCode == 206) {
            response.totalBytes
        } else {
            response.contentLengthBytes
        }
        if (expectedBytes != null && declaredResponseTotal != null && expectedBytes != declaredResponseTotal) {
            response.body.close()
            if (partialBytes > 0L && !discardPartial()) return
            onState(DownloadState.Failed(DownloadFailure.InvalidResponse))
            return
        }
        if (response.statusCode == 206 && declaredResponseTotal == null && expectedBytes == null) {
            response.body.close()
            if (partialBytes > 0L && !discardPartial()) return
            onState(DownloadState.Failed(DownloadFailure.InvalidResponse))
            return
        }

        if (partialBytes > 0L && response.statusCode != 206) {
            response.body.close()
            onState(DownloadState.Failed(DownloadFailure.InvalidResponse))
            return
        }

        if (response.contentType?.substringBefore(';')?.equals("text/html", ignoreCase = true) == true) {
            response.body.close()
            onState(DownloadState.Failed(DownloadFailure.InvalidResponse))
            return
        }

        val append = partialBytes > 0L && response.statusCode == 206
        val responseTotalBytes = if (response.statusCode == 206) {
            response.totalBytes
        } else {
            response.contentLengthBytes
        }
        val totalBytes = expectedBytes ?: responseTotalBytes

        val output = try {
            sink.openTemporary(task.destinationPath, append = append, transferId = task.id)
        } catch (cancelled: CancellationException) {
            response.body.close()
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (error: Throwable) {
            response.body.close()
            reportStorageFailure("open_temporary", error)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
            return
        }

        try {
            withContext(Dispatchers.IO) {
                response.body.use { input ->
                    output.use { target ->
                        val buffer = ByteArray(bufferSize)
                        var downloaded = partialBytes
                        onState(DownloadState.Downloading(downloaded, totalBytes))

                        while (true) {
                            val read = try {
                                input.read(buffer)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: IOException) {
                                throw NetworkReadException()
                            }

                            if (read < 0) {
                                if (response.statusCode == 206) {
                                    val rangeStart = response.rangeStartBytes
                                        ?: throw InvalidRangeBodyException()
                                    val rangeEnd = response.rangeEndBytes
                                        ?: throw InvalidRangeBodyException()
                                    val declaredRangeBytes = rangeEnd - rangeStart + 1L
                                    val actualRangeBytes = downloaded - partialBytes
                                    if (declaredRangeBytes <= 0L || actualRangeBytes > declaredRangeBytes) {
                                        throw InvalidRangeBodyException()
                                    }
                                }
                                if (totalBytes != null && downloaded != totalBytes) {
                                    throw IncompleteDownloadException(
                                        expectedBytes = totalBytes,
                                        receivedBytes = downloaded,
                                    )
                                }
                                break
                            }
                            if (read == 0) continue

                            try {
                                target.write(buffer, 0, read)
                            } catch (error: IOException) {
                                throw StorageWriteException(error)
                            }

                            downloaded += read
                            onState(DownloadState.Downloading(downloaded, totalBytes))
                        }

                        target.flush()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (incomplete: IncompleteDownloadException) {
            // Never commit a known-short transfer as a completed media file.
            // Keep valid prefix bytes so WorkManager may resume with a matching Range request.
            onState(
                DownloadState.Failed(
                    DownloadFailure.IncompleteResponse(
                        expectedBytes = incomplete.expectedBytes,
                        receivedBytes = incomplete.receivedBytes,
                    ),
                ),
            )
            return
        } catch (_: InvalidRangeBodyException) {
            discardPartialBestEffort(task, onState)
            onState(DownloadState.Failed(DownloadFailure.InvalidResponse))
            return
        } catch (_: NetworkReadException) {
            onState(DownloadState.Failed(DownloadFailure.NetworkError))
            return
        } catch (error: StorageWriteException) {
            reportStorageFailure("write", error.cause ?: error)
            discardPartialBestEffort(task, onState)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
            return
        } catch (error: IOException) {
            reportStorageFailure("flush_or_close", error)
            discardPartialBestEffort(task, onState)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
            return
        } catch (cancelled: CancellationException) {
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (error: Throwable) {
            reportStorageFailure("stream_output", error)
            discardPartialBestEffort(task, onState)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
            return
        }

        val completedTempBytes = try {
            sink.temporarySize(task.destinationPath, task.id)
        } catch (cancelled: CancellationException) {
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (error: Throwable) {
            reportStorageFailure("verify_temporary_size", error)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
            return
        }
        if (completedTempBytes <= 0L) {
            discardPartialBestEffort(task, onState)
            onState(DownloadState.Failed(DownloadFailure.InvalidResponse))
            return
        }

        try {
            sink.commit(task.destinationPath, task.id)
            onState(DownloadState.Completed)
        } catch (cancelled: CancellationException) {
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (error: Throwable) {
            reportStorageFailure("commit", error)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
        }
    }

    private fun reportStorageFailure(stage: String, error: Throwable) {
        // Diagnostic failures must never turn a recoverable transfer error into a worker crash.
        runCatching { onStorageFailure(stage, error) }
    }

    private suspend fun discardPartialBestEffort(
        task: DownloadTask,
        onState: suspend (DownloadState) -> Unit,
    ) {
        try {
            sink.discard(task.destinationPath, task.id)
        } catch (cancelled: CancellationException) {
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (error: Throwable) {
            reportStorageFailure("discard_partial", error)
            // Preserve the primary failure reason; the diagnostic records the cleanup issue.
        }
    }

    private fun hasValidContentRange(response: DownloadResponse, requestedStart: Long): Boolean {
        if (response.statusCode != 206) return true
        val start = response.rangeStartBytes ?: return false
        val end = response.rangeEndBytes ?: return false
        if (start != requestedStart || end < start) return false
        if (response.contentLengthBytes != null) {
            val rangeLength = end - start + 1L
            if (rangeLength <= 0L || response.contentLengthBytes != rangeLength) return false
        }
        val total = response.totalBytes
        // A server may honor an open-ended range in bounded chunks. The declared total
        // is still authoritative; the engine can resume the remaining suffix on retry.
        if (total != null && total <= end) return false
        return true
    }

    private class IncompleteDownloadException(
        val expectedBytes: Long,
        val receivedBytes: Long,
    ) : IOException()
    private class InvalidRangeBodyException : IOException()
    private class NetworkReadException : IOException()
    private class StorageWriteException(cause: Throwable) : IOException(cause)

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
