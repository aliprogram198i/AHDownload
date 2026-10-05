package com.ahdownload.domain.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        var response: DownloadResponse? = null
        try {
            response = source.open(task.sourceUrl)

            if (response.statusCode !in 200..299) {
                response.body.close()
                onState(DownloadState.Failed(DownloadFailure.HttpError(response.statusCode)))
                return
            }

            if (response.contentType?.substringBefore(';')?.equals("text/html", ignoreCase = true) == true) {
                response.body.close()
                onState(DownloadState.Failed(DownloadFailure.InvalidResponse))
                return
            }

            withContext(Dispatchers.IO) {
                response.body.use { input ->
                    val output = sink.openTemporary(task.destinationPath)
                    try {
                        val buffer = ByteArray(bufferSize)
                        var downloaded = 0L
                        onState(DownloadState.Downloading(downloaded, response.contentLengthBytes))

                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue

                            output.write(buffer, 0, read)
                            downloaded += read
                            onState(DownloadState.Downloading(downloaded, response.contentLengthBytes))
                        }

                        output.flush()
                    } catch (t: Throwable) {
                        output.close()
                        sink.discard(task.destinationPath)
                        throw t
                    }
                    output.close()
                }
            }

            sink.commit(task.destinationPath)
            onState(DownloadState.Completed)
        } catch (cancelled: CancellationException) {
            sink.discard(task.destinationPath)
            onState(DownloadState.Cancelled)
            throw cancelled
        } catch (t: java.io.IOException) {
            sink.discard(task.destinationPath)
            onState(DownloadState.Failed(DownloadFailure.NetworkError))
        } catch (t: Throwable) {
            sink.discard(task.destinationPath)
            onState(DownloadState.Failed(DownloadFailure.StorageError))
        }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
