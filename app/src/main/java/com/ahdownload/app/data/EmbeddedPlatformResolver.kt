package com.ahdownload.app.data

import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class EmbeddedPlatformResolver {
    suspend fun resolve(url: String): Result<ResolvedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val module = Python.getInstance().getModule("resolver")
            val raw = module.callAttr("resolve_json", JSONObject().put("url", url).toString()).toString()
            val json = JSONObject(raw)
            if (json.has("error")) error(json.optString("error", "EXTRACTION_FAILED"))
            val array = json.optJSONArray("formats") ?: error("NO_MEDIA_FORMATS")
            val formats = buildList {
                for (i in 0 until array.length()) {
                    val f = array.getJSONObject(i)
                    val mediaUrl = f.optString("url")
                    if (mediaUrl.isBlank()) continue
                    add(ResolvedFormat(f.optString("id"),f.optString("ext"),f.optInt("width").takeIf { f.has("width") && it > 0 },f.optInt("height").takeIf { f.has("height") && it > 0 },f.optDouble("abr").takeIf { f.has("abr") },f.optLong("sizeBytes").takeIf { f.has("sizeBytes") && it > 0 },f.optBoolean("hasVideo"),f.optBoolean("hasAudio"),mediaUrl))
                }
            }
            require(formats.isNotEmpty()) { "NO_MEDIA_FORMATS" }
            ResolvedMedia(json.optString("title","AHDownload file"),json.optString("thumbnail").takeIf { it.isNotBlank() },json.optDouble("duration").takeIf { json.has("duration") },json.optString("extractor").takeIf { it.isNotBlank() },json.optString("source",url),formats)
        }
    }
}
