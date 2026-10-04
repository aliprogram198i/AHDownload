package com.ahdownload.app.data

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

object MediaValidator {
    private const val SIGNATURE_SAMPLE_BYTES = 4096

    fun validateFile(file: File, extension: String): Result<Unit> = runCatching {
        require(file.exists() && file.isFile) { "MEDIA_FILE_MISSING" }
        require(file.length() > 0L) { "MEDIA_FILE_EMPTY" }
        val ext = extension.lowercase(Locale.US).removePrefix(".")
        val sample = ByteArray(minOf(SIGNATURE_SAMPLE_BYTES.toLong(), file.length()).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(sample)
        }

        val textPrefix = sample.copyOf(minOf(sample.size, 64))
            .toString(Charsets.UTF_8)
            .trimStart('﻿', ' ', '\t', '\r', '\n')
            .lowercase(Locale.US)
        require(
            !textPrefix.startsWith("<!doctype html") &&
                !textPrefix.startsWith("<html") &&
                !textPrefix.startsWith("<?xml") &&
                !textPrefix.startsWith("{"error"")
        ) { "MEDIA_HTML_OR_ERROR_RESPONSE" }

        val valid = when (ext) {
            "mp4", "m4v", "m4a", "mov" -> hasAscii(sample, "ftyp") ||
                hasAscii(sample, "moov") || hasAscii(sample, "mdat") || hasAscii(sample, "styp")
            "webm", "mkv" -> sample.size >= 4 &&
                sample[0] == 0x1A.toByte() && sample[1] == 0x45.toByte() &&
                sample[2] == 0xDF.toByte() && sample[3] == 0xA3.toByte()
            "mp3" -> hasAscii(sample, "ID3") ||
                (sample.size >= 2 && sample[0] == 0xFF.toByte() && (sample[1].toInt() and 0xE0) == 0xE0)
            else -> true
        }
        require(valid) { "MEDIA_SIGNATURE_MISMATCH" }
    }

    private fun hasAscii(bytes: ByteArray, value: String): Boolean {
        val target = value.toByteArray(Charsets.US_ASCII)
        return bytes.indices.any { i ->
            i + target.size <= bytes.size && target.indices.all { j -> bytes[i + j] == target[j] }
        }
    }
}
