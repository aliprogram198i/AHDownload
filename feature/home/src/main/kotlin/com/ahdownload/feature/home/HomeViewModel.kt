package com.ahdownload.feature.home

import androidx.lifecycle.ViewModel
import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.model.MediaLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HomeUiState(
    val url: String = "",
    val analyzing: Boolean = false,
    val result: MediaLink? = null,
    val error: String? = null,
)

class HomeViewModel(
    private val analyzer: LinkAnalyzer = LinkAnalyzer(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            url = value,
            result = null,
            error = null,
        )
    }

    fun analyze() {
        val current = _uiState.value.url
        _uiState.value = _uiState.value.copy(analyzing = true, error = null)

        val result = analyzer.analyze(current)
        _uiState.value = _uiState.value.copy(
            analyzing = false,
            result = result,
            error = if (result == null) "الرابط غير صالح أو غير مدعوم." else null,
        )
    }
}
