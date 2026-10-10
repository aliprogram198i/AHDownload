package com.ahdownload.domain.download

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAtomicFileSinkTest {

    @Test
    fun writesAndAtomicallyPublishesCompletedFile() = runBlocking {
        withTemporaryDirectory { directory ->
            val destination = File(directory, "media.bin")
            val sink = LocalAtomicFileSink()

            sink.openTemporary(destination.absolutePath, append = false).use {
                it.write("complete-media".toByteArray(Charsets.UTF_8))
            }

            assertEquals("complete-media".length.toLong(), sink.temporarySize(destination.absolutePath))
            sink.commit(destination.absolutePath)

            assertEquals("complete-media", destination.readText())
            assertFalse(File(destination.parentFile, destination.name + ".part").exists())
        }
    }

    @Test
    fun resumesTemporaryFileAndReplacesExistingDestination() = runBlocking {
        withTemporaryDirectory { directory ->
            val destination = File(directory, "media.bin")
            destination.writeText("old-data")
            val sink = LocalAtomicFileSink()

            sink.openTemporary(destination.absolutePath, append = false).use {
                it.write("new-".toByteArray(Charsets.UTF_8))
            }
            sink.openTemporary(destination.absolutePath, append = true).use {
                it.write("data".toByteArray(Charsets.UTF_8))
            }

            assertEquals(8L, sink.temporarySize(destination.absolutePath))
            sink.commit(destination.absolutePath)

            assertEquals("new-data", destination.readText())
            assertFalse(File(destination.parentFile, destination.name + ".part").exists())
        }
    }

    @Test
    fun discardRemovesOnlyTemporaryFile() = runBlocking {
        withTemporaryDirectory { directory ->
            val destination = File(directory, "media.bin")
            destination.writeText("preserve")
            val sink = LocalAtomicFileSink()
            sink.openTemporary(destination.absolutePath, append = false).use {
                it.write("partial".toByteArray(Charsets.UTF_8))
            }

            sink.discard(destination.absolutePath)

            assertEquals("preserve", destination.readText())
            assertFalse(File(destination.parentFile, destination.name + ".part").exists())
        }
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = File(
            System.getProperty("java.io.tmpdir"),
            "ahdownload-sink-test-${System.nanoTime()}",
        )
        assertTrue("Unable to create temporary test directory", directory.mkdirs())
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
