package com.keystone.shared.data.repository

import com.keystone.shared.core.AppResult
import com.keystone.shared.data.local.CachedJsonDao
import com.keystone.shared.data.local.CachedJsonEntity
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.KeystoneJson
import com.keystone.shared.data.remote.dto.HomeDto
import com.keystone.shared.data.remote.dto.toDomain
import com.keystone.shared.data.remote.safeApiCall
import com.keystone.shared.domain.model.HomeDashboard
import com.keystone.shared.domain.repository.DeviceRepository
import com.keystone.shared.domain.repository.HomeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

class HomeRepositoryImpl(
    private val api: KeystoneApi,
    private val cache: CachedJsonDao,
    private val clock: Clock = Clock.System,
) : HomeRepository {

    override fun observe(): Flow<HomeDashboard?> = cache.observe(KEY).map { entity ->
        entity?.let {
            // A cache written by an older app version may not parse: treat it as empty.
            runCatching { KeystoneJson.decodeFromString(HomeDto.serializer(), it.json).toDomain() }.getOrNull()
        }
    }

    override suspend fun refresh(): AppResult<Unit> {
        val result = safeApiCall {
            val dto = api.home()
            dto.toDomain() // validate before caching: never store something we can't show
            dto
        }
        return when (result) {
            is AppResult.Success -> {
                val json = KeystoneJson.encodeToString(HomeDto.serializer(), result.value)
                cache.put(CachedJsonEntity(KEY, json, clock.now().toEpochMilliseconds()))
                AppResult.Success(Unit)
            }
            is AppResult.Failure -> result
        }
    }

    override suspend fun clearCache() = cache.deleteAll()

    private companion object {
        const val KEY = "home"
    }
}

class DeviceRepositoryImpl(private val api: KeystoneApi, private val platform: String) : DeviceRepository {
    override suspend fun register(token: String): AppResult<Unit> = safeApiCall { api.registerDevice(token, platform) }

    override suspend fun unregister(token: String): AppResult<Unit> = safeApiCall { api.unregisterDevice(token) }
}
