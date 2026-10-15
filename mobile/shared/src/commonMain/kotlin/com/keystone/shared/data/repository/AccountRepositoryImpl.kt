package com.keystone.shared.data.repository

import com.keystone.shared.core.AppResult
import com.keystone.shared.core.map
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.safeApiCall
import com.keystone.shared.domain.model.UserProfile
import com.keystone.shared.domain.repository.AccountRepository

class AccountRepositoryImpl(private val api: KeystoneApi) : AccountRepository {
    override suspend fun startSession(): AppResult<UserProfile> =
        safeApiCall { api.startSession() }.map { UserProfile(it.id, it.email, it.displayName) }

    override suspend fun deleteAccount(): AppResult<Unit> = safeApiCall { api.deleteAccount() }
}
