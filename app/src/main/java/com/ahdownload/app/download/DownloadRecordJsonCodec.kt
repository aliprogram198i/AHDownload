package com.ahdownload.app.download

import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadProcessingMode
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal class DownloadRecordJsonCodec(
    private val gson: Gson = Gson(),
) {
    private val type = object : TypeToken<List<DownloadRecord>>() {}.type

    fun encode(records: List<DownloadRecord>): String =
        gson.toJson(records, type)

    fun decode(json: String): List<DownloadRecord> =
        (gson.fromJson<List<DownloadRecord>>(json, type) ?: emptyList()).map { record ->
            record.copy(
                task = record.task.copy(
                    requestHeaders = runCatching { record.task.requestHeaders }.getOrNull() ?: emptyMap(),
                processingMode = runCatching { record.task.processingMode }.getOrNull()
                        ?: DownloadProcessingMode.Direct,
                ),
            )
        }
}
