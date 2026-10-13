package com.ahdownload.domain.search

enum class ContentSearchKind {
    VIDEO,
    SHORT,
    CHANNEL,
}

data class ContentSearchItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val durationLabel: String? = null,
    val channelLabel: String? = null,
    val kind: ContentSearchKind = ContentSearchKind.VIDEO,
    val subtitle: String? = null,
)

interface ContentSearchProvider {
    suspend fun search(query: String, limit: Int = 12): List<ContentSearchItem>
}
