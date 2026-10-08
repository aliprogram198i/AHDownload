package com.ahdownload.app.social

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.feature.home.AndroidBrowserMediaSessionProvider
import com.ahdownload.feature.home.AndroidYouTubeSessionProvider
import com.ahdownload.feature.home.HomeResolver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SocialDownloadSmokeTest {

    private data class Case(
        val name: String,
        val platform: MediaPlatform,
        val url: String,
    )

    @Test
    fun liveSocialVideoSmokeTest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val browserProvider = AndroidBrowserMediaSessionProvider(context)
        val resolver = HomeResolver(
            sessionProvider = AndroidYouTubeSessionProvider(context),
            browserMediaSessionProvider = browserProvider,
        )
        val outputDir = File(context.cacheDir, "social-smoke").apply { mkdirs() }

        val cases = listOf(
            Case("Instagram", MediaPlatform.Instagram, "https://www.instagram.com/reel/DbAqmKPIaY5/"),
            Case("Facebook", MediaPlatform.Facebook, "https://www.facebook.com/amburexpress/videos/chinnaswamy-stadium-declared-unsafe-ipl-2026-matches-banned-at-rcbs-home-ground-/1260993795428923/"),
            Case("TikTok", MediaPlatform.TikTok, "https://www.tiktok.com/@scout2015/video/6718335390845095173"),
            Case("X", MediaPlatform.X, "https://x.com/historyinmemes/status/1790637656616943991"),
            Case("Snapchat", MediaPlatform.Snapchat, "https://www.snapchat.com/p/8af53eee-e298-40c4-9d6e-af20cf881b61/spotlight/W7_EDlXWTBiXAEEniNoMPwAAYYnZzdm1qc3l3AZu7sQlkAZu7sKUTAAAAAQ"),
            Case("Pinterest", MediaPlatform.Pinterest, "https://www.pinterest.com/pin/144326363052341106/"),
            Case("Reddit", MediaPlatform.Reddit, "https://www.reddit.com/r/vancouver/comments/1u8t3rd/i_filmed_a_day_to_night_timelapse_last_night_of/"),
            Case("Twitch", MediaPlatform.Twitch, "https://www.twitch.tv/videos/635475444"),
            Case("Vimeo", MediaPlatform.Vimeo, "https://vimeo.com/1182776978"),
            Case("YouTube", MediaPlatform.YouTube, "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )

        val results = mutableListOf<CaseResult>()
        val reportLines = mutableListOf<String>()
        reportLines += "AHDownload live social smoke test"
        reportLines += "android=" + android.os.Build.VERSION.RELEASE + " sdk=" + android.os.Build.VERSION.SDK_INT
        reportLines += "started=" + System.currentTimeMillis()
        reportLines += "strict_transfer_assertion=true"

        for (case in cases) {
            val caseResult = runCase(resolver, case, outputDir)
            results += caseResult
            reportLines += caseResult.reportLine
            println(caseResult.reportLine)
        }

        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }
        reportLines += "SUMMARY passed=${passed} failed=${failed} total=${cases.size}"

        val report = reportLines.joinToString("\n")
        println(report)
        File(outputDir, "report.txt").writeText(report)

        assertEquals(
            "Smoke test must produce one diagnostic result per platform case",
            cases.size,
            results.size,
        )
        assertTrue(
            "Live social smoke test failed for: " +
                results.filterNot { it.passed }.joinToString(", ") { it.case.name + "=" + it.reason },
            results.all { it.passed },
        )
    }

    private data class CaseResult(
        val case: Case,
        val passed: Boolean,
        val reason: String,
        val reportLine: String,
    )

    private suspend fun runCase(
        resolver: HomeResolver,
        case: Case,
        outputDir: File,
    ): CaseResult {
        val resolved = runCatching {
            withTimeout(TimeUnit.SECONDS.toMillis(45)) {
                resolver.resolve(
                    link = MediaLink(
                            originalUrl = case.url,
                            normalizedUrl = case.url,
                            platform = case.platform,
                            kind = MediaKind.Video,
                        ),
                    operationId = "smoke-" + case.platform.name.lowercase(),
                )
            }
        }

        if (resolved.isFailure) {
            val error = resolved.exceptionOrNull()
            val reason = "EXCEPTION_" + (error?.javaClass?.simpleName ?: "Unknown")
            return CaseResult(
                case = case,
                passed = false,
                reason = reason,
                reportLine = "RESULT ${case.name}: EXCEPTION " +
                    (error?.javaClass?.simpleName ?: "Unknown") + ": " +
                    (error?.message ?: ""),
            )
        }

        return when (val result = resolved.getOrThrow()) {
            is ResolverResult.Success -> {
                val videos = result.candidates
                    .filter { it.format.kind == MediaKind.Video }
                    .sortedWith(
                        compareByDescending<MediaCandidate> { it.format.height ?: 0 }
                            .thenByDescending { it.format.bitrateKbps ?: 0 },
                    )
                    .take(MAX_TRANSFER_CANDIDATES)

                if (videos.isEmpty()) {
                    CaseResult(
                        case = case,
                        passed = false,
                        reason = "RESOLVED_NO_VIDEO_CANDIDATE",
                        reportLine = "RESULT ${case.name}: RESOLVED_NO_VIDEO_CANDIDATE " +
                            "candidate_count=${result.candidates.size}",
                    )
                } else {
                    val attempts = videos.map { candidate ->
                        val probe = probeMedia(
                            candidate.sourceUrl,
                            candidate.requestHeaders,
                            outputDir,
                            case.name,
                        )
                        candidate to probe
                    }
                    val successful = attempts.firstOrNull { (_, probe) ->
                        probe.bytes > 0L && probe.mediaKind == MediaKind.Video
                    }
                    val bestCandidate = successful?.first ?: attempts.first().first
                    val bestProbe = successful?.second ?: attempts.first().second
                    val passed = successful != null
                    val attemptSummary = attempts.joinToString(",") { (candidate, probe) ->
                        candidate.format.height?.toString().orEmpty().ifBlank { "na" } +
                            ":" + probe.status + ":" + probe.bytes
                    }
                    CaseResult(
                        case = case,
                        passed = passed,
                        reason = if (passed) "OK" else bestProbe.status,
                        reportLine = "RESULT ${case.name}: RESOLVED " +
                            "candidate_count=${result.candidates.size} " +
                            "transfer_candidates=${videos.size} " +
                            "selected_height=${bestCandidate.format.height ?: 0} " +
                            "container=${bestCandidate.format.container} " +
                            "transfer=${bestProbe.status} bytes=${bestProbe.bytes} " +
                            "content_type=${bestProbe.contentType ?: "unknown"} " +
                            "payload_kind=${bestProbe.mediaKind.name} " +
                            "attempts=${attemptSummary}",
                    )
                }
            }

            is ResolverResult.Failure -> CaseResult(
                case = case,
                passed = false,
                reason = "FAILURE_${result.code}",
                reportLine = "RESULT ${case.name}: FAILURE code=" + result.code +
                    " message=" + (result.message ?: ""),
            )
        }
    }

    private data class Probe(
        val status: String,
        val bytes: Long,
        val contentType: String?,
        val mediaKind: MediaKind,
    )

    private fun probeMedia(
        mediaUrl: String,
        headers: Map<String, String>,
        outputDir: File,
        platform: String,
    ): Probe {
        val connection = (URL(mediaUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-1048575")
            setRequestProperty("Accept", "video/*,audio/*,*/*;q=0.1")
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) {
                    setRequestProperty(name, value)
                }
            }
        }

        return runCatching {
            val code = connection.responseCode
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()

            if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                Probe(
                    status = "HTTP_" + code,
                    bytes = 0L,
                    contentType = contentType,
                    mediaKind = MediaKind.Unknown,
                )
            } else {
                val destination = File(outputDir, platform.lowercase() + "-probe.bin")
                val signature = ByteArray(32)
                var signatureBytes = 0
                val responseBytes = connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    destination.outputStream().use { output ->
                        while (total < 1_048_576L) {
                            val remaining = (1_048_576L - total).toInt().coerceAtMost(buffer.size)
                            val count = input.read(buffer, 0, remaining)
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                            if (signatureBytes < signature.size) {
                                val copyCount = minOf(count, signature.size - signatureBytes)
                                System.arraycopy(buffer, 0, signature, signatureBytes, copyCount)
                                signatureBytes += copyCount
                            }
                            total += count
                        }
                    }
                    total
                }

                val mediaKind = detectMediaKind(contentType, signature, signatureBytes)
                val status = when {
                    responseBytes <= 0L -> "NO_RESPONSE_BYTES"
                    mediaKind == MediaKind.Unknown -> "INVALID_MEDIA_PAYLOAD"
                    else -> "HTTP_" + code
                }

                Probe(
                    status = status,
                    bytes = responseBytes,
                    contentType = contentType,
                    mediaKind = mediaKind,
                )
            }
        }.getOrElse { error ->
            Probe(
                status = "TRANSFER_ERROR_" + (error::class.java.simpleName ?: "Unknown"),
                bytes = 0L,
                contentType = null,
                mediaKind = MediaKind.Unknown,
            )
        }.also {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_TRANSFER_CANDIDATES = 4
    }

    private fun detectMediaKind(
        contentType: String?,
        bytes: ByteArray,
        count: Int,
    ): MediaKind {
        val type = contentType.orEmpty()
        if (type.startsWith("video/")) return MediaKind.Video
        if (type.startsWith("audio/")) return MediaKind.Audio
        if (count <= 0) return MediaKind.Unknown

        if (count >= 8 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        ) return MediaKind.Video

        if (count >= 4 &&
            bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()
        ) return MediaKind.Video

        if (count >= 3 &&
            bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()
        ) return MediaKind.Audio

        if (count >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            (bytes[1].toInt() and 0xE0) == 0xE0
        ) return MediaKind.Audio

        val prefix = bytes.copyOf(count.coerceAtMost(32)).toString(Charsets.UTF_8).trimStart()
        if (prefix.startsWith("<html", true) ||
            prefix.startsWith("<!doctype", true) ||
            prefix.startsWith("{\"error\"", true)
        ) return MediaKind.Unknown

        return MediaKind.Unknown
    }

}
