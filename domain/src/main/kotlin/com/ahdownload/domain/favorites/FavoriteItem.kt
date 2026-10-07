package com.ahdownload.domain.favorites

import kotlinx.coroutines.flow.Flow

data class FavoriteItem(
    val id: String,
    val url: String,
    val title: String?,
    val thumbnailUrl: String?,
    val createdAtEpochMs: Long,
) {
    init {
        require(id.isNotBlank()) { "Favorite id must not be blank" }
        require(url.startsWith("http://") || url.startsWith("https://")) { "Favorite url must be http/https" }
        require(createdAtEpochMs >= 0L) { "createdAtEpochMs must be >= 0" }
    }
}

interface FavoriteRepository {
    fun observe(): Flow<List<FavoriteItem>>
    fun isFavorite(url: String): Boolean
    suspend fun setFavorite(item: FavoriteItem, favorite: Boolean)
}

object FavoriteKey {
    fun fromUrl(url: String): String =
        url.trim()
            .lowercase()
            .removeSuffix("/")
            .replace(Regex("[#?].*$"), "")
}
