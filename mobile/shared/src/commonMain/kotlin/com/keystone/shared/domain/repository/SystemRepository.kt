package com.keystone.shared.domain.repository

import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.BackendInfo

/** System-level information about the backend. The UI depends on this interface, never on Ktor. */
interface SystemRepository {
    suspend fun backendInfo(): AppResult<BackendInfo>
}
