package com.ahdownload.app.download

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_history",
    indices = [
        Index(value = ["contentFingerprint"]),
        Index(value = ["status", "updatedAtEpochMs"]),
    ],
)
data class DownloadHistoryEntity(
    @PrimaryKey val taskId: String,
    val contentFingerprint: String?,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val recordJson: String,
)
