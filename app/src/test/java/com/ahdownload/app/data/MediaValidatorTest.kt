package com.ahdownload.app.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaValidatorTest {
    @Test
    fun acceptsPdfSignature() {
        val file = Files.createTempFile("ahdownload-", ".pdf").toFile()
        try {
            file.writeBytes("%PDF-1.7\n".toByteArray() + ByteArray(128))
            assertTrue(MediaValidator.validateFile(file, "pdf").isSuccess)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsHtmlPretendingToBePdf() {
        val file = Files.createTempFile("ahdownload-", ".pdf").toFile()
        try {
            file.writeText("<!doctype html><html>not media</html>")
            assertFalse(MediaValidator.validateFile(file, "pdf").isSuccess)
        } finally {
            file.delete()
        }
    }

    @Test
    fun acceptsZipSignature() {
        val file = Files.createTempFile("ahdownload-", ".zip").toFile()
        try {
            file.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00))
            assertTrue(MediaValidator.validateFile(file, "zip").isSuccess)
        } finally {
            file.delete()
        }
    }
}
