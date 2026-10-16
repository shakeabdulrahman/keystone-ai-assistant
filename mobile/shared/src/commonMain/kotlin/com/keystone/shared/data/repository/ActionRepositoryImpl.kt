package com.keystone.shared.data.repository

import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.dto.toDomain
import com.keystone.shared.data.remote.safeApiCall
import com.keystone.shared.domain.model.PendingAction
import com.keystone.shared.domain.repository.ActionRepository

class ActionRepositoryImpl(private val api: KeystoneApi) : ActionRepository {

    override suspend fun confirm(actionId: String): AppResult<PendingAction> =
        decide(actionId) { api.confirmAction(actionId).toDomain() }

    override suspend fun cancel(actionId: String): AppResult<PendingAction> =
        decide(actionId) { api.cancelAction(actionId).toDomain() }

    override suspend fun get(actionId: String): AppResult<PendingAction> =
        safeApiCall { api.getAction(actionId).toDomain() }

    /**
     * A 409 means "this can't be decided any more" (expired, already done, cancelled...).
     * The card should then show what actually happened, so we fetch the current state
     * instead of reporting an error.
     */
    private suspend fun decide(
        actionId: String,
        call: suspend () -> PendingAction,
    ): AppResult<PendingAction> {
        val result = safeApiCall { call() }
        if (result is AppResult.Failure && (result.error as? AppError.Http)?.status == 409) {
            return get(actionId)
        }
        return result
    }
}
