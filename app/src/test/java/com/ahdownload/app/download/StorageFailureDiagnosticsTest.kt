package com.ahdownload.app.download

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StorageFailureDiagnosticsTest {
    @Test
    fun classifiesOutOfSpaceWithoutReturningRawExceptionText() {
        val error = IOException("No space left on device at /private/path/video.mp4?token=secret")

        val category = StorageFailureDiagnostics.classify(error)

        assertEquals("NO_SPACE_AVAILABLE", category)
        assertFalse(category.contains("secret"))
    }

    @Test
    fun classifiesPermissionAndReadOnlyFailures() {
        assertEquals(
            "ACCESS_DENIED",
            StorageFailureDiagnostics.classify(SecurityException("permission denied")),
        )
        assertEquals(
            "READ_ONLY_STORAGE",
            StorageFailureDiagnostics.classify(IOException("Read-only file system")),
        )
    }

    @Test
    fun distinguishesMissingFilesAndGenericIoFailures() {
        assertEquals(
            "FILE_UNAVAILABLE_OR_STORAGE_IO",
            StorageFailureDiagnostics.classify(FileNotFoundException("missing")),
        )
        assertEquals(
            "IO_FAILURE",
            StorageFailureDiagnostics.classify(IOException("write failed")),
        )
    }
}
