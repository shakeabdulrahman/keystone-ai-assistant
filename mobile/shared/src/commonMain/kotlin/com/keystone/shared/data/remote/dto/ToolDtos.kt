package com.keystone.shared.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ToolStartedDto(
    @SerialName("call_id") val callId: String,
    @SerialName("tool") val tool: String,
    @SerialName("label") val label: String? = null,
)

@Serializable
internal data class ToolResultDto(
    @SerialName("call_id") val callId: String,
    @SerialName("tool") val tool: String,
    @SerialName("status") val status: String,
    @SerialName("ui") val ui: String? = null,
    @SerialName("data") val data: JsonObject? = null,
)

/**
 * A `tool_result` entry of a stored message's `parts` (same shape as a tool.result event).
 * Parts are kept as raw JSON and decoded one by one, so a future part type (citations,
 * confirmations) can never break history.
 */
@Serializable
internal data class ToolResultPartDto(
    @SerialName("type") val type: String,
    @SerialName("status") val status: String? = null,
    @SerialName("ui") val ui: String? = null,
    @SerialName("data") val data: JsonObject? = null,
)

// Card payloads, decoded only after the `ui` type says which one to expect.

@Serializable
internal data class ProfileCardDto(
    val name: String,
    val email: String? = null,
    @SerialName("job_title") val jobTitle: String,
    val department: String,
    val manager: String,
    val location: String,
    @SerialName("start_date") val startDate: String,
)

@Serializable
internal data class LeaveBalanceCardDto(val year: Int, val items: List<Item>) {
    @Serializable
    data class Item(val type: String, val entitled: Double, val used: Double, val remaining: Double)
}

@Serializable
internal data class LeaveRequestsCardDto(val items: List<Item>) {
    @Serializable
    data class Item(
        val id: String,
        val type: String,
        val start: String,
        val end: String,
        val days: Double,
        val status: String,
    )
}

@Serializable
internal data class HrRequestsCardDto(val items: List<Item>) {
    @Serializable
    data class Item(
        val id: String,
        val category: String,
        val title: String,
        val status: String,
        @SerialName("updated_on") val updatedOn: String,
    )
}

@Serializable
internal data class TasksCardDto(val items: List<Item>) {
    @Serializable
    data class Item(
        val id: String,
        val title: String,
        val category: String,
        val due: String,
        val status: String,
        val overdue: Boolean = false,
    )
}

@Serializable
internal data class AppointmentsCardDto(val items: List<Item>) {
    @Serializable
    data class Item(
        val id: String,
        val title: String,
        val with: String,
        val start: String,
        val end: String,
        val location: String,
    )
}

@Serializable
internal data class NotificationsCardDto(val items: List<Item>) {
    @Serializable
    data class Item(
        val id: String,
        val title: String,
        val body: String,
        @SerialName("created_at") val createdAt: String,
        val read: Boolean,
    )
}
