package com.ahdownload.app.data

import com.ahdownload.app.BuildConfig
import com.ahdownload.app.domain.MediaFormat
import com.ahdownload.app.domain.MediaInfo
import com.ahdownload.app.domain.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional platform resolver client.
 *
 * The APK never treats a platform page as a media file. A resolver backend must be
 * explicitly configured at build time before this client can be used.
 */
class ResolverApiClient {
    private val baseUrl = BuildConfig.RESOLVER_BASE_URL.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank()

    suspend fun resolve(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.failure(IllegalStateException("RESOLVER_NOT_CONFIGURED"))

        runCatching {
            val body = JSONObject().put("url", url)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/v1/resolve")
                .post(body)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Resolver HTTP ${response.code}")
                val root = JSONObject(response.body?.string().orEmpty())
                val formatsJson = root.optJSONArray("formats") ?: error("RESOLVER_NO_FORMATS")
                val formats = buildList {
                    for (i in 0 until formatsJson.length()) {
                        val f = formatsJson.getJSONObject(i)
                        val hasVideo = f.optBoolean("hasVideo")
                        val hasAudio = f.optBoolean("hasAudio")
                        val type = when {
                            hasVideo -> MediaType.VIDEO
                            hasAudio -> MediaType.AUDIO
                            else -> MediaType.FILE
                        }
                        val urlValue = f.optString("url").takeIf { it.isNotBlank() } ?: continue
                        add(
                            MediaFormat(
                                id = f.optString("id", "format-$i"),
                                type = type,
                                container = f.optString("container").takeIf { it.isNotBlank() },
                                codec = f.optString("codec").takeIf { it.isNotBlank() },
                                resolution = f.optString("resolution").takeIf { it.isNotBlank() },
                                fps = f.optInt("fps").takeIf { it > 0 },
                                bitrate = f.optLong("bitrate").takeIf { it > 0 },
                                hasAudio = hasAudio,
                                hasVideo = hasVideo,
                                estimatedSize = f.optLong("estimatedSize").takeIf { it > 0 },
                                url = urlValue
                            )
                        )
                    }
                }
                if (formats.isEmpty()) error("RESOLVER_NO_USABLE_FORMATS")
                MediaInfo(
                    source = root.optString("source", url),
                    type = when {
                        formats.any { it.hasVideo } -> MediaType.VIDEO
                        formats.any { it.hasAudio } -> MediaType.AUDIO
                        else -> MediaType.FILE
                    },
                    title = root.optString("title").ifBlank { "AHDownload" },
                    thumbnailUrl = root.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                    durationMs = root.optLong("duration").takeIf { it > 0 },
                    sizeBytes = formats.mapNotNull { it.estimatedSize }.maxOrNull(),
                    formats = formats,
                    isDirect = false
                )
            }
        }
    }
}
