package com.keystone.shared.data.remote.dto

import com.keystone.shared.domain.model.PendingAction
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class FieldDto(
    @SerialName("label") val label: String,
    @SerialName("value") val value: String,
)

@Serializable
internal data class ActionResultDto(
    @SerialName("reference") val reference: String = "",
    @SerialName("message") val message: String = "",
)

@Serializable
internal data class ActionErrorDto(
    @SerialName("code") val code: String = "",
    @SerialName("message") val message: String = "",
)

/**
 * One shape for three sources: the `confirmation.required` event, a stored
 * `confirmation` part (plus current `status`/`result`/`error`), and the actions API
 * (which names the id `id` instead of `action_id`).
 */
@Serializable
internal data class ActionDto(
    @SerialName("action_id") val actionId: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("tool") val tool: String,
    @SerialName("title") val title: String,
    @SerialName("fields") val fields: List<FieldDto> = emptyList(),
    @SerialName("notes") val notes: List<String> = emptyList(),
    @SerialName("confirm_label") val confirmLabel: String = "Confirm",
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("status") val status: String = "pending",
    @SerialName("result") val result: ActionResultDto? = null,
    @SerialName("error") val error: ActionErrorDto? = null,
)

/** Throws IllegalArgumentException (incl. SerializationException) for unusable data. */
internal fun ActionDto.toDomain(): PendingAction = PendingAction(
    id = requireNotNull(actionId ?: id) { "action without id" },
    tool = tool,
    title = title,
    fields = fields.map { PendingAction.Field(it.label, it.value) },
    notes = notes,
    confirmLabel = confirmLabel,
    expiresAt = Instant.parse(expiresAt),
    status = PendingAction.Status.of(status),
    result = result?.let { PendingAction.Result(it.reference, it.message) },
    failureMessage = error?.message?.takeIf { it.isNotBlank() },
)
