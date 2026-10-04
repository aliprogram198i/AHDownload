package com.ahdownload.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ahdownload.app.data.DownloadRepository
import com.ahdownload.app.domain.DownloadJob
import com.ahdownload.app.domain.DownloadStatus
import com.ahdownload.app.domain.DownloadLifecyclePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DownloadRepository.get(application)
    private val filter = MutableStateFlow(Filter.ALL)

    val state: StateFlow<List<DownloadJob>> = combine(repository.jobs, filter) { jobs, selected ->
        jobs.filter { selected.matches(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(value: Filter) { filter.value = value }
    fun retry(id: String) { repository.retry(id) }
    fun refreshAndRetry(id: String) { viewModelScope.launch { repository.refreshAndRetry(id) } }
    fun toggleFavorite(id: String) { repository.toggleFavorite(id) }
    fun cancel(id: String) { repository.cancel(id) }
    fun delete(id: String) { repository.delete(id) }

    enum class Filter {
        ALL, ACTIVE, COMPLETED, FAILED, FAVORITES;

        fun matches(job: DownloadJob): Boolean = when (this) {
            ALL -> true
            ACTIVE -> job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.DOWNLOADING || job.status == DownloadStatus.RETRYING
            COMPLETED -> job.status == DownloadStatus.COMPLETED
            FAILED -> job.status == DownloadStatus.FAILED
            FAVORITES -> job.favorite
        }
    }
}
