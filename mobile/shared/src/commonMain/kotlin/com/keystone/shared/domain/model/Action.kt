package com.keystone.shared.domain.model

import kotlinx.datetime.Instant

/**
 * A change the assistant prepared (leave request, HR request, ...). Nothing happens until
 * the employee confirms it; then the server runs it exactly once.
 */
data class PendingAction(
    val id: String,
    val tool: String,
    val title: String,
    /** Exactly what will be submitted, as label/value rows. */
    val fields: List<Field>,
    val notes: List<String>,
    val confirmLabel: String,
    val expiresAt: Instant,
    val status: Status,
    val result: Result? = null,
    /** Why it failed, written for the employee. */
    val failureMessage: String? = null,
) {
    data class Field(val label: String, val value: String)

    data class Result(val reference: String, val message: String)

    enum class Status {
        Pending, Executing, Executed, Cancelled, Expired, Failed;

        val isOpen: Boolean get() = this == Pending

        companion object {
            /** Unknown future statuses count as Failed (closed): never show buttons for them. */
            fun of(value: String?): Status = when (value) {
                "pending" -> Pending
                "executing" -> Executing
                "executed" -> Executed
                "cancelled" -> Cancelled
                "expired" -> Expired
                else -> Failed
            }
        }
    }
}
