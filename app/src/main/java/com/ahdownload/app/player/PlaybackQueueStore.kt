package com.ahdownload.app.player

import android.content.Context
import org.json.JSONArray

internal class PlaybackQueueStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun readTaskIds(): List<String> = runCatching {
        val encoded = preferences.getString(ORDER_KEY, null) ?: return emptyList()
        val array = JSONArray(encoded)
        buildList {
            for (index in 0 until array.length()) {
                val id = array.optString(index).trim()
                if (id.isNotBlank()) add(id)
            }
        }
    }.getOrDefault(emptyList())

    fun writeTaskIds(taskIds: List<String>) {
        val array = JSONArray()
        taskIds.asSequence().filter { it.isNotBlank() }.distinct().forEach(array::put)
        preferences.edit().putString(ORDER_KEY, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "ahdownload_playback_queue"
        const val ORDER_KEY = "ordered_task_ids"
    }
}
