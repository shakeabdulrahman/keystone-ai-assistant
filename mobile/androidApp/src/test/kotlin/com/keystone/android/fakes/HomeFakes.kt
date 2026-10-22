package com.keystone.android.fakes

import com.keystone.android.push.PushTokens
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.HomeDashboard
import com.keystone.shared.domain.repository.DeviceRepository
import com.keystone.shared.domain.repository.HomeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Cache + scripted refresh results. A successful refresh "downloads" [server]. */
class FakeHomeRepository(cached: HomeDashboard? = null) : HomeRepository {
    val cache = MutableStateFlow(cached)
    var server: HomeDashboard? = null
    var nextResult: AppResult<Unit> = AppResult.Success(Unit)
    var gate: CompletableDeferred<Unit>? = null
    var refreshes = 0
    var cleared = false

    override fun observe(): Flow<HomeDashboard?> = cache

    override suspend fun refresh(): AppResult<Unit> {
        refreshes++
        gate?.await()
        if (nextResult is AppResult.Success) server?.let { cache.value = it }
        return nextResult
    }

    override suspend fun clearCache() {
        cleared = true
        cache.value = null
    }
}

class FakeDeviceRepository(private val onCall: (String) -> Unit = {}) : DeviceRepository {
    val calls = mutableListOf<String>()

    override suspend fun register(token: String): AppResult<Unit> {
        calls += "register:$token"
        onCall("register")
        return AppResult.Success(Unit)
    }

    override suspend fun unregister(token: String): AppResult<Unit> {
        calls += "unregister:$token"
        onCall("unregister")
        return AppResult.Success(Unit)
    }
}

class FakePushTokens(var token: String? = "push-token-1") : PushTokens {
    var deleted = 0
    override suspend fun current(): String? = token
    override suspend fun delete() {
        deleted++
        token = token?.let { "$it-new" }
    }
}
