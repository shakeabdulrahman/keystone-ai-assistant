package com.keystone.android.ui.home

import com.keystone.shared.core.AppError
import com.keystone.shared.domain.model.BackendInfo

/** Everything the home screen renders. Immutable; the ViewModel emits a new copy on change. */
data class HomeUiState(
    val backend: BackendStatus = BackendStatus.Checking,
)

sealed interface BackendStatus {
    data object Checking : BackendStatus
    data class Connected(val info: BackendInfo) : BackendStatus
    data class Unreachable(val error: AppError) : BackendStatus
}
