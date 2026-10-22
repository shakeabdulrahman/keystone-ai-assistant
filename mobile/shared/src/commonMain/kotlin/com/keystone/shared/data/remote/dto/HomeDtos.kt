package com.keystone.shared.data.remote.dto

import com.keystone.shared.domain.model.AttentionItem
import com.keystone.shared.domain.model.HomeCounts
import com.keystone.shared.domain.model.HomeDashboard
import com.keystone.shared.domain.model.LeaveSummary
import com.keystone.shared.domain.model.UpcomingMeeting
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class HomeDto(
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("leave") val leave: List<LeaveSummaryDto> = emptyList(),
    @SerialName("attention") val attention: List<AttentionDto> = emptyList(),
    @SerialName("upcoming") val upcoming: List<UpcomingDto> = emptyList(),
    @SerialName("counts") val counts: CountsDto = CountsDto(),
    @SerialName("suggestions") val suggestions: List<String> = emptyList(),
)

@Serializable
internal data class LeaveSummaryDto(
    @SerialName("type") val type: String,
    @SerialName("remaining") val remaining: Double,
    @SerialName("entitled") val entitled: Double,
)

@Serializable
internal data class AttentionDto(
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: String,
    @SerialName("severity") val severity: String = "normal",
    @SerialName("title") val title: String,
    @SerialName("detail") val detail: String = "",
    // Lenient: a null or non-string value must not break the whole dashboard.
    @SerialName("action") val action: Map<String, JsonElement?>? = null,
)

@Serializable
internal data class UpcomingDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("with") val with: String = "",
    @SerialName("start") val start: String,
    @SerialName("location") val location: String = "",
)

@Serializable
internal data class CountsDto(
    @SerialName("open_hr_requests") val openHrRequests: Int = 0,
    @SerialName("pending_leave_requests") val pendingLeaveRequests: Int = 0,
    @SerialName("unread_notifications") val unreadNotifications: Int = 0,
)

@Serializable
internal data class DeviceDto(
    @SerialName("token") val token: String,
    @SerialName("platform") val platform: String,
)

@Serializable
internal data class TokenDto(@SerialName("token") val token: String)

/** Throws IllegalArgumentException for unusable dates (the caller treats that as a failure). */
internal fun HomeDto.toDomain() = HomeDashboard(
    generatedAt = Instant.parse(generatedAt),
    firstName = firstName,
    leave = leave.map { LeaveSummary(it.type, it.remaining, it.entitled) },
    attention = attention.map { it.toDomain() },
    upcoming = upcoming.mapNotNull { m ->
        runCatching { UpcomingMeeting(m.id, m.title, m.with, Instant.parse(m.start), m.location) }.getOrNull()
    },
    counts = HomeCounts(counts.openHrRequests, counts.pendingLeaveRequests, counts.unreadNotifications),
    suggestions = suggestions,
)

private fun AttentionDto.toDomain() = AttentionItem(
    id = id,
    kind = when (kind) {
        "pending_action" -> AttentionItem.Kind.PendingAction
        "overdue_task" -> AttentionItem.Kind.OverdueTask
        "task_due_soon" -> AttentionItem.Kind.TaskDueSoon
        "leave_pending" -> AttentionItem.Kind.LeavePending
        "unread_notifications" -> AttentionItem.Kind.UnreadNotifications
        else -> AttentionItem.Kind.Other // newer server: still shown, with a generic icon
    },
    isUrgent = severity == "high",
    title = title,
    detail = detail,
    action = actionOf(action.orEmpty()),
)

private fun actionOf(raw: Map<String, JsonElement?>): AttentionItem.Action {
    fun text(key: String): String? = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (text("type")) {
        "ask" -> text("prompt")?.let { AttentionItem.Action.Ask(it) } ?: AttentionItem.Action.None
        "open_conversation" -> text("conversation_id")
            ?.let { AttentionItem.Action.OpenConversation(it, text("action_id")) }
            ?: AttentionItem.Action.None
        else -> AttentionItem.Action.None
    }
}
