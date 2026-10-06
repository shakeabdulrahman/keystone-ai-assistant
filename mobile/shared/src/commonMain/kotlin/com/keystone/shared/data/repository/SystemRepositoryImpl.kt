package com.keystone.shared.data.repository

import com.keystone.shared.core.AppResult
import com.keystone.shared.core.map
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.safeApiCall
import com.keystone.shared.domain.model.BackendInfo
import com.keystone.shared.domain.repository.SystemRepository

class SystemRepositoryImpl(private val api: KeystoneApi) : SystemRepository {
    override suspend fun backendInfo(): AppResult<BackendInfo> =
        safeApiCall { api.meta() }.map { dto ->
            BackendInfo(name = dto.name, version = dto.version, environment = dto.environment)
        }
}
