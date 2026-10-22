package com.keystone.shared.domain.model

import kotlinx.datetime.Instant

/** The home dashboard: what needs the employee's attention right now. */
data class HomeDashboard(
    val generatedAt: Instant,
    val firstName: String,
    val leave: List<LeaveSummary>,
    /** Most urgent first. */
    val attention: List<AttentionItem>,
    val upcoming: List<UpcomingMeeting>,
    val counts: HomeCounts,
    val suggestions: List<String>,
)

data class LeaveSummary(val type: String, val remaining: Double, val entitled: Double)

data class AttentionItem(
    val id: String,
    val kind: Kind,
    val isUrgent: Boolean,
    val title: String,
    val detail: String,
    val action: Action,
) {
    enum class Kind { PendingAction, OverdueTask, TaskDueSoon, LeavePending, UnreadNotifications, Other }

    /** What tapping the item does. */
    sealed interface Action {
        /** Start a chat that asks [prompt]. */
        data class Ask(val prompt: String) : Action

        /** Reopen a conversation where a confirmation card is waiting. */
        data class OpenConversation(val conversationId: String, val actionId: String?) : Action

        data object None : Action
    }
}

data class UpcomingMeeting(
    val id: String,
    val title: String,
    val with: String,
    val start: Instant,
    val location: String,
)

data class HomeCounts(val openHrRequests: Int, val pendingLeaveRequests: Int, val unreadNotifications: Int)
