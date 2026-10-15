package com.keystone.android.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.Conversation
import com.keystone.shared.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationsUiState(
    /** Always the cached list: shown instantly and also offline. */
    val conversations: List<Conversation> = emptyList(),
    /** False until the first refresh attempt finishes (to tell "loading" from "empty"). */
    val hasRefreshedOnce: Boolean = false,
    val isRefreshing: Boolean = false,
    val refreshError: AppError? = null,
    val canLoadMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** A rename/delete failed; it has already been rolled back in the list. */
    val actionError: AppError? = null,
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val repository: ConversationRepository,
) : ViewModel() {

    private val local = MutableStateFlow(ConversationsUiState())

    val uiState: StateFlow<ConversationsUiState> =
        combine(repository.observeConversations(), local) { cached, state ->
            state.copy(conversations = cached)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState())

    init {
        onRefresh()
    }

    fun onRefresh() {
        if (local.value.isRefreshing) return
        local.update { it.copy(isRefreshing = true, refreshError = null) }
        viewModelScope.launch {
            val result = repository.refreshConversations()
            local.update {
                it.copy(
                    isRefreshing = false,
                    hasRefreshedOnce = true,
                    refreshError = (result as? AppResult.Failure)?.error,
                    canLoadMore = when (result) {
                        is AppResult.Success -> result.value
                        is AppResult.Failure -> it.canLoadMore
                    },
                )
            }
        }
    }

    fun onLoadMore() {
        val state = local.value
        if (state.isLoadingMore || !state.canLoadMore) return
        local.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val result = repository.loadMoreConversations()
            local.update {
                it.copy(
                    isLoadingMore = false,
                    canLoadMore = when (result) {
                        is AppResult.Success -> result.value
                        is AppResult.Failure -> true // let the user try again
                    },
                    actionError = (result as? AppResult.Failure)?.error,
                )
            }
        }
    }

    fun onRename(conversationId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val result = repository.rename(conversationId, clean.take(MAX_TITLE_CHARS))
            if (result is AppResult.Failure) local.update { it.copy(actionError = result.error) }
        }
    }

    fun onDelete(conversationId: String) {
        viewModelScope.launch {
            val result = repository.delete(conversationId)
            if (result is AppResult.Failure) local.update { it.copy(actionError = result.error) }
        }
    }

    fun onActionErrorShown() = local.update { it.copy(actionError = null) }

    companion object {
        const val MAX_TITLE_CHARS = 120 // backend limit
    }
}
