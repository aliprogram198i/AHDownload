package com.ahdownload.app.download

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.fetchSemanticsNodes
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahdownload.app.AHDownloadApplication
import com.ahdownload.app.MainActivity
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-network emulator acceptance test for the exact YouTube Search -> select result ->
 * result-card -> video download path. It intentionally fails unless the card presents an
 * actionable video+audio option and audio extraction choices, and the queued file is published.
 */
@RunWith(AndroidJUnit4::class)
class YouTubeResultCardEmulatorTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 540_000L)
    fun youtubeSearchSelectionShowsActionableCardAndDownloadsVideo() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as AHDownloadApplication
        val existingTaskIds = runBlocking { app.downloadRepository.listHistory() }
            .mapTo(mutableSetOf()) { it.task.id }

        composeRule.onNodeWithText("ابدأ الآن").performClick()
        composeRule.onNodeWithText("بحث YouTube").performClick()
        composeRule.onNodeWithContentDescription("حقل البحث في YouTube")
            .performTextInput(SEARCH_QUERY)
        composeRule.onNodeWithText("بحث").performClick()

        val searchSettled = runCatching {
            composeRule.waitUntil(timeoutMillis = SEARCH_TIMEOUT_MS) {
                nodeExists("Never Gonna Give You Up", substring = true) ||
                    nodeExists("لم نجد نتائج مطابقة حاليًا.") ||
                    nodeExists("تعذر تنفيذ البحث الآن. أعد المحاولة.")
            }
        }.isSuccess
        val targetSearchResultExists = nodeExists("Never Gonna Give You Up", substring = true)
        assertTrue(
            "YouTube internal search did not return the expected public test video. " +
                "settled=$searchSettled; diagnosticSummary=" + safeDiagnosticSummary(app),
            targetSearchResultExists,
        )

        composeRule.onNodeWithText("Never Gonna Give You Up", substring = true).performClick()

        val resolutionSettled = runCatching {
            composeRule.waitUntil(timeoutMillis = RESOLUTION_TIMEOUT_MS) {
                nodeExists("الفيديو + الصوت") || nodeExists("تعذر تجهيز الرابط")
            }
        }.isSuccess
        val cardVisible = nodeExists("الفيديو + الصوت")
        assertTrue(
            "YouTube selection did not render the result card. settled=$resolutionSettled; " +
                "diagnosticSummary=" + safeDiagnosticSummary(app),
            cardVisible,
        )

        composeRule.onNodeWithText("الفيديو + الصوت").assertExists()
        composeRule.onNodeWithText("استخراج الصوت").assertExists()
        composeRule.waitUntil(timeoutMillis = 20_000L) {
            latestCardEvent(app) != null
        }
        val cardEvent = requireNotNull(latestCardEvent(app))
        val cardMetrics = cardEvent.context
        val videoOptions = cardMetrics["video_format_option_count"]?.toIntOrNull() ?: 0
        val audioAvailable = cardMetrics["audio_extraction_available"] == "true"
        Log.i(
            TAG,
            "RESULT_CARD platform=${cardMetrics["platform"]} " +
                "rawCandidates=${cardMetrics["candidate_total"]} " +
                "deduplicated=${cardMetrics["deduplicated_candidate_total"]} " +
                "videoOptions=$videoOptions " +
                "audioSources=${cardMetrics["audio_source_option_count"]} " +
                "audioExtractionAvailable=$audioAvailable",
        )
        assertTrue(
            "The result card appeared but has no actionable video+audio quality. " +
                "metrics=${cardMetrics.filterKeys { it in SAFE_METRIC_KEYS }}; " +
                safeDiagnosticSummary(app),
            videoOptions > 0,
        )
        assertTrue(
            "The result card appeared but audio extraction is unavailable. " +
                "metrics=${cardMetrics.filterKeys { it in SAFE_METRIC_KEYS }}",
            audioAvailable,
        )

        copyAndLogResultCardTrace(context)

        // Select the first presented option. Video options are rendered before audio options.
        composeRule.onNode(
            hasContentDescription("اختيار:", substring = true),
            useUnmergedTree = true,
        ).performScrollTo().performClick()

        val videoDownloadButton = hasContentDescription("تنزيل الفيديو مع الصوت") or
            hasContentDescription("تنزيل الفيديو مع دمج مسار صوت منفصل")
        composeRule.onNode(videoDownloadButton, useUnmergedTree = true)
            .performScrollTo()
            .performClick()

        var terminalRecord: DownloadRecord? = null
        var downloadActionFailed = false
        composeRule.waitUntil(timeoutMillis = DOWNLOAD_TIMEOUT_MS) {
            val current = runBlocking { app.downloadRepository.listHistory() }
                .firstOrNull { it.task.id !in existingTaskIds }
            if (current != null && current.status in setOf(
                    DownloadStatus.COMPLETED,
                    DownloadStatus.FAILED,
                    DownloadStatus.CANCELLED,
                )
            ) {
                terminalRecord = current
                true
            } else if (nodeExists("تعذر بدء التنزيل")) {
                downloadActionFailed = true
                true
            } else {
                false
            }
        }

        val record = requireNotNull(terminalRecord) {
            "YouTube download did not reach a terminal record; " +
                "downloadActionFailed=$downloadActionFailed; " + safeDiagnosticSummary(app)
        }
        assertEquals(
            "The YouTube download did not complete. failureCode=${record.failureCode}; " +
                safeDiagnosticSummary(app),
            DownloadStatus.COMPLETED,
            record.status,
        )

        val destination = requireNotNull(record.destinationUri) {
            "Download was marked completed without a published MediaStore URI."
        }
        val uri = Uri.parse(destination)
        val (byteCount, header) = inspectPublishedMedia(context, uri)
        assertTrue("Published video is empty.", byteCount > 0L)
        assertTrue(
            "Published output looks like HTML rather than a media file.",
            !header.trimStart().startsWith("<html", ignoreCase = true) &&
                !header.trimStart().startsWith("<!doctype", ignoreCase = true),
        )
        Log.i(TAG, "DOWNLOAD_PASS bytes=$byteCount headerHex=${header.toByteArray().take(12).joinToString("") { "%02x".format(it) }}")
        copyAndLogResultCardTrace(context)
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun nodeExists(text: String, substring: Boolean = false): Boolean =
        composeRule.onAllNodesWithText(text, substring = substring)
            .fetchSemanticsNodes().isNotEmpty()

    private fun latestCardEvent(app: AHDownloadApplication) =
        app.diagnosticLogger.list().lastOrNull { it.type == "SMART_CENTER_RESULT_PRESENTED" }

    private fun safeDiagnosticSummary(app: AHDownloadApplication): String {
        val safeTypes = setOf(
            "SMART_CENTER_RESULT_PRESENTED",
            "YOUTUBE_RESOLVE_FAILED",
            "YOUTUBE_RESOLVE_SUCCEEDED",
            "YOUTUBE_CANDIDATE_VALIDATION",
            "MEDIA_VALIDATION_FAILED",
            "DOWNLOAD_FAILED",
        )
        return app.diagnosticLogger.list()
            .takeLast(35)
            .filter { it.type in safeTypes || it.type.contains("YOUTUBE", ignoreCase = true) }
            .joinToString(" | ") { event ->
                event.type + ":" + event.context
                    .filterKeys { it in SAFE_DIAGNOSTIC_KEYS }
                    .entries.joinToString(",") { "${it.key}=${it.value}" }
            }
            .take(6000)
    }

    private fun copyAndLogResultCardTrace(context: Context) {
        runCatching {
            composeRule.onNodeWithText("نسخ سجل بطاقة النتائج")
                .performScrollTo()
                .performClick()
            composeRule.waitForIdle()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val report = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                .orEmpty()
            if (report.isNotBlank()) Log.i(TAG, "RESULT_CARD_TRACE\n" + report.takeLast(7_000))
        }.onFailure { Log.w(TAG, "Could not export result-card trace: ${it.javaClass.simpleName}") }
    }

    private fun inspectPublishedMedia(context: Context, uri: Uri): Pair<Long, String> {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Published MediaStore URI cannot be opened")
        val bytes = input.use { stream ->
            val buffer = ByteArray(4096)
            val count = stream.read(buffer)
            check(count > 0) { "Published media has no readable data" }
            buffer.copyOf(count)
        }
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?.takeIf { it > 0L } ?: bytes.size.toLong()
        return size to String(bytes, Charsets.ISO_8859_1)
    }

    private companion object {
        const val TAG = "AH_YOUTUBE_E2E"
        const val SEARCH_QUERY = "Never Gonna Give You Up Rick Astley"
        const val SEARCH_TIMEOUT_MS = 65_000L
        const val RESOLUTION_TIMEOUT_MS = 180_000L
        const val DOWNLOAD_TIMEOUT_MS = 210_000L

        val SAFE_METRIC_KEYS = setOf(
            "platform", "candidate_total", "deduplicated_candidate_total", "available_total",
            "video_candidate_count", "video_format_option_count", "unresolved_video_candidate_count",
            "known_video_resolution_count", "unknown_video_quality_count", "known_audio_bitrate_tier_count",
            "audio_source_option_count", "audio_extraction_available", "browser_observed_candidate_count",
        )
        val SAFE_DIAGNOSTIC_KEYS = setOf(
            "operation_id", "platform", "candidate_count", "video_candidate_count",
            "video_with_audio_count", "video_without_audio_count", "http_status", "status_code",
            "content_type", "content_length_bytes", "validation_result", "failure_code",
            "exception_type", "host", "stage", "classification", "root_cause", "failure",
        )
    }
}
