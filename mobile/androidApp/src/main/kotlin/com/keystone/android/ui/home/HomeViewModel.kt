package com.keystone.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.repository.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var checkJob: Job? = null

    init {
        checkBackend()
    }

    fun onRetry() = checkBackend()

    private fun checkBackend() {
        checkJob?.cancel() // a fast double-tap on Retry must not race two requests
        checkJob = viewModelScope.launch {
            _uiState.update { it.copy(backend = BackendStatus.Checking) }
            val status = when (val result = systemRepository.backendInfo()) {
                is AppResult.Success -> BackendStatus.Connected(result.value)
                is AppResult.Failure -> BackendStatus.Unreachable(result.error)
            }
            _uiState.update { it.copy(backend = status) }
        }
    }
}
