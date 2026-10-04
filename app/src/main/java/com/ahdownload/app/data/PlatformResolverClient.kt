package com.ahdownload.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ResolvedFormat(
    val id: String,
    val ext: String,
    val width: Int?,
    val height: Int?,
    val abr: Double?,
    val sizeBytes: Long?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val url: String,
    val mergeRequired: Boolean = false,
    val audioUrl: String? = null,
    val audioExt: String? = null,
    val httpHeaders: Map<String, String> = emptyMap(),
    val audioHeaders: Map<String, String> = emptyMap()
)

data class ResolvedMedia(
    val title: String,
    val thumbnail: String?,
    val durationSeconds: Double?,
    val extractor: String?,
    val source: String,
    val formats: List<ResolvedFormat>
)

class PlatformResolverClient(
    private val baseUrl: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(url: String): Result<ResolvedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            require(baseUrl.isNotBlank()) { "RESOLVER_NOT_CONFIGURED" }
            val payload = JSONObject().put("url", url).toString()
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/v1/resolve")
                .header("Accept", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                if (!response.isSuccessful) error(json.optString("code", "RESOLVER_FAILED"))
                val formatsJson = json.optJSONArray("formats") ?: error("NO_MEDIA_FORMATS")
                val formats = buildList {
                    for (i in 0 until formatsJson.length()) {
                        val f = formatsJson.getJSONObject(i)
                        val mediaUrl = f.optString("url")
                        if (mediaUrl.isBlank()) continue
                        add(
                            ResolvedFormat(
                                id = f.optString("id"),
                                ext = f.optString("ext"),
                                width = f.optInt("width").takeIf { f.has("width") && it > 0 },
                                height = f.optInt("height").takeIf { f.has("height") && it > 0 },
                                abr = f.optDouble("abr").takeIf { f.has("abr") },
                                sizeBytes = f.optLong("sizeBytes").takeIf { f.has("sizeBytes") && it > 0 },
                                hasVideo = f.optBoolean("hasVideo"),
                                hasAudio = f.optBoolean("hasAudio"),
                                url = mediaUrl,
                                mergeRequired = f.optBoolean("mergeRequired"),
                                audioUrl = f.optString("audioUrl").takeIf { it.isNotBlank() },
                                audioExt = f.optString("audioExt").takeIf { it.isNotBlank() },
                                httpHeaders = parseHeaders(f.optJSONObject("httpHeaders")),
                                audioHeaders = parseHeaders(f.optJSONObject("audioHeaders"))
                            )
                        )
                    }
                }
                require(formats.isNotEmpty()) { "NO_MEDIA_FORMATS" }
                ResolvedMedia(
                    title = json.optString("title", "AHDownload file"),
                    thumbnail = json.optString("thumbnail").takeIf { it.isNotBlank() },
                    durationSeconds = json.optDouble("duration").takeIf { json.has("duration") },
                    extractor = json.optString("extractor").takeIf { it.isNotBlank() },
                    source = json.optString("source", url),
                    formats = formats
                )
            }
        }
    }

    private fun parseHeaders(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key).trim()
            if (value.isNotBlank()) result[key] = value
        }
        return result
    }
}
