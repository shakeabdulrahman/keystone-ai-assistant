package com.keystone.shared.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * Structured results the assistant got from a tool, ready to render as native UI.
 * One subtype per `ui` card type in the stream contract (contracts/README.md).
 */
sealed interface ToolCard {

    data class Profile(
        val name: String,
        val email: String?,
        val jobTitle: String,
        val department: String,
        val manager: String,
        val location: String,
        val startDate: LocalDate,
    ) : ToolCard

    data class LeaveBalance(val year: Int, val items: List<Item>) : ToolCard {
        data class Item(val type: String, val entitled: Double, val used: Double, val remaining: Double)
    }

    data class LeaveRequests(val items: List<Item>) : ToolCard {
        data class Item(
            val id: String,
            val type: String,
            val start: LocalDate,
            val end: LocalDate,
            val days: Double,
            val status: String, // pending | approved | rejected
        )
    }

    data class HrRequests(val items: List<Item>) : ToolCard {
        data class Item(
            val id: String,
            val category: String,
            val title: String,
            val status: String, // submitted | in_progress | resolved
            val updatedOn: LocalDate,
        )
    }

    data class Tasks(val items: List<Item>) : ToolCard {
        data class Item(
            val id: String,
            val title: String,
            val category: String,
            val due: LocalDate,
            val done: Boolean,
            val overdue: Boolean,
        )
    }

    data class Appointments(val items: List<Item>) : ToolCard {
        data class Item(
            val id: String,
            val title: String,
            val with: String,
            val start: Instant,
            val end: Instant,
            val location: String,
        )
    }

    data class Notifications(val items: List<Item>) : ToolCard {
        data class Item(val id: String, val title: String, val body: String, val createdAt: Instant, val read: Boolean)
    }
}
