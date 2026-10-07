package com.ahdownload.domain.search

data class ContentSearchItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val durationLabel: String? = null,
    val channelLabel: String? = null,
)

interface ContentSearchProvider {
    suspend fun search(query: String, limit: Int = 12): List<ContentSearchItem>
}
