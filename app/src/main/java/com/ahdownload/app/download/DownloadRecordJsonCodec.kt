package com.ahdownload.app.download

import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadProcessingMode
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

internal class DownloadRecordJsonCodec(
    private val gson: Gson = Gson(),
) {
    private val type = object : TypeToken<List<DownloadRecord>>() {}.type

    fun encode(records: List<DownloadRecord>): String =
        gson.toJson(records, type)

    fun decode(json: String): List<DownloadRecord> {
        if (json.isBlank()) return emptyList()

        val normalized = runCatching {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray) return@runCatching "[]"

            root.asJsonArray.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val record = element.asJsonObject
                val task = record.getAsJsonObject("task") ?: return@forEach

                val processingMode = task.get("processingMode")
                if (processingMode == null || processingMode.isJsonNull) {
                    task.addProperty("processingMode", DownloadProcessingMode.Direct.name)
                }

                val requestHeaders = task.get("requestHeaders")
                if (requestHeaders == null || requestHeaders.isJsonNull) {
                    task.add("requestHeaders", com.google.gson.JsonObject())
                }

                val companionHeaders = task.get("companionAudioRequestHeaders")
                if (companionHeaders == null || companionHeaders.isJsonNull) {
                    task.add("companionAudioRequestHeaders", com.google.gson.JsonObject())
                }

                val companionManifest = task.get("companionAudioStreamingManifest")
                if (companionManifest == null || companionManifest.isJsonNull) {
                    task.addProperty("companionAudioStreamingManifest", false)
                }
            }

            root.toString()
        }.getOrElse { return emptyList() }

        return runCatching {
            gson.fromJson<List<DownloadRecord>>(normalized, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
