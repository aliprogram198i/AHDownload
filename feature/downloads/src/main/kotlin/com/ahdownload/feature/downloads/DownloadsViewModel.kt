package com.ahdownload.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DownloadsViewModel(
    private val repository: DownloadRepository,
    private val controls: DownloadControls,
) : ViewModel() {
    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeHistory().collect { _records.value = it }
        }
    }

    fun pauseAll() {
        _records.value
            .filter { it.status in ACTIVE_STATUSES }
            .forEach { controls.pause(it.task.id) }
    }

    fun resumeAll() {
        _records.value
            .filter { it.status in setOf(DownloadStatus.PAUSED, DownloadStatus.CANCELLED, DownloadStatus.FAILED) }
            .forEach { controls.resume(it) }
    }

    fun cancelAll() {
        _records.value
            .filter { it.status in ACTIVE_STATUSES }
            .forEach { controls.cancel(it.task.id) }
    }

    fun pause(record: DownloadRecord) {
        if (record.status in setOf(DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING)) {
            controls.pause(record.task.id)
        }
    }

    fun resume(record: DownloadRecord) {
        if (record.status in setOf(DownloadStatus.PAUSED, DownloadStatus.CANCELLED, DownloadStatus.FAILED)) {
            controls.resume(record)
        }
    }

    fun cancel(record: DownloadRecord) {
        if (record.status in setOf(
                DownloadStatus.QUEUED,
                DownloadStatus.PREPARING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED,
            )
        ) {
            controls.cancel(record.task.id)
        }
    }

    fun retry(record: DownloadRecord) {
        if (
            record.status == DownloadStatus.FAILED ||
            record.status == DownloadStatus.CANCELLED ||
            record.status == DownloadStatus.COMPLETED
        ) {
            controls.resume(record)
        }
    }

    fun deleteHistory(record: DownloadRecord) {
        if (record.status !in setOf(
                DownloadStatus.QUEUED,
                DownloadStatus.PREPARING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED,
            )
        ) {
            viewModelScope.launch {
                repository.delete(record.task.id)
            }
        }
    }

    class Factory(
        private val repository: DownloadRepository,
        private val controls: DownloadControls,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DownloadsViewModel(repository, controls) as T
    }
}

interface DownloadControls {
    fun pause(taskId: String)
    fun resume(record: DownloadRecord)
    fun cancel(taskId: String)
}
