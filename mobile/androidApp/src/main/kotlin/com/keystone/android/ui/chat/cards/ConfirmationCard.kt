package com.keystone.android.ui.chat.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keystone.android.R
import com.keystone.android.ui.chat.ActionUi
import com.keystone.designsystem.component.StatusPill
import com.keystone.designsystem.component.StatusTone
import com.keystone.designsystem.theme.KeystoneTheme
import com.keystone.shared.core.AppError
import com.keystone.shared.domain.model.PendingAction
import com.keystone.shared.domain.model.PendingAction.Status
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * "Here is exactly what will be submitted": the fields, any policy notes, and two buttons.
 * Once decided, the buttons are replaced by the outcome (reference number, or why not).
 */
@Composable
fun ConfirmationCard(
    item: ActionUi,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = item.action
    // The server decides expiry. The phone's clock may be wrong, so the buttons stay; a
    // late tap gets a 409 and the card then shows the real (expired) state.
    val status = action.status

    ElevatedCard(
        modifier = modifier.widthIn(max = 340.dp).fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    action.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(statusText(status), statusTone(status))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                action.fields.forEach { field ->
                    Row {
                        Text(
                            field.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 104.dp),
                        )
                        Text(field.value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            action.notes.forEach { note ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    )
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Outcome(item, status, onConfirm, onCancel)
        }
    }
}

@Composable
private fun Outcome(item: ActionUi, status: Status, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val action = item.action
    // Announced by screen readers when it changes (e.g. "Submitted, reference LR-1050").
    val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (status) {
        Status.Pending -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.error?.let {
                Text(
                    if (it == AppError.Network) stringResource(R.string.action_error_network) else stringResource(R.string.action_error_other),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = announce,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onConfirm, enabled = !item.busy) {
                    if (item.busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(action.confirmLabel)
                    }
                }
                OutlinedButton(onClick = onCancel, enabled = !item.busy) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        Status.Executing -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.action_working), style = MaterialTheme.typography.bodyMedium)
        }
        Status.Executed -> Row(
            modifier = announce,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = KeystoneTheme.status.success)
            Text(
                action.result?.message ?: stringResource(R.string.action_done),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Status.Cancelled -> OutcomeText(stringResource(R.string.action_cancelled_note), announce)
        Status.Expired -> OutcomeText(stringResource(R.string.action_expired_note), announce)
        Status.Failed -> OutcomeText(action.failureMessage ?: stringResource(R.string.action_failed_note), announce, isError = true)
    }
}

@Composable
private fun OutcomeText(text: String, modifier: Modifier, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun statusText(status: Status): String = stringResource(
    when (status) {
        Status.Pending -> R.string.action_status_pending
        Status.Executing -> R.string.action_status_executing
        Status.Executed -> R.string.action_status_executed
        Status.Cancelled -> R.string.action_status_cancelled
        Status.Expired -> R.string.action_status_expired
        Status.Failed -> R.string.action_status_failed
    },
)

private fun statusTone(status: Status): StatusTone = when (status) {
    Status.Pending, Status.Executing -> StatusTone.Warning
    Status.Executed -> StatusTone.Success
    Status.Failed -> StatusTone.Error
    Status.Cancelled, Status.Expired -> StatusTone.Neutral
}

private val previewAction = PendingAction(
    id = "a1",
    tool = "submitLeaveRequest",
    title = "Request annual leave",
    fields = listOf(
        PendingAction.Field("Type", "Annual leave"),
        PendingAction.Field("Dates", "Mon 26 Oct 2026 – Tue 27 Oct 2026"),
        PendingAction.Field("Working days", "2"),
        PendingAction.Field("Balance after", "14 days → 12 days"),
    ),
    notes = listOf("Short leave should be requested 5 working days ahead."),
    confirmLabel = "Submit request",
    expiresAt = Clock.System.now() + 15.minutes,
    status = Status.Pending,
)

@Preview(name = "Waiting for confirmation")
@Composable
private fun PendingPreview() {
    KeystoneTheme { ConfirmationCard(ActionUi(previewAction), onConfirm = {}, onCancel = {}) }
}

@Preview(name = "Done, dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DonePreview() {
    KeystoneTheme(darkTheme = true) {
        ConfirmationCard(
            ActionUi(
                previewAction.copy(
                    status = Status.Executed,
                    result = PendingAction.Result("LR-1050", "Leave request LR-1050 submitted. Your manager will review it."),
                ),
            ),
            onConfirm = {},
            onCancel = {},
        )
    }
}
