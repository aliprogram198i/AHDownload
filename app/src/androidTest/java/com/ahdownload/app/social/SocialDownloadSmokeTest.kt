package com.ahdownload.app.social

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.resolver.youtube.YouTubeResolver
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
            youtubeResolver = YouTubeResolver(
                httpClient = OkHttpTextClient(),
                sessionProvider = AndroidYouTubeSessionProvider(context),
            ),
            browserMediaSessionProvider = browserProvider,
        )
        val outputDir = File(context.cacheDir, "social-smoke").apply { mkdirs() }

        val cases = listOf(
            Case("Instagram", MediaPlatform.Instagram, "https://www.instagram.com/reel/DWgvoQ3jcFs/"),
            Case("Facebook", MediaPlatform.Facebook, "https://www.facebook.com/attn/posts/pfbid0j1Czf2gGDVqeQ8KiMLFm3pWN8GxsQmeRrVhimWDzMuKQoR8r4b1knNsejELmUgyhl"),
            Case("TikTok", MediaPlatform.TikTok, "https://www.tiktok.com/t/ZTRC5xgJp"),
            Case("X", MediaPlatform.X, "https://x.com/historyinmemes/status/1790637656616943991"),
            Case("Snapchat", MediaPlatform.Snapchat, "https://www.snapchat.com/spotlight"),
            Case("Pinterest", MediaPlatform.Pinterest, "https://www.pinterest.com/pin/664281013778109217/"),
            Case("Reddit", MediaPlatform.Reddit, "https://www.reddit.com/r/videos/comments/6rrwyj/that_small_heart_attack/"),
            Case("Twitch", MediaPlatform.Twitch, "https://www.twitch.tv/videos/635475444"),
            Case("Vimeo", MediaPlatform.Vimeo, "https://vimeo.com/56015672"),
            Case("YouTube", MediaPlatform.YouTube, "https://www.youtube.com/watch?v=BaW_jenozKc"),
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
                    ResolverRequest(
                        link = MediaLink(
                            originalUrl = case.url,
                            normalizedUrl = case.url,
                            platform = case.platform,
                            kind = MediaKind.Video,
                        ),
                        requestedKind = MediaKind.Video,
                        operationId = "smoke-" + case.platform.name.lowercase(),
                    ),
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
                val video = result.candidates
                    .filter { it.format.kind == MediaKind.Video }
                    .sortedByDescending { it.format.height ?: 0 }
                    .firstOrNull()

                if (video == null) {
                    CaseResult(
                        case = case,
                        passed = false,
                        reason = "RESOLVED_NO_VIDEO_CANDIDATE",
                        reportLine = "RESULT ${case.name}: RESOLVED_NO_VIDEO_CANDIDATE " +
                            "candidate_count=${result.candidates.size}",
                    )
                } else {
                    val probe = probeMedia(video.sourceUrl, video.requestHeaders, outputDir, case.name)
                    val passed = probe.bytes > 0L
                    CaseResult(
                        case = case,
                        passed = passed,
                        reason = if (passed) "OK" else probe.status,
                        reportLine = "RESULT ${case.name}: RESOLVED " +
                            "candidate_count=${result.candidates.size} " +
                            "height=${video.format.height ?: 0} " +
                            "container=${video.format.container} " +
                            "transfer=${probe.status} bytes=${probe.bytes}",
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
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) {
                    setRequestProperty(name, value)
                }
            }
        }

        return runCatching {
            val code = connection.responseCode
            if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                Probe(
                    status = "HTTP_" + code,
                    bytes = 0L,
                )
            } else {
                val destination = File(outputDir, platform.lowercase() + "-probe.bin")
                val responseBytes = connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    destination.outputStream().use { output ->
                        while (total < 1_048_576L) {
                            val remaining = (1_048_576L - total).toInt().coerceAtMost(buffer.size)
                            val count = input.read(buffer, 0, remaining)
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                            total += count
                        }
                    }
                    total
                }
                Probe(
                    status = "HTTP_" + code,
                    bytes = responseBytes,
                )
            }
        }.getOrElse { error ->
            Probe(
                status = "TRANSFER_ERROR_" + (error.javaClass.simpleName ?: "Unknown"),
                bytes = 0L,
            )
        }.also {
            connection.disconnect()
        }
    }
}
