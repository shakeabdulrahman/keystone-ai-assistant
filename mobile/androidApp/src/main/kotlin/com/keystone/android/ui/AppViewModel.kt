package com.keystone.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.android.auth.AuthService
import com.keystone.android.auth.AuthState
import com.keystone.android.push.PushTokens
import com.keystone.shared.domain.repository.AccountRepository
import com.keystone.shared.domain.repository.ConversationRepository
import com.keystone.shared.domain.repository.DeviceRepository
import com.keystone.shared.domain.repository.HomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** App-wide session state: who is signed in, and what happens when that changes. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val auth: AuthService,
    private val accounts: AccountRepository,
    private val conversations: ConversationRepository,
    private val home: HomeRepository,
    private val devices: DeviceRepository,
    private val pushTokens: PushTokens,
) : ViewModel() {

    val authState: StateFlow<AuthState> = auth.authState

    init {
        // Each time a (different) user signs in, register them with the backend. Failures
        // are fine: the backend also creates the user on their first API call.
        viewModelScope.launch {
            auth.authState
                .filterIsInstance<AuthState.SignedIn>()
                .map { it.uid }
                .distinctUntilChanged()
                .collect {
                    accounts.startSession()
                    // This phone now gets this user's push notifications.
                    withTimeoutOrNull(PUSH_TIMEOUT) { pushTokens.current()?.let { token -> devices.register(token) } }
                }
        }
    }

    /**
     * Stop this phone getting the user's pushes (needs their sign-in, so first), wipe
     * cached HR data, then end the Firebase session.
     */
    fun signOut() {
        viewModelScope.launch {
            // Bounded: offline, Firebase can take a long time to answer; sign-out must not wait.
            withTimeoutOrNull(PUSH_TIMEOUT) {
                pushTokens.current()?.let { token -> devices.unregister(token) }
                pushTokens.delete()
            }
            conversations.clearCache()
            home.clearCache()
            auth.signOut()
        }
    }

    private companion object {
        val PUSH_TIMEOUT = 5.seconds
    }
}
