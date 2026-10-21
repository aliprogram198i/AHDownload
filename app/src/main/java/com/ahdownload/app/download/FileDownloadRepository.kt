package com.ahdownload.app.download

import android.content.Context
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import kotlinx.coroutines.flow.Flow

/**
 * Compatibility façade for code/tests that still use the former implementation name.
 * All reads and writes are delegated to RoomDownloadRepository.
 */
@Deprecated("Use RoomDownloadRepository; legacy JSON is imported automatically.")
class FileDownloadRepository(context: Context) : DownloadRepository by RoomDownloadRepository(context)
