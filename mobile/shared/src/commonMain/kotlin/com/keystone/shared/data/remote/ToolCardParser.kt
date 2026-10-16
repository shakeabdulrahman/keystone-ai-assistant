package com.keystone.shared.data.remote

import com.keystone.shared.data.remote.dto.AppointmentsCardDto
import com.keystone.shared.data.remote.dto.HrRequestsCardDto
import com.keystone.shared.data.remote.dto.LeaveBalanceCardDto
import com.keystone.shared.data.remote.dto.LeaveRequestsCardDto
import com.keystone.shared.data.remote.dto.NotificationsCardDto
import com.keystone.shared.data.remote.dto.ProfileCardDto
import com.keystone.shared.data.remote.dto.TasksCardDto
import com.keystone.shared.domain.model.ToolCard
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Turns a tool result's `ui` + `data` into a typed [ToolCard].
 *
 * Returns null for unknown card types (a newer server) or malformed data, so one odd
 * card never breaks the chat: the assistant's text answer is still shown.
 */
internal object ToolCardParser {
    fun parse(ui: String?, data: JsonObject?): ToolCard? {
        if (ui == null || data == null) return null
        return try {
            when (ui) {
                "profile" -> decode<ProfileCardDto>(data).let {
                    ToolCard.Profile(
                        it.name, it.email, it.jobTitle, it.department, it.manager, it.location,
                        LocalDate.parse(it.startDate),
                    )
                }
                "leave_balance" -> decode<LeaveBalanceCardDto>(data).let { dto ->
                    ToolCard.LeaveBalance(
                        dto.year,
                        dto.items.map { ToolCard.LeaveBalance.Item(it.type, it.entitled, it.used, it.remaining) },
                    )
                }
                "leave_requests" -> ToolCard.LeaveRequests(
                    decode<LeaveRequestsCardDto>(data).items.map {
                        ToolCard.LeaveRequests.Item(
                            it.id, it.type, LocalDate.parse(it.start), LocalDate.parse(it.end), it.days, it.status,
                        )
                    },
                )
                "hr_requests" -> ToolCard.HrRequests(
                    decode<HrRequestsCardDto>(data).items.map {
                        ToolCard.HrRequests.Item(it.id, it.category, it.title, it.status, LocalDate.parse(it.updatedOn))
                    },
                )
                "tasks" -> ToolCard.Tasks(
                    decode<TasksCardDto>(data).items.map {
                        ToolCard.Tasks.Item(
                            it.id, it.title, it.category, LocalDate.parse(it.due), it.status == "done", it.overdue,
                        )
                    },
                )
                "appointments" -> ToolCard.Appointments(
                    decode<AppointmentsCardDto>(data).items.map {
                        ToolCard.Appointments.Item(
                            it.id, it.title, it.with, Instant.parse(it.start), Instant.parse(it.end), it.location,
                        )
                    },
                )
                "notifications" -> ToolCard.Notifications(
                    decode<NotificationsCardDto>(data).items.map {
                        ToolCard.Notifications.Item(it.id, it.title, it.body, Instant.parse(it.createdAt), it.read)
                    },
                )
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            // Covers SerializationException (a subclass) and malformed dates.
            null
        }
    }

    private inline fun <reified T> decode(data: JsonObject): T = KeystoneJson.decodeFromJsonElement(serializer<T>(), data)
}
