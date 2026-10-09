package com.ahdownload.app.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaVideoAudioMuxerTest {
    @Test
    fun muxTemporaryOutputPreservesMp4ContainerExtension() {
        val temp = muxTemporaryOutputFile(File("/tmp/clip.mp4"))

        assertEquals("mp4", temp.extension)
        assertTrue(temp.name.endsWith(".muxing.mp4"))
    }

    @Test
    fun muxTemporaryOutputPreservesMatroskaContainerExtension() {
        val temp = muxTemporaryOutputFile(File("/tmp/clip.mkv"))

        assertEquals("mkv", temp.extension)
        assertTrue(temp.name.endsWith(".muxing.mkv"))
    }

    @Test
    fun extensionlessMuxOutputUsesMatroskaFallback() {
        val temp = muxTemporaryOutputFile(File("/tmp/clip"))

        assertEquals("mkv", temp.extension)
        assertTrue(temp.name.endsWith(".muxing.mkv"))
    }
}
