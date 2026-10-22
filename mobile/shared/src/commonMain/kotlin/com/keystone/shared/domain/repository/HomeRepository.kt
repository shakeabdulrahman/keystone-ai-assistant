package com.keystone.shared.domain.repository

import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.HomeDashboard
import kotlinx.coroutines.flow.Flow

/** Offline-first home dashboard: the last copy shows instantly, refresh updates it. */
interface HomeRepository {
    /** The cached dashboard (null before the first successful load). */
    fun observe(): Flow<HomeDashboard?>

    suspend fun refresh(): AppResult<Unit>

    /** On sign-out: it's personal HR data. */
    suspend fun clearCache()
}

/** Push-notification registration for this phone. */
interface DeviceRepository {
    suspend fun register(token: String): AppResult<Unit>

    suspend fun unregister(token: String): AppResult<Unit>
}
