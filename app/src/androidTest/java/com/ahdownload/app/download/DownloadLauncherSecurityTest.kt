package com.ahdownload.app.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahdownload.domain.download.DownloadEnqueueResult
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadLauncherSecurityTest {
    @Test
    fun rejectsCleartextSourceBeforeQueueingDownload() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = DownloadLauncher(context)

        val result = launcher.enqueue(candidate("http://cdn.example/video.mp4"), "video")

        assertEquals(DownloadEnqueueResult.REJECTED, result)
    }

    @Test
    fun rejectsCleartextCompanionAudioBeforeQueueingMux() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = DownloadLauncher(context)

        val result = launcher.enqueue(
            candidate(
                sourceUrl = "https://cdn.example/video.mp4",
                companionAudioUrl = "http://cdn.example/audio.m4a",
            ),
            "video",
        )

        assertEquals(DownloadEnqueueResult.REJECTED, result)
    }

    @Test
    fun rejectsCleartextSourceBeforeQueueingAudioExtraction() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = DownloadLauncher(context)

        val result = launcher.enqueueAudioExtraction(
            candidate("http://cdn.example/video.mp4"),
            com.ahdownload.domain.download.AudioOutputFormat.Mp3,
            "video",
        )

        assertEquals(DownloadEnqueueResult.REJECTED, result)
    }

    private fun candidate(
        sourceUrl: String,
        companionAudioUrl: String? = null,
    ) = MediaCandidate(
        id = "test-candidate",
        sourceUrl = sourceUrl,
        format = MediaFormat(
            id = "test-format",
            kind = MediaKind.Video,
            container = MediaContainer.Mp4,
            hasVideo = true,
            hasAudio = true,
        ),
        companionAudioSourceUrl = companionAudioUrl,
    )
}
