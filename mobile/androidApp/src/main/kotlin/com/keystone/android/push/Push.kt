package com.keystone.android.push

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** This install's push token. Behind an interface so ViewModels are tested without Firebase. */
interface PushTokens {
    /** Null if push isn't available (no Play services, offline at first launch). */
    suspend fun current(): String?

    /** On sign-out: the next account on this phone gets a fresh token. */
    suspend fun delete()
}

/** "Something changed on the server": screens that show HR data refresh when this fires. */
@Singleton
class PushEvents @Inject constructor() {
    private val _events = MutableSharedFlow<PushEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PushEvent> = _events.asSharedFlow()

    fun emit(event: PushEvent) {
        _events.tryEmit(event)
    }
}

data class PushEvent(val kind: String, val ref: String?)
