package com.keystone.shared.domain.repository

import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.PendingAction

/**
 * Deciding on actions the assistant prepared. Both calls are safe to retry: confirming
 * an action that already ran returns the same result instead of running it again.
 */
interface ActionRepository {
    suspend fun confirm(actionId: String): AppResult<PendingAction>
    suspend fun cancel(actionId: String): AppResult<PendingAction>
    suspend fun get(actionId: String): AppResult<PendingAction>
}
