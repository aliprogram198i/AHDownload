package com.ahdownload.app.download

import com.ahdownload.domain.download.AudioOutputFormat
import com.ahdownload.domain.download.DownloadProcessingMode
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadFingerprintTest {
    @Test
    fun everyAudioOutputFormatHasItsOwnFingerprint() {
        val candidate = sampleCandidate()

        val fingerprints = AudioOutputFormat.entries.map { outputFormat ->
            calculateDownloadFingerprint(
                candidate = candidate,
                processingMode = DownloadProcessingMode.ExtractAudio,
                outputFormat = outputFormat,
            )
        }

        assertEquals(AudioOutputFormat.entries.size, fingerprints.toSet().size)
    }

    @Test
    fun m4aKeepsTheLegacyExtractionFingerprint() {
        val candidate = sampleCandidate()

        val expectedLegacy = legacyFingerprint(candidate, DownloadProcessingMode.ExtractAudio)
        val explicitM4a = calculateDownloadFingerprint(
            candidate,
            DownloadProcessingMode.ExtractAudio,
            AudioOutputFormat.M4a,
        )

        assertEquals(expectedLegacy, explicitM4a)
    }

    @Test
    fun directDownloadFingerprintIsUnaffectedByAudioFormatArgument() {
        val candidate = sampleCandidate()

        val withoutOutput = calculateDownloadFingerprint(candidate, DownloadProcessingMode.Direct)
        val withOutput = calculateDownloadFingerprint(
            candidate,
            DownloadProcessingMode.Direct,
            AudioOutputFormat.Mp3,
        )

        assertEquals(withoutOutput, withOutput)
    }

    @Test
    fun nonM4aExtractionFingerprintDiffersFromLegacyM4aFingerprint() {
        val candidate = sampleCandidate()

        val m4a = calculateDownloadFingerprint(
            candidate,
            DownloadProcessingMode.ExtractAudio,
            AudioOutputFormat.M4a,
        )
        val mp3 = calculateDownloadFingerprint(
            candidate,
            DownloadProcessingMode.ExtractAudio,
            AudioOutputFormat.Mp3,
        )

        assertNotEquals(m4a, mp3)
    }

    private fun legacyFingerprint(
        candidate: MediaCandidate,
        processingMode: DownloadProcessingMode,
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
            candidate.streamingManifest,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sampleCandidate() = MediaCandidate(
        id = "sample-format",
        sourceUrl = "https://cdn.example/media",
        format = MediaFormat(
            id = "sample-format",
            kind = MediaKind.Video,
            container = MediaContainer.Mp4,
            width = 1280,
            height = 720,
            bitrateKbps = 2400,
            hasVideo = true,
            hasAudio = false,
        ),
    )
}
