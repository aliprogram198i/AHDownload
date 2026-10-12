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
import com.ahdownload.domain.model.MediaKind
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
 * Live acceptance matrix for the exact 20 public URLs requested.
 * Uses the production LinkAnalyzer, HomeResolver, CandidateValidator, WorkManager pipeline,
 * and MediaStore publication. No mocked HTTP responses or fabricated media candidates.
 */
@RunWith(AndroidJUnit4::class)
class PublicMediaDownloadMatrixE2ETest {
    @Test(timeout = 3_300_000L)
    fun allTwentyPublicMediaUrlsResolveDownloadAndPublishValidBytes() = runBlocking {
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

        for (target in TARGETS) {
            val started = SystemClock.elapsedRealtime()
            val title = "AH-MATRIX-${target.id}-" + UUID.randomUUID().toString().take(8)
            try {
                val link = analyzer.analyze(target.url)
                    ?: error("LinkAnalyzer rejected the URL")
                val resolved = withTimeoutOrNull(30_000L) {
                    resolver.resolve(link, operationId = "matrix-${target.id}")
                } ?: error("Resolution timed out after 30 seconds")
                val candidates = when (resolved) {
                    is ResolverResult.Success -> resolved.candidates
                        .filter { it.format.kind == MediaKind.Video && it.format.hasVideo }
                        .distinctBy { it.sourceUrl }
                        .take(3)
                    is ResolverResult.Failure -> error(
                        "Resolver failure ${resolved.code}: ${resolved.message ?: "no details"}",
                    )
                }
                check(candidates.isNotEmpty()) { "No video candidates returned" }

                var lastFailure = "No candidate passed validation"
                var passed = false
                for ((index, candidate) in candidates.withIndex()) {
                    val validationResult = withTimeoutOrNull(20_000L) {
                        resolver.validate(candidate, operationId = "matrix-${target.id}-${index + 1}")
                    }
                    if (validationResult == null) {
                        lastFailure = "Candidate ${index + 1}: validation timeout"
                        continue
                    }
                    val validation = validationResult
                    if (validation !is CandidateValidationResult.Valid) {
                        lastFailure = "Candidate ${index + 1}: validation rejected"
                        continue
                    }

                    val enqueue = launcher.enqueue(
                        candidate = validation.candidate.copy(sourceUrl = validation.finalUrl),
                        title = title,
                        sourcePageUrl = target.url,
                        thumbnailUrl = null,
                    )
                    if (enqueue != DownloadEnqueueResult.QUEUED) {
                        lastFailure = "Candidate ${index + 1}: enqueue=$enqueue"
                        continue
                    }

                    val record = awaitTerminalRecord(app, target.url, title, 100_000L)
                    if (record == null) {
                        lastFailure = "Candidate ${index + 1}: no terminal/published record within 100 seconds"
                        continue
                    }
                    if (record.status != DownloadStatus.COMPLETED) {
                        lastFailure = "Candidate ${index + 1}: status=${record.status}; code=${record.failureCode ?: "unknown"}"
                        continue
                    }
                    val destination = record.destinationUri
                    if (destination.isNullOrBlank()) {
                        lastFailure = "Candidate ${index + 1}: completed without published URI"
                        continue
                    }

                    val uri = Uri.parse(destination)
                    val output = runCatching { inspectPublishedFile(context, uri) }
                    if (output.isFailure) {
                        lastFailure = "Candidate ${index + 1}: output inspection failed: ${output.exceptionOrNull()?.message}"
                        continue
                    }
                    val (bytes, prefix) = output.getOrThrow()
                    if (bytes <= 0L) {
                        lastFailure = "Candidate ${index + 1}: empty output"
                        continue
                    }
                    if (prefix.trimStart().startsWith("<html", true) ||
                        prefix.trimStart().startsWith("<!doctype", true) ||
                        prefix.trimStart().startsWith("{\"error\"", true)
                    ) {
                        lastFailure = "Candidate ${index + 1}: output is HTML/JSON, not media"
                        continue
                    }

                    val signature = prefix.toByteArray(Charsets.ISO_8859_1)
                        .take(12).joinToString("") { "%02x".format(it) }
                    val knownTotal = record.totalBytes?.toString() ?: "unknown"
                    val validatedExpected = validation.candidate.validatedContentLengthBytes
                        ?.toString() ?: "unknown"
                    lastFailure = "downloadedBytes=$bytes; downloadTotalBytes=$knownTotal; " +
                        "validatedExpectedBytes=$validatedExpected; signature=$signature"
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    passed = true
                    break
                }

                check(passed) { lastFailure }
                report += "PASS | ${target.id} | ${lastFailure} | ${SystemClock.elapsedRealtime() - started}ms"
            } catch (error: Throwable) {
                report += "FAIL | ${target.id} | ${(error.message ?: error.javaClass.simpleName).replace('\n', ' ').take(700)} | ${SystemClock.elapsedRealtime() - started}ms"
            }
            Log.i(TAG, report.last())
        }

        val summary = report.joinToString("\n")
        val passed = report.count { it.startsWith("PASS |") }
        val failed = report.size - passed
        Log.i(TAG, "FINAL MATRIX SUMMARY\n" + summary)
        assertTrue(
            "Public media download matrix: $passed passed, $failed failed out of ${TARGETS.size}.\n$summary",
            failed == 0 && passed == TARGETS.size,
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
            if (record != null) {
                val failed = record.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED)
                val published = record.status == DownloadStatus.COMPLETED &&
                    !record.destinationUri.isNullOrBlank()
                if (failed || published) return record
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
            check(count > 0) { "Published file has no readable data" }
            buffer.copyOf(count)
        }
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?.takeIf { it > 0L } ?: bytes.size.toLong()
        return size to String(bytes, Charsets.ISO_8859_1)
    }

    private data class Target(val id: String, val url: String)

    private companion object {
        const val TAG = "AH_MEDIA_MATRIX"
        val TARGETS = listOf(
            Target("01_apple_hls", "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8"),
            Target("02_mux_hls", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
            Target("03_akamai_dash", "https://dash.akamaized.net/akamai/test/caption_test/elephants_dream/elephants_dream_480p_heaac5_1.mpd"),
            Target("04_google_mp4", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
            Target("05_w3schools_mp4", "https://www.w3schools.com/html/mov_bbb.mp4"),
            Target("06_github_raw_mp4", "https://raw.githubusercontent.com/bower-media-samples/big-buck-bunny-480p-30s/master/video.mp4"),
            Target("07_nasa_apollo_mp4", "https://images-assets.nasa.gov/video/Apollo%2011%20Overview/Apollo%2011%20Overview~orig.mp4"),
            Target("08_archive_mp4", "https://archive.org/download/night_of_the_living_dead/night_of_the_living_dead_512kb.mp4"),
            Target("09_wikimedia_webm", "https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm.480p.vp9.webm"),
            Target("10_wikimedia_ogv", "https://upload.wikimedia.org/wikipedia/commons/7/79/Chameleon_color_change.ogv"),
            Target("11_youtube_watch", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            Target("12_youtube_shorts", "https://www.youtube.com/shorts/qRrv4hcrp-0"),
            Target("13_vimeo", "https://vimeo.com/76979871"),
            Target("14_dailymotion", "https://www.dailymotion.com/video/x7tgad0"),
            Target("15_ted", "https://www.ted.com/talks/tim_urban_inside_the_mind_of_a_master_procrastinator"),
            Target("16_blender_peertube", "https://video.blender.org/w/64cda44b-f748-42f4-a0c4-9a8be8e4f16a"),
            Target("17_reddit", "https://www.reddit.com/r/space/comments/lnxpt8/first_video_from_perseverance_on_mars/"),
            Target("18_archive_page", "https://archive.org/details/A_Trip_to_the_Moon"),
            Target("19_wikimedia_page", "https://commons.wikimedia.org/wiki/File:Curiosity%27s_Seven_Minutes_of_Terror.ogv"),
            Target("20_google_for_bigger_blazes", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"),
            Target("21_xnxx_arabic", "https://www.xnxx-arabic.com/video-1ey5zfb1/young_stepsister_is_doing_grooming_horny_stepsister_gives_me_her_rich_pussy"),
        )
    }
}
