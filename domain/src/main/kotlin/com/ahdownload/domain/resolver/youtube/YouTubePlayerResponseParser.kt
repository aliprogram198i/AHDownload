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
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class YouTubePlayerResponseParser {
    fun parse(html: String): ResolverResult {
        val playerResponse = extractPlayerResponse(html)
            ?: return ResolverResult.Failure(FailureCode.ResolverUnavailable, "لم يتم العثور على بيانات YouTube داخل الصفحة.")
        return parsePlayerResponse(playerResponse)
    }

    fun parsePlayerResponse(
        json: String,
        observedVideoUrls: List<String> = emptyList(),
        observedAudioUrls: List<String> = emptyList(),
    ): ResolverResult =
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

            val candidates = buildCandidates(root.obj("streamingData"), observedVideoUrls, observedAudioUrls)
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
            if (!decoded.isNullOrBlank() && decoded != current) {
                add(decoded)
                current = decoded
            }
        }

        for (candidate in candidates) {
            runCatching {
                JsonParser.parseString(candidate).asJsonObject
            }.getOrNull()?.let { return it }

            val unquoted = runCatching {
                JsonParser.parseString(candidate)
                    .takeIf(JsonElement::isJsonPrimitive)
                    ?.asString
            }.getOrNull()

            if (!unquoted.isNullOrBlank() && unquoted != candidate) {
                runCatching {
                    JsonParser.parseString(unquoted).asJsonObject
                }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun buildCandidates(
        streamingData: JsonObject?,
        observedVideoUrls: List<String>,
        observedAudioUrls: List<String>,
    ): List<MediaCandidate> {
        if (streamingData == null) return emptyList()

        // A ciphered YouTube format does not contain a directly usable URL.
        // Only associate its quality metadata with an exact browser-observed
        // URL for the same itag and media type. Never use the unsigned URL
        // embedded inside signatureCipher directly.
        val observedVideoUrlsByItag = observedUrlsByItag(observedVideoUrls)
        val observedAudioUrlsByItag = observedUrlsByItag(observedAudioUrls)

        return sequenceOf(streamingData.array("formats"), streamingData.array("adaptiveFormats"))
            .filterNotNull()
            .flatMap { it.asSequence() }
            .mapNotNull {
                it.takeIf(JsonElement::isJsonObject)
                    ?.asJsonObject
                    ?.toCandidate(observedVideoUrlsByItag, observedAudioUrlsByItag)
            }
            .distinctBy { it.id }
            .toList()
    }

    private fun observedUrlsByItag(urls: List<String>): Map<String, List<String>> =
        urls.asSequence()
            .filter(::isHttpMediaUrl)
            .mapNotNull { url -> queryParameter(url, "itag")?.let { it to url } }
            .groupBy({ it.first }, { it.second })

    private fun JsonObject.toCandidate(
        observedVideoUrlsByItag: Map<String, List<String>>,
        observedAudioUrlsByItag: Map<String, List<String>>,
    ): MediaCandidate? {
        val formatId = string("itag") ?: return null
        val mimeType = string("mimeType") ?: return null
        val mediaKind = when {
            mimeType.startsWith("video/") -> MediaKind.Video
            mimeType.startsWith("audio/") -> MediaKind.Audio
            else -> return null
        }
        val directUrl = string("url")?.takeIf(::isHttpMediaUrl)
        val ciphered = !string("signatureCipher").isNullOrBlank() ||
            !string("cipher").isNullOrBlank()
        val observedUrlsByItag = when (mediaKind) {
            MediaKind.Video -> observedVideoUrlsByItag
            MediaKind.Audio -> observedAudioUrlsByItag
            else -> emptyMap()
        }
        val observedUrl = if (directUrl == null && ciphered) {
            observedUrlsByItag[formatId]?.firstOrNull()
        } else {
            null
        }
        val url = directUrl ?: observedUrl ?: return null
        val codecs = Regex("""codecs="([^"]+)"""").find(mimeType)?.groupValues?.get(1)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
        val videoCodec = codecs.firstOrNull()
        val audioCodec = codecs.drop(1).firstOrNull()

        return MediaCandidate(
            id = formatId,
            sourceUrl = url,
            format = MediaFormat(
                id = formatId,
                kind = mediaKind,
                container = mimeType.toContainer(),
                videoCodec = if (mediaKind == MediaKind.Video) videoCodec else null,
                audioCodec = when {
                    mediaKind == MediaKind.Audio -> videoCodec
                    mediaKind == MediaKind.Video -> audioCodec
                    else -> null
                },
                width = int("width"),
                height = int("height"),
                fps = double("fps"),
                bitrateKbps = int("bitrate")?.div(1000),
                fileSizeBytes = long("contentLength"),
                hasVideo = mediaKind == MediaKind.Video,
                hasAudio = mediaKind == MediaKind.Audio ||
                    (mediaKind == MediaKind.Video && audioCodec != null),
            ),
        )
    }

    private fun String.toContainer(): MediaContainer =
        when (substringAfter('/', "").substringBefore(';').lowercase()) {
            "mp4" -> MediaContainer.Mp4
            "webm" -> MediaContainer.Webm
            "x-matroska" -> MediaContainer.Mkv
            "quicktime" -> MediaContainer.Mov
            "3gpp" -> MediaContainer.ThreeGp
            "x-msvideo" -> MediaContainer.Avi
            "mpeg" -> MediaContainer.Mp3
            "mp3" -> MediaContainer.Mp3
            "m4a" -> MediaContainer.M4a
            "x-m4a" -> MediaContainer.M4a
            "aac" -> MediaContainer.Aac
            "ogg" -> MediaContainer.Ogg
            "opus" -> MediaContainer.Ogg
            "flac" -> MediaContainer.Flac
            else -> MediaContainer.Unknown
        }

    private fun isHttpMediaUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)

    private fun queryParameter(url: String, name: String): String? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        return query.split('&').firstNotNullOfOrNull { component ->
            val separator = component.indexOf('=')
            if (separator <= 0) return@firstNotNullOfOrNull null
            val key = runCatching {
                URLDecoder.decode(component.substring(0, separator), StandardCharsets.UTF_8.toString())
            }.getOrDefault(component.substring(0, separator))
            if (key != name) return@firstNotNullOfOrNull null
            runCatching {
                URLDecoder.decode(component.substring(separator + 1), StandardCharsets.UTF_8.toString())
            }.getOrDefault(component.substring(separator + 1))
        }
    }

    private fun extractPlayerResponse(html: String): String? {
        val markers = listOf(
            "var ytInitialPlayerResponse =",
            "ytInitialPlayerResponse =",
            "ytInitialPlayerResponse:",
            "ytplayer.config.args.player_response=",
            "\"player_response\":",
            "\"playerResponse\":",
            "player_response=",
        )

        for (marker in markers) {
            var searchFrom = 0
            while (searchFrom < html.length) {
                val markerStart = html.indexOf(marker, searchFrom)
                if (markerStart < 0) break

                val extracted = extractValueAfterMarker(
                    html = html,
                    valueStart = markerStart + marker.length,
                )
                if (extracted != null) return extracted
                searchFrom = markerStart + marker.length
            }
        }
        return null
    }

    private fun extractValueAfterMarker(html: String, valueStart: Int): String? {
        var index = valueStart
        while (index < html.length && (html[index].isWhitespace() || html[index] == ':' || html[index] == '=')) {
            index++
        }
        if (index >= html.length) return null

        return when (html[index]) {
            '{' -> {
                val end = findJsonObjectEnd(html, index)
                if (end > index) html.substring(index, end + 1) else null
            }

            '"' -> {
                val end = findJsonStringEnd(html, index)
                if (end <= index) return null
                val quoted = html.substring(index, end + 1)
                runCatching { JsonParser.parseString(quoted).asString }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }

            else -> null
        }
    }

    private fun findJsonObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun findJsonStringEnd(text: String, start: Int): Int {
        var escaped = false
        for (index in start + 1 until text.length) {
            val char = text[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                continue
            }
            if (char == '"') return index
        }
        return -1
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun JsonObject.int(name: String): Int? = string(name)?.toIntOrNull()
    private fun JsonObject.long(name: String): Long? = string(name)?.toLongOrNull()
    private fun JsonObject.double(name: String): Double? = string(name)?.toDoubleOrNull()
    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.asJsonObject

    private fun JsonObject.array(name: String): JsonArray? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.asJsonArray

    private fun JsonElement.objValue(name: String): String? =
        takeIf(JsonElement::isJsonObject)?.asJsonObject?.string(name)

    private fun JsonArray.lastOrNull(): JsonElement? =
        if (size() == 0) null else get(size() - 1)
}
