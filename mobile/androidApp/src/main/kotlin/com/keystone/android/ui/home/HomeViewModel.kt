package com.keystone.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.android.push.PushEvents
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.repository.HomeRepository
import com.keystone.shared.domain.repository.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The home dashboard. Offline-first like history: the screen shows the cached dashboard
 * at once and refreshes it in the background (on open, on return to the screen, on pull,
 * and when a push says something changed).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
    private val homeRepository: HomeRepository,
    pushEvents: PushEvents,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var checkJob: Job? = null
    private var refreshJob: Job? = null

    /** A refresh was asked for while one was running: run once more afterwards. */
    private var refreshAgain = false

    init {
        viewModelScope.launch {
            homeRepository.observe().collect { cached -> _uiState.update { it.copy(dashboard = cached) } }
        }
        viewModelScope.launch { pushEvents.events.collect { refresh() } }
        refresh()
        checkBackend()
    }

    /** Pull to refresh, or the screen became visible again. */
    fun onRefresh() = refresh()

    fun onRetry() {
        checkBackend()
        refresh()
    }

    private fun refresh() {
        // One at a time, so requests never race. A request during a refresh (e.g. a push
        // that arrived after it started) is remembered, because that response may be older
        // than the change the push announced.
        if (refreshJob?.isActive == true) {
            refreshAgain = true
            return
        }
        refreshJob = viewModelScope.launch {
            do {
                refreshAgain = false
                _uiState.update { it.copy(refreshing = true) }
                val error = (homeRepository.refresh() as? AppResult.Failure)?.error
                _uiState.update { it.copy(refreshing = false, refreshError = error) }
            } while (refreshAgain)
        }
    }

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
