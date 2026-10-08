package com.ahdownload.app.download

import android.content.Context
import android.os.Environment
import com.ahdownload.app.settings.DownloadLocationStore
import com.ahdownload.domain.download.AudioOutputFormat
import com.ahdownload.domain.download.DownloadEnqueueResult
import com.ahdownload.domain.download.DownloadProcessingMode
import com.ahdownload.domain.download.DownloadTask
import com.ahdownload.domain.resolver.MediaCandidate
import java.io.File
import java.security.MessageDigest

class DownloadLauncher(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val application = appContext as com.ahdownload.app.AHDownloadApplication
    private val scheduler = application.downloadWorkScheduler
    private val repository = application.downloadRepository
    private val locationStore = DownloadLocationStore(appContext)

    suspend fun enqueue(
        candidate: MediaCandidate,
        title: String?,
        sourcePageUrl: String? = null,
        thumbnailUrl: String? = null,
    ): DownloadEnqueueResult {
        if (locationStore.persistedUri() != null && !locationStore.hasAccessibleCustomLocation()) {
            return DownloadEnqueueResult.INVALID_CUSTOM_LOCATION
        }

        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return DownloadEnqueueResult.STORAGE_UNAVAILABLE

        if (!directory.exists() && !directory.mkdirs()) {
            return DownloadEnqueueResult.STORAGE_UNAVAILABLE
        }

        val extension = extensionFor(candidate)
        val baseName = sanitize(title).ifBlank { "AHDownload-" + candidate.id }
        val fingerprint = fingerprint(candidate)
        if (repository.findByContentFingerprint(fingerprint) != null) {
            return DownloadEnqueueResult.DUPLICATE
        }

        val taskId = fingerprint.take(36)
        val file = uniqueFile(directory, baseName, extension)

        scheduler.enqueue(
            DownloadTask(
                id = taskId,
                sourceUrl = candidate.sourceUrl,
                destinationPath = file.absolutePath,
                displayName = title?.trim()?.takeIf { it.isNotBlank() } ?: baseName,
                thumbnailUrl = thumbnailUrl?.trim()?.takeIf {
                    it.startsWith("http://") || it.startsWith("https://")
                },
                contentFingerprint = fingerprint,
                sessionCookieHost = candidate.sessionCookieHost,
                sourcePageUrl = sourcePageUrl?.trim()?.takeIf {
                    it.startsWith("http://") || it.startsWith("https://")
                },
                mediaKind = candidate.format.kind,
                processingMode = if (candidate.companionAudioSourceUrl.isNullOrBlank()) {
                    DownloadProcessingMode.Direct
                } else {
                    DownloadProcessingMode.MuxVideoAudio
                },
                companionAudioSourceUrl = candidate.companionAudioSourceUrl,
                companionAudioRequestHeaders = candidate.companionAudioRequestHeaders.filterKeys(::isPersistableHeader),
                companionAudioSessionCookieHost = candidate.companionAudioSessionCookieHost,
                requestHeaders = candidate.requestHeaders.filterKeys { key ->
                    !key.equals("Cookie", ignoreCase = true) &&
                        (key.equals("User-Agent", ignoreCase = true) ||
                            key.equals("Referer", ignoreCase = true) ||
                            key.equals("Origin", ignoreCase = true) ||
                            key.equals("Accept", ignoreCase = true) ||
                            key.equals("Accept-Language", ignoreCase = true) ||
                            key.equals("Sec-Fetch-Dest", ignoreCase = true) ||
                            key.equals("Sec-Fetch-Mode", ignoreCase = true) ||
                            key.equals("Sec-Fetch-Site", ignoreCase = true) ||
                            key.equals("X-Goog-Visitor-Id", ignoreCase = true) ||
                            key.equals("X-YouTube-Client-Name", ignoreCase = true) ||
                            key.equals("X-YouTube-Client-Version", ignoreCase = true) ||
                            key.equals("Sec-CH-UA", ignoreCase = true) ||
                            key.equals("Sec-CH-UA-Mobile", ignoreCase = true) ||
                            key.equals("Sec-CH-UA-Platform", ignoreCase = true) )
                },
            ),
        )
        return DownloadEnqueueResult.QUEUED
    }

    suspend fun enqueueAudioExtraction(
        candidate: MediaCandidate,
        outputFormat: AudioOutputFormat,
        title: String?,
        sourcePageUrl: String? = null,
        thumbnailUrl: String? = null,
    ): DownloadEnqueueResult {
        if (locationStore.persistedUri() != null && !locationStore.hasAccessibleCustomLocation()) {
            return DownloadEnqueueResult.INVALID_CUSTOM_LOCATION
        }

        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return DownloadEnqueueResult.STORAGE_UNAVAILABLE

        if (!directory.exists() && !directory.mkdirs()) {
            return DownloadEnqueueResult.STORAGE_UNAVAILABLE
        }

        val baseName = sanitize(title).ifBlank { "AHDownload-" + candidate.id }
        val fingerprint = fingerprint(candidate, DownloadProcessingMode.ExtractAudio)
        if (repository.findByContentFingerprint(fingerprint) != null) {
            return DownloadEnqueueResult.DUPLICATE
        }

        val file = uniqueFile(directory, baseName, outputFormat.extension)
        scheduler.enqueue(
            DownloadTask(
                id = fingerprint.take(36),
                sourceUrl = candidate.sourceUrl,
                destinationPath = file.absolutePath,
                displayName = title?.trim()?.takeIf { it.isNotBlank() }?.let { "$it - Audio" } ?: baseName + " - Audio",
                thumbnailUrl = thumbnailUrl?.trim()?.takeIf {
                    it.startsWith("http://") || it.startsWith("https://")
                },
                contentFingerprint = fingerprint,
                sessionCookieHost = candidate.sessionCookieHost,
                sourcePageUrl = sourcePageUrl?.trim()?.takeIf {
                    it.startsWith("http://") || it.startsWith("https://")
                },
                mediaKind = com.ahdownload.domain.model.MediaKind.Audio,
                processingMode = DownloadProcessingMode.ExtractAudio,
                audioOutputFormat = outputFormat,
                requestHeaders = candidate.requestHeaders.filterKeys { key ->
                    !key.equals("Cookie", ignoreCase = true) &&
                        (key.equals("User-Agent", ignoreCase = true) ||
                            key.equals("Referer", ignoreCase = true) ||
                            key.equals("Origin", ignoreCase = true) ||
                            key.equals("Accept", ignoreCase = true) ||
                            key.equals("Accept-Language", ignoreCase = true) ||
                            key.equals("Sec-Fetch-Dest", ignoreCase = true) ||
                            key.equals("Sec-Fetch-Mode", ignoreCase = true) ||
                            key.equals("Sec-Fetch-Site", ignoreCase = true) ||
                            key.equals("X-Goog-Visitor-Id", ignoreCase = true) ||
                            key.equals("X-YouTube-Client-Name", ignoreCase = true) ||
                            key.equals("X-YouTube-Client-Version", ignoreCase = true) ||
                            key.equals("Sec-CH-UA", ignoreCase = true) ||
                            key.equals("Sec-CH-UA-Mobile", ignoreCase = true) ||
                            key.equals("Sec-CH-UA-Platform", ignoreCase = true))
                },
            ),
        )
        return DownloadEnqueueResult.QUEUED
    }

    private fun fingerprint(
        candidate: MediaCandidate,
        processingMode: DownloadProcessingMode = DownloadProcessingMode.Direct,
    ): String {
        val raw = listOf(
            processingMode.name,
            candidate.sourceUrl,
            candidate.format.id,
            candidate.format.kind.name,
            candidate.format.container.name,
            candidate.format.width ?: 0,
            candidate.format.height ?: 0,
            candidate.format.bitrateKbps ?: 0,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isPersistableHeader(key: String): Boolean =
        key.equals("User-Agent", true) ||
            key.equals("Referer", true) ||
            key.equals("Origin", true) ||
            key.equals("Accept", true) ||
            key.equals("Accept-Language", true) ||
            key.equals("Sec-Fetch-Dest", true) ||
            key.equals("Sec-Fetch-Mode", true) ||
            key.equals("Sec-Fetch-Site", true) ||
            key.equals("X-Goog-Visitor-Id", true) ||
            key.equals("X-YouTube-Client-Name", true) ||
            key.equals("X-YouTube-Client-Version", true) ||
            key.equals("Sec-CH-UA", true) ||
            key.equals("Sec-CH-UA-Mobile", true) ||
            key.equals("Sec-CH-UA-Platform", true)

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
        if (!candidate.companionAudioSourceUrl.isNullOrBlank()) {
            when (candidate.format.container) {
                com.ahdownload.domain.resolver.MediaContainer.Mp4 -> ".mp4"
                com.ahdownload.domain.resolver.MediaContainer.Webm -> ".mkv"
                com.ahdownload.domain.resolver.MediaContainer.Mkv -> ".mkv"
                else -> ".mkv"
            }
        } else when (candidate.format.container) {
            com.ahdownload.domain.resolver.MediaContainer.Mp4 -> ".mp4"
            com.ahdownload.domain.resolver.MediaContainer.Webm -> ".webm"
            com.ahdownload.domain.resolver.MediaContainer.Mkv -> ".mkv"
            com.ahdownload.domain.resolver.MediaContainer.Mov -> ".mov"
            com.ahdownload.domain.resolver.MediaContainer.M4a -> ".m4a"
            com.ahdownload.domain.resolver.MediaContainer.Mp3 -> ".mp3"
            com.ahdownload.domain.resolver.MediaContainer.Aac -> ".aac"
            com.ahdownload.domain.resolver.MediaContainer.Ogg -> ".ogg"
            com.ahdownload.domain.resolver.MediaContainer.Flac -> ".flac"
            com.ahdownload.domain.resolver.MediaContainer.Wav -> ".wav"
            com.ahdownload.domain.resolver.MediaContainer.ThreeGp -> ".3gp"
            com.ahdownload.domain.resolver.MediaContainer.Avi -> ".avi"
            com.ahdownload.domain.resolver.MediaContainer.Unknown -> ".bin"
        }
}
