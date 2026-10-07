package com.ahdownload.app.favorites

import android.content.Context
import com.ahdownload.domain.favorites.FavoriteItem
import com.ahdownload.domain.favorites.FavoriteKey
import com.ahdownload.domain.favorites.FavoriteRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FavoritesStore(context: Context) : FavoriteRepository {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        "ahdownload_favorites",
        Context.MODE_PRIVATE,
    )
    private val gson = Gson()
    private val lock = Any()
    private val _favorites = MutableStateFlow(readPersisted())
    private val favorites: StateFlow<List<FavoriteItem>> = _favorites

    override fun observe() = favorites

    override fun isFavorite(url: String): Boolean =
        synchronized(lock) {
            val key = FavoriteKey.fromUrl(url)
            _favorites.value.any { FavoriteKey.fromUrl(it.url) == key }
        }

    override suspend fun setFavorite(item: FavoriteItem, favorite: Boolean) {
        synchronized(lock) {
            val key = FavoriteKey.fromUrl(item.url)
            val current = _favorites.value.toMutableList()
            current.removeAll { FavoriteKey.fromUrl(it.url) == key }
            if (favorite) current.add(item.copy(id = key))
            current.sortByDescending { it.createdAtEpochMs }
            val next = current.take(MAX_ITEMS)
            preferences.edit().putString(KEY_ITEMS, gson.toJson(next)).apply()
            _favorites.value = next
        }
    }

    private fun readPersisted(): List<FavoriteItem> =
        runCatching {
            val raw = preferences.getString(KEY_ITEMS, null).orEmpty()
            if (raw.isBlank()) return@runCatching emptyList()
            val type = object : TypeToken<List<FavoriteItem>>() {}.type
            gson.fromJson<List<FavoriteItem>>(raw, type)
                ?.filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
                ?.sortedByDescending { it.createdAtEpochMs }
                ?.take(MAX_ITEMS)
                .orEmpty()
        }.getOrDefault(emptyList())

    companion object {
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 100
    }
}
