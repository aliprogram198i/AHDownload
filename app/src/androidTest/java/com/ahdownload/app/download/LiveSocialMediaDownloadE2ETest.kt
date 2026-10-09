package com.ahdownload.app.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahdownload.app.AHDownloadApplication
import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.download.DownloadEnqueueResult
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.validation.CandidateValidationResult
import com.ahdownload.feature.home.AndroidBrowserMediaSessionProvider
import com.ahdownload.feature.home.AndroidYouTubeSessionProvider
import com.ahdownload.feature.home.HomeResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Live-device acceptance test. Every case uses a real public post/video, resolves it with
 * the production resolver, validates a returned media URL, queues the production WorkManager
 * download, and verifies bytes from the file published into Android's media library.
 *
 * No mocked media URLs or fake HTTP responses are used.
 */
@RunWith(AndroidJUnit4::class)
class LiveSocialMediaDownloadE2ETest {
    @Test(timeout = 3_300_000L)
    fun publicVideoLinksResolveAndDownloadOnAndroidEmulator() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as AHDownloadApplication
        val resolver = HomeResolver(
            logger = app.diagnosticLogger,
            sessionProvider = AndroidYouTubeSessionProvider(context),
            browserMediaSessionProvider = AndroidBrowserMediaSessionProvider(context),
        )
        val launcher = DownloadLauncher(context)
        val analyzer = LinkAnalyzer()
        val report = mutableListOf<String>()

        val targets = listOf(
            Target("YouTube", MediaPlatform.YouTube, "https://www.youtube.com/watch?v=BaW_jenozKc"),
            Target("Instagram", MediaPlatform.Instagram, "https://www.instagram.com/p/DWHwMSwiQkW/"),
            Target("Facebook", MediaPlatform.Facebook, "https://www.facebook.com/reel/1653671952450066/"),
            Target("TikTok", MediaPlatform.TikTok, "https://www.tiktok.com/@scout2015/video/6718335390845095173"),
            Target("X", MediaPlatform.X, "https://x.com/NASAEarth/status/2019783141242610121"),
            Target("Snapchat", MediaPlatform.Snapchat, "https://www.snapchat.com/@nasa/spotlight/W7_EDlXWTBiXAEEniNoMPwAAYdHBmbG9hdHR0AZ1ytS-RAZ1ytSiwAAAAAQ"),
            Target("Pinterest", MediaPlatform.Pinterest, "https://www.pinterest.com/pin/842173199081163306/"),
            Target("Reddit", MediaPlatform.Reddit, "https://www.reddit.com/r/spaceporn/comments/1sgcz1e/nasa_just_dropped_a_new_highresolution_video_of/"),
            Target("Twitch", MediaPlatform.Twitch, "https://clips.twitch.tv/ZanyBlazingOtterNomNom-86PFh3-7kWWyJhrs"),
            Target("Vimeo", MediaPlatform.Vimeo, "https://vimeo.com/764921867"),
        )

        for (target in targets) {
            val started = SystemClock.elapsedRealtime()
            val title = "AH-E2E-" + target.name + "-" + UUID.randomUUID().toString().take(8)
            try {
                val link = analyzer.analyze(target.url)
                    ?: error("LinkAnalyzer rejected the public test URL")
                check(link.platform == target.platform) {
                    "Platform detection mismatch: expected=" + target.platform + ", actual=" + link.platform
                }

                val resolved = withTimeoutOrNull(35_000L) {
                    resolver.resolve(link, operationId = "live-e2e-" + target.name)
                } ?: error("Resolution timed out after 35 seconds")

                val candidates = when (resolved) {
                    is ResolverResult.Success -> resolved.candidates
                        .filter {
                            it.format.kind == com.ahdownload.domain.model.MediaKind.Video &&
                                it.format.hasVideo
                        }
                        .distinctBy { it.sourceUrl }
                        .sortedWith(
                            compareBy<com.ahdownload.domain.resolver.MediaCandidate> { it.format.height ?: Int.MAX_VALUE }
                                .thenBy { it.format.fileSizeBytes ?: Long.MAX_VALUE },
                        )
                        .take(2)
                    is ResolverResult.Failure -> error(
                        "Resolver returned " + resolved.code + ": " + (resolved.message ?: "no details"),
                    )
                }
                check(candidates.isNotEmpty()) {
                    "Resolver returned no video candidates (candidate count=0)"
                }

                var candidateFailure = "No candidate was accepted"
                var downloaded = false
                for ((index, candidate) in candidates.withIndex()) {
                    val validation = withTimeoutOrNull(20_000L) {
                        resolver.validate(candidate, operationId = "live-e2e-" + target.name + "-" + index)
                    }
                    if (validation == null) {
                        candidateFailure = "Candidate " + (index + 1) + ": validation timeout"
                        continue
                    }
                    if (validation !is CandidateValidationResult.Valid) {
                        candidateFailure = "Candidate " + (index + 1) + ": validation rejected (" + validation.failure + ")"
                        continue
                    }

                    val enqueue = launcher.enqueue(
                        candidate = validation.candidate.copy(sourceUrl = validation.finalUrl),
                        title = title,
                        sourcePageUrl = target.url,
                        thumbnailUrl = null,
                    )
                    if (enqueue != DownloadEnqueueResult.QUEUED) {
                        candidateFailure = "Candidate " + (index + 1) + ": queue rejected with " + enqueue
                        continue
                    }

                    val record = awaitTerminalRecord(
                        app = app,
                        sourcePageUrl = target.url,
                        displayName = title,
                        timeoutMs = 90_000L,
                    )
                    if (record == null) {
                        candidateFailure = "Candidate " + (index + 1) + ": download did not reach a terminal state in 105 seconds"
                        continue
                    }
                    if (record.status != DownloadStatus.COMPLETED) {
                        candidateFailure = "Candidate " + (index + 1) + ": status=" + record.status +
                            ", failure=" + (record.failureCode ?: "unknown") +
                            " (" + (record.failureDetail ?: "no details") + ")"
                        continue
                    }
                    val destination = record.destinationUri
                    if (destination.isNullOrBlank()) {
                        candidateFailure = "Candidate " + (index + 1) + ": completion had no published output URI"
                        continue
                    }

                    val uri = Uri.parse(destination)
                    val probe = runCatching { inspectPublishedFile(context, uri) }
                    if (probe.isFailure) {
                        candidateFailure = "Candidate " + (index + 1) + ": output verification failed: " +
                            (probe.exceptionOrNull()?.message ?: "unknown")
                        continue
                    }

                    val (byteCount, prefix) = probe.getOrThrow()
                    if (byteCount <= 0L) {
                        candidateFailure = "Candidate " + (index + 1) + ": output is empty"
                        continue
                    }
                    if (prefix.trimStart().startsWith("<html", ignoreCase = true) ||
                        prefix.trimStart().startsWith("<!doctype", ignoreCase = true)
                    ) {
                        candidateFailure = "Candidate " + (index + 1) + ": output is HTML, not a media file"
                        continue
                    }

                    downloaded = true
                    val headerHex = prefix.take(12).toByteArray().joinToString("") { "%02x".format(it) }
                    candidateFailure = "downloaded " + byteCount + " bytes; header=" + headerHex
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    break
                }

                check(downloaded) { candidateFailure }
                report += "PASS | " + target.name + " | candidates=" + candidates.size + " | " +
                    candidateFailure + " | " + (SystemClock.elapsedRealtime() - started) + "ms"
            } catch (error: Throwable) {
                val reason = (error.message ?: error.javaClass.simpleName).replace('\n', ' ').take(900)
                report += "FAIL | " + target.name + " | " + reason + " | " +
                    (SystemClock.elapsedRealtime() - started) + "ms"
                Log.e(TAG, report.last(), error)
            }
            Log.i(TAG, report.last())
        }

        val summary = report.joinToString("\n")
        Log.i(TAG, "FINAL SUMMARY\n" + summary)
        val passed = report.count { it.startsWith("PASS |") }
        val failed = report.size - passed
        assertTrue(
            "Live public-video emulator acceptance tests: " + passed + " passed, " + failed + " failed.\n" + summary,
            failed == 0 && passed == targets.size,
        )
    }

    private suspend fun awaitTerminalRecord(
        app: AHDownloadApplication,
        sourcePageUrl: String,
        displayName: String,
        timeoutMs: Long,
    ): com.ahdownload.domain.download.DownloadRecord? {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            val record = app.downloadRepository.listHistory().firstOrNull {
                it.task.sourcePageUrl == sourcePageUrl && it.task.displayName == displayName
            }
            if (record != null && record.status in setOf(
                    DownloadStatus.COMPLETED,
                    DownloadStatus.FAILED,
                    DownloadStatus.CANCELLED,
                )
            ) {
                return record
            }
            delay(750L)
        }
        return null
    }

    private fun inspectPublishedFile(context: Context, uri: Uri): Pair<Long, String> {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Published MediaStore URI cannot be opened")
        val bytes = input.use { stream ->
            val buffer = ByteArray(4096)
            val count = stream.read(buffer)
            check(count > 0) { "Published MediaStore item has no readable data" }
            buffer.copyOf(count)
        }
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?.takeIf { it > 0L } ?: bytes.size.toLong()
        return size to String(bytes, Charsets.ISO_8859_1)
    }

    private data class Target(
        val name: String,
        val platform: MediaPlatform,
        val url: String,
    )

    private companion object {
        const val TAG = "AH_LIVE_E2E"
    }
}
