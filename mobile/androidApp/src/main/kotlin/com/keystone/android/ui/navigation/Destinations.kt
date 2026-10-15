package com.keystone.android.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations (Navigation Compose 2.8+). */
@Serializable
data object HomeDestination

@Serializable
data object ConversationsDestination

/** Opens a new chat, or an existing one when [conversationId] is set. */
@Serializable
data class ChatDestination(val conversationId: String? = null)
