package com.keystone.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.android.auth.AuthService
import com.keystone.android.auth.AuthState
import com.keystone.shared.domain.repository.AccountRepository
import com.keystone.shared.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-wide session state: who is signed in, and what happens when that changes. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val auth: AuthService,
    private val accounts: AccountRepository,
    private val conversations: ConversationRepository,
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
                .collect { accounts.startSession() }
        }
    }

    /** Wipe cached HR data first, then end the Firebase session. */
    fun signOut() {
        viewModelScope.launch {
            conversations.clearCache()
            auth.signOut()
        }
    }
}
