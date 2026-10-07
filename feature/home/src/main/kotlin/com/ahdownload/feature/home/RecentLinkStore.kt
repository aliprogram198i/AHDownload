package com.ahdownload.feature.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentLink(
    val url: String,
    val title: String?,
    val platform: String,
    val updatedAtEpochMs: Long,
    val thumbnailUrl: String? = null,
)

class RecentLinkStore(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<RecentLink> {
        val json = preferences.getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue
                    add(
                        RecentLink(
                            url = url,
                            title = item.optString("title").takeIf { it.isNotBlank() },
                            platform = item.optString("platform").ifBlank { "Unknown" },
                            thumbnailUrl = item.optString("thumbnailUrl").takeIf { it.startsWith("http://") || it.startsWith("https://") },
                            updatedAtEpochMs = item.optLong("updatedAt", 0L),
                        ),
                    )
                }
            }.sortedByDescending { it.updatedAtEpochMs }.take(MAX_ITEMS)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(url: String, title: String?, platform: String, thumbnailUrl: String? = null) {
        val cleaned = url.trim()
        if (cleaned.isBlank()) return
        val now = System.currentTimeMillis()
        val updated = list()
            .filterNot { it.url == cleaned }
            .toMutableList()
        updated.add(
            0,
            RecentLink(
                url = cleaned,
                title = title?.trim()?.takeIf { it.isNotBlank() },
                platform = platform,
                thumbnailUrl = thumbnailUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
                updatedAtEpochMs = now,
            ),
        )
        persist(updated.take(MAX_ITEMS))
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_RECENT).apply()
    }

    private fun persist(items: List<RecentLink>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("url", item.url)
                    put("title", item.title.orEmpty())
                    put("platform", item.platform)
                    put("thumbnailUrl", item.thumbnailUrl.orEmpty())
                    put("updatedAt", item.updatedAtEpochMs)
                },
            )
        }
        preferences.edit().putString(KEY_RECENT, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES = "ahdownload_recent_links"
        const val KEY_RECENT = "recent"
        const val MAX_ITEMS = 6
    }
}
