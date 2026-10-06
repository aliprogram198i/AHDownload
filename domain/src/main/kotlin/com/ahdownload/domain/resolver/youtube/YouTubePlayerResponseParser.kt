package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import com.ahdownload.domain.resolver.ResolverResult
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class YouTubePlayerResponseParser {
    fun parse(html: String): ResolverResult {
        val playerResponse = extractPlayerResponse(html)
            ?: return ResolverResult.Failure(FailureCode.ResolverUnavailable, "لم يتم العثور على بيانات YouTube داخل الصفحة.")
        return parsePlayerResponse(playerResponse)
    }

    fun parsePlayerResponse(json: String): ResolverResult =
        runCatching {
            val root = parseJsonObject(json) ?: throw IllegalArgumentException("استجابة YouTube ليست JSON صالحًا.")
            val details = root.obj("videoDetails")
            val playability = root.obj("playabilityStatus")
            val playabilityStatus = playability?.string("status")
            if (playabilityStatus != null && playabilityStatus != "OK") {
                val reason = playability.string("reason") ?: "status=$playabilityStatus"
                return ResolverResult.Failure(
                    FailureCode.ResolverUnavailable,
                    "YouTube رفض تشغيل الفيديو: $reason",
                )
            }

            val candidates = buildCandidates(root.obj("streamingData"))
            if (candidates.isEmpty()) {
                ResolverResult.Failure(
                    FailureCode.NoCandidates,
                    "لم تُرجع YouTube صيغًا مباشرة قابلة للتنزيل؛ قد تتطلب الصيغة توقيعًا أو جلسة مصادقة.",
                )
            } else {
                ResolverResult.Success(
                    title = details?.string("title"),
                    thumbnailUrl = details?.obj("thumbnail")?.array("thumbnails")
                        ?.lastOrNull()?.objValue("url"),
                    durationMs = details?.string("lengthSeconds")?.toLongOrNull()?.times(1000L),
                    candidates = candidates,
                )
            }
        }.getOrElse { error ->
            ResolverResult.Failure(
                FailureCode.ResolverUnavailable,
                "تعذر تحليل استجابة YouTube: " + (error.message ?: error::class.simpleName.orEmpty()),
            )
        }


    private fun parseJsonObject(raw: String): JsonObject? {
        val candidates = linkedSetOf<String>()
        fun add(value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() }?.let(candidates::add)
        }
        add(raw)
        var current = raw.trim()
        repeat(2) {
            val decoded = runCatching {
                URLDecoder.decode(current, StandardCharsets.UTF_8.toString())
            }.getOrNull()
            if (decoded != null && decoded != current) {
                add(decoded)
                current = decoded
            }
        }
        for (candidate in candidates) {
            runCatching { JsonParser.parseString(candidate).asJsonObject }.getOrNull()?.let { return it }
            val unquoted = runCatching {
                JsonParser.parseString(candidate).takeIf(JsonElement::isJsonPrimitive)?.asString
            }.getOrNull()
            if (!unquoted.isNullOrBlank()) {
                runCatching { JsonParser.parseString(unquoted).asJsonObject }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun buildCandidates(streamingData: JsonObject?): List<MediaCandidate> {
        if (streamingData == null) return emptyList()
        return sequenceOf(streamingData.array("formats"), streamingData.array("adaptiveFormats"))
            .filterNotNull()
            .flatMap { it.asSequence() }
            .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.toCandidate() }
            .distinctBy { it.id }
            .toList()
    }

    private fun JsonObject.toCandidate(): MediaCandidate? {
        val url = string("url") ?: return null
        val mimeType = string("mimeType") ?: return null
        val mediaKind = when {
            mimeType.startsWith("video/") -> MediaKind.Video
            mimeType.startsWith("audio/") -> MediaKind.Audio
            else -> return null
        }
        val formatId = string("itag") ?: return null
        val codecs = Regex("""codecs="([^"]+)""").find(mimeType)?.groupValues?.get(1)
        return MediaCandidate(
            id = formatId,
            sourceUrl = url,
            format = MediaFormat(
                id = formatId,
                kind = mediaKind,
                container = mimeType.toContainer(),
                videoCodec = if (mediaKind == MediaKind.Video) codecs?.substringBefore(',') else null,
                audioCodec = if (mediaKind == MediaKind.Audio) codecs?.substringBefore(',') else null,
                width = int("width"),
                height = int("height"),
                fps = double("fps"),
                bitrateKbps = int("bitrate")?.div(1000),
                fileSizeBytes = long("contentLength"),
                hasVideo = mediaKind == MediaKind.Video,
                hasAudio = mediaKind == MediaKind.Audio,
            ),
        )
    }

    private fun String.toContainer(): MediaContainer {
        return when (substringAfter('/', "").substringBefore(';').lowercase()) {
            "mp4" -> MediaContainer.Mp4
            "webm" -> MediaContainer.Webm
            "quicktime" -> MediaContainer.Mov
            "mpeg" -> MediaContainer.Mp3
            "aac" -> MediaContainer.Aac
            "ogg" -> MediaContainer.Ogg
            "flac" -> MediaContainer.Flac
            else -> MediaContainer.Unknown
        }
    }

    private fun extractPlayerResponse(html: String): String? {
        val markers = listOf("var ytInitialPlayerResponse = ", "ytInitialPlayerResponse = ")
        for (marker in markers) {
            val start = html.indexOf(marker)
            if (start < 0) continue
            val objectStart = html.indexOf('{', start + marker.length)
            if (objectStart < 0) continue
            val end = findJsonObjectEnd(html, objectStart)
            if (end > objectStart) return html.substring(objectStart, end + 1)
        }
        return null
    }

    private fun findJsonObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return index }
            }
        }
        return -1
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.asString
    private fun JsonObject.int(name: String): Int? = string(name)?.toIntOrNull()
    private fun JsonObject.long(name: String): Long? = string(name)?.toLongOrNull()
    private fun JsonObject.double(name: String): Double? = string(name)?.toDoubleOrNull()
    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeUnless(JsonElement::isJsonNull)?.asJsonObject
    private fun JsonObject.array(name: String): JsonArray? = get(name)?.takeUnless(JsonElement::isJsonNull)?.asJsonArray
    private fun JsonElement.objValue(name: String): String? = takeIf(JsonElement::isJsonObject)?.asJsonObject?.string(name)
    private fun JsonArray.lastOrNull(): JsonElement? = if (size() == 0) null else get(size() - 1)
}
