package com.ahdownload.domain.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class StreamingDownloadEngine(
    private val source: DownloadByteStream,
    private val sink: AtomicFileSink,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
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

        var partialBytes = runCatching { sink.temporarySize(task.destinationPath) }
            .getOrElse {
                onState(DownloadState.Failed(DownloadFailure.StorageError))
                return
            }

        var response = try {
            source.open(task.sourceUrl, partialBytes, task.requestHeaders)
        } catch (cancelled: CancellationException) {
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (_: IOException) {
            onState(DownloadState.Failed(DownloadFailure.NetworkError))
            return
        } catch (_: Throwable) {
            onState(DownloadState.Failed(DownloadFailure.NetworkError))
            return
        }

        if (partialBytes > 0L && response.statusCode == 200) {
            response.body.close()
            partialBytes = 0L
            response = try {
                source.open(task.sourceUrl, 0L, task.requestHeaders)
            } catch (cancelled: CancellationException) {
                onState(DownloadState.Cancelled)
                throw cancelled
            } catch (_: IOException) {
                onState(DownloadState.Failed(DownloadFailure.NetworkError))
                return
            } catch (_: Throwable) {
                onState(DownloadState.Failed(DownloadFailure.NetworkError))
                return
            }
        }

        if (response.statusCode !in 200..299) {
            response.body.close()
            onState(DownloadState.Failed(DownloadFailure.HttpError(response.statusCode)))
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
        val responseTotalBytes = when {
            response.totalBytes != null -> response.totalBytes
            response.statusCode == 206 && append && response.contentLengthBytes != null ->
                response.contentLengthBytes + partialBytes
            response.statusCode == 206 -> null
            response.contentLengthBytes != null -> response.contentLengthBytes
            else -> null
        }
        // A size measured during candidate validation is authoritative for this source URL.
        // Server headers remain the fallback for sources whose total size is unknown at probe time.
        val totalBytes = task.expectedBytes?.takeIf { it > 0L } ?: responseTotalBytes

        val output = try {
            sink.openTemporary(task.destinationPath, append = append)
        } catch (cancelled: CancellationException) {
            response.body.close()
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (_: Throwable) {
            response.body.close()
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
                            } catch (_: IOException) {
                                throw StorageWriteException()
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
            // Never commit a known-short/overlong transfer as a completed media file.
            // Keep the .part bytes so WorkManager may resume it with a Range request.
            onState(
                DownloadState.Failed(
                    DownloadFailure.IncompleteResponse(
                        expectedBytes = incomplete.expectedBytes,
                        receivedBytes = incomplete.receivedBytes,
                    ),
                ),
            )
            return
        } catch (_: NetworkReadException) {
            onState(DownloadState.Failed(DownloadFailure.NetworkError))
            return
        } catch (_: StorageWriteException) {
            sink.discard(task.destinationPath)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
            return
        } catch (_: IOException) {
            sink.discard(task.destinationPath)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
            return
        } catch (_: Throwable) {
            sink.discard(task.destinationPath)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
            return
        }

        try {
            sink.commit(task.destinationPath)
            onState(DownloadState.Completed)
        } catch (cancelled: CancellationException) {
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (_: Throwable) {
            onState(DownloadState.Failed(DownloadFailure.StorageError))
        }
    }

    private class IncompleteDownloadException(
        val expectedBytes: Long,
        val receivedBytes: Long,
    ) : IOException()
    private class NetworkReadException : IOException()
    private class StorageWriteException : IOException()

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
