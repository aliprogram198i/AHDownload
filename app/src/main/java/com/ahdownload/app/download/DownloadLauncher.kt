package com.ahdownload.app.download

import android.content.Context
import android.os.Environment
import com.ahdownload.domain.download.DownloadTask
import com.ahdownload.app.settings.DownloadLocationStore
import com.ahdownload.domain.resolver.MediaCandidate
import java.io.File
import java.util.UUID

class DownloadLauncher(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scheduler = (appContext as com.ahdownload.app.AHDownloadApplication).downloadWorkScheduler
    private val locationStore = DownloadLocationStore(appContext)

    fun enqueue(candidate: MediaCandidate, title: String?): Boolean {
        if (locationStore.persistedUri() != null && !locationStore.hasAccessibleCustomLocation()) return false
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
        if (!directory.exists() && !directory.mkdirs()) return false

        val extension = extensionFor(candidate)
        val baseName = sanitize(title).ifBlank { "AHDownload-" + candidate.id }
        val file = uniqueFile(directory, baseName, extension)

        scheduler.enqueue(
            DownloadTask(
                id = UUID.randomUUID().toString(),
                sourceUrl = candidate.sourceUrl,
                destinationPath = file.absolutePath,
                requestHeaders = candidate.requestHeaders.filterKeys { key ->
                    !key.equals("Cookie", ignoreCase = true) &&
                        (key.equals("User-Agent", ignoreCase = true) ||
                            key.equals("Referer", ignoreCase = true) ||
                            key.equals("Origin", ignoreCase = true) ||
                            key.equals("Accept", ignoreCase = true) ||
                            key.equals("Accept-Language", ignoreCase = true) ||
                            key.equals("Sec-Fetch-Dest", ignoreCase = true) ||
                            key.equals("Sec-Fetch-Mode", ignoreCase = true) ||
                            key.equals("Sec-Fetch-Site", ignoreCase = true))
                },
            ),
        )
        return true
    }

    private fun uniqueFile(directory: File, baseName: String, extension: String): File {
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else "-" + index
            val file = File(directory, baseName + suffix + extension)
            if (!file.exists()) return file
            index++
        }
    }

    private fun sanitize(value: String?): String =
        value.orEmpty()
            .replace(Regex("""[\\/:*?"<>|\r\n]+"""), " ")
            .trim()
            .take(120)

    private fun extensionFor(candidate: MediaCandidate): String =
        when (candidate.format.container) {
            com.ahdownload.domain.resolver.MediaContainer.Mp4 -> ".mp4"
            com.ahdownload.domain.resolver.MediaContainer.Webm -> ".webm"
            com.ahdownload.domain.resolver.MediaContainer.Mkv -> ".mkv"
            com.ahdownload.domain.resolver.MediaContainer.Mov -> ".mov"
            com.ahdownload.domain.resolver.MediaContainer.M4a -> ".m4a"
            com.ahdownload.domain.resolver.MediaContainer.Mp3 -> ".mp3"
            com.ahdownload.domain.resolver.MediaContainer.Aac -> ".aac"
            com.ahdownload.domain.resolver.MediaContainer.Ogg -> ".ogg"
            com.ahdownload.domain.resolver.MediaContainer.Flac -> ".flac"
            com.ahdownload.domain.resolver.MediaContainer.ThreeGp -> ".3gp"
            com.ahdownload.domain.resolver.MediaContainer.Avi -> ".avi"
            com.ahdownload.domain.resolver.MediaContainer.Unknown -> ".bin"
        }
}
