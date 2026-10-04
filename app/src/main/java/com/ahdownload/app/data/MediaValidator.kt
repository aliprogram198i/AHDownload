package com.ahdownload.app.data

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Validates the bytes that were actually downloaded.
 *
 * CDN media URLs (especially signed social-media URLs) can expire or return a
 * non-media response without changing the URL. Keep this validator strict,
 * but inspect enough of the ISO-BMFF header to avoid false negatives when
 * MP4/M4A metadata is not at the very beginning of the file.
 */
object MediaValidator {
    private const val SIGNATURE_SAMPLE_BYTES = 64 * 1024

    fun validateFile(file: File, extension: String): Result<Unit> = runCatching {
        require(file.exists() && file.isFile) { "MEDIA_FILE_MISSING" }
        require(file.length() > 0L) { "MEDIA_FILE_EMPTY" }

        val ext = extension.lowercase(Locale.US).removePrefix(".")
        val sample = ByteArray(minOf(SIGNATURE_SAMPLE_BYTES.toLong(), file.length()).toInt())
        RandomAccessFile(file, "r").use { raf -> raf.readFully(sample) }

        val textPrefix = sample.copyOf(minOf(sample.size, 512))
            .let { String(it, Charsets.UTF_8) }
            .trimStart('﻿', ' ', '\t', '\r', '\n')
            .lowercase(Locale.US)

        require(
            !textPrefix.startsWith("<!doctype html") &&
                !textPrefix.startsWith("<html") &&
                !textPrefix.startsWith("<?xml") &&
                !textPrefix.startsWith("{"error"") &&
                !textPrefix.startsWith("{"status":"error"")
        ) { "MEDIA_HTML_OR_ERROR_RESPONSE" }

        val valid = when (ext) {
            "mp4", "m4v", "m4a", "mov" -> isIsoBmff(sample)
            "webm", "mkv" -> hasEbmlHeader(sample)
            "mp3" -> isMp3(sample)
            else -> true
        }

        require(valid) { "MEDIA_SIGNATURE_MISMATCH" }
    }

    private fun isIsoBmff(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false

        // ISO-BMFF normally starts with ftyp, but valid fragmented/streamed
        // files may expose moov/mdat/styp after other boxes. Scan a bounded
        // prefix and also validate plausible box headers.
        if (hasAscii(bytes, "ftyp") || hasAscii(bytes, "styp") ||
            hasAscii(bytes, "moov") || hasAscii(bytes, "mdat")) {
            return true
        }

        var offset = 0
        var boxes = 0
        while (offset + 8 <= bytes.size && boxes < 64) {
            val size = readUInt32(bytes, offset)
            val type = asciiAt(bytes, offset + 4, 4)
            if (type == null) return false

            if (type in ISO_BOX_TYPES) return true
            if (size == 0L) return true
            if (size == 1L) {
                if (offset + 16 > bytes.size) return false
                val largeSize = readUInt64(bytes, offset + 8)
                if (largeSize < 16L || largeSize > bytes.size - offset) return false
                offset += largeSize.toInt()
            } else {
                if (size < 8L || size > bytes.size - offset) return false
                offset += size.toInt()
            }
            boxes++
        }
        return false
    }

    private fun hasEbmlHeader(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()

    private fun isMp3(bytes: ByteArray): Boolean =
        hasAscii(bytes, "ID3") ||
            (bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                (bytes[1].toInt() and 0xE0) == 0xE0)

    private fun hasAscii(bytes: ByteArray, value: String): Boolean {
        val target = value.toByteArray(Charsets.US_ASCII)
        return bytes.indices.any { i ->
            i + target.size <= bytes.size &&
                target.indices.all { j -> bytes[i + j] == target[j] }
        }
    }

    private fun asciiAt(bytes: ByteArray, offset: Int, length: Int): String? =
        if (offset >= 0 && offset + length <= bytes.size) {
            String(bytes, offset, length, Charsets.US_ASCII)
        } else null

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun readUInt64(bytes: ByteArray, offset: Int): Long =
        (readUInt32(bytes, offset) shl 32) or readUInt32(bytes, offset + 4)

    private val ISO_BOX_TYPES = setOf(
        "ftyp", "styp", "moov", "mdat", "moof", "sidx", "free", "skip", "wide"
    )
}
