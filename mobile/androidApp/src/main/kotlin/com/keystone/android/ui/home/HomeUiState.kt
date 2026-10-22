package com.keystone.android.ui.home

import com.keystone.shared.core.AppError
import com.keystone.shared.domain.model.BackendInfo
import com.keystone.shared.domain.model.HomeDashboard

/** Everything the home screen renders. Immutable; the ViewModel emits a new copy on change. */
data class HomeUiState(
    val backend: BackendStatus = BackendStatus.Checking,
    /** The last dashboard we have (cached, so it shows instantly and offline). */
    val dashboard: HomeDashboard? = null,
    val refreshing: Boolean = false,
    /** The last refresh failed: show cached data with an "offline" note, or an error. */
    val refreshError: AppError? = null,
)

sealed interface BackendStatus {
    data object Checking : BackendStatus
    data class Connected(val info: BackendInfo) : BackendStatus
    data class Unreachable(val error: AppError) : BackendStatus
}
