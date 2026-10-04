package com.ahdownload.app.data

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

object MediaValidator {
    fun validateFile(file: File, extension: String): Result<Unit> = runCatching {
        require(file.exists() && file.isFile) { "MEDIA_FILE_MISSING" }
        require(file.length() > 0L) { "MEDIA_FILE_EMPTY" }
        val ext = extension.lowercase(Locale.US).removePrefix(".")
        val header = ByteArray(16)
        RandomAccessFile(file, "r").use { raf ->
            raf.read(header)
        }
        val valid = when (ext) {
            "mp4", "m4v", "m4a", "mov" -> hasAscii(header, "ftyp")
            "webm", "mkv" -> header.size >= 4 &&
                header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
            "mp3" -> hasAscii(header, "ID3") ||
                (header.size >= 2 && header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0)
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
