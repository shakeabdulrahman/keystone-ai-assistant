package com.keystone.android.ui.chat.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keystone.android.R
import com.keystone.android.ui.chat.ToolActivityUi
import com.keystone.designsystem.component.StatusPill
import com.keystone.designsystem.component.StatusTone
import com.keystone.designsystem.theme.KeystoneTheme
import com.keystone.shared.domain.model.ToolCard
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toJavaLocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Long lists are cut here; the assistant's text can still mention the rest. */
private const val MAX_ROWS = 5

/**
 * One tool call inside an answer: a spinner row while it runs, then its card.
 * Failed calls and unknown card types show a short note, never raw data.
 */
@Composable
fun ToolActivity(activity: ToolActivityUi, modifier: Modifier = Modifier) {
    when {
        activity.state == ToolActivityUi.State.Running -> ProgressRow(activity.label, modifier)
        activity.state == ToolActivityUi.State.Failed -> Note(stringResource(R.string.tool_failed), modifier)
        activity.card != null -> ToolCardView(activity.card, modifier)
        // Done, but this app version doesn't know the card type: the text answer covers it.
        else -> Unit
    }
}

@Composable
private fun ProgressRow(label: String, modifier: Modifier) {
    val text = label.ifBlank { stringResource(R.string.tool_working) }
    Row(
        modifier = modifier
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Note(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
fun ToolCardView(card: ToolCard, modifier: Modifier = Modifier) {
    when (card) {
        is ToolCard.Profile -> ProfileCard(card, modifier)
        is ToolCard.LeaveBalance -> LeaveBalanceCard(card, modifier)
        is ToolCard.LeaveRequests -> LeaveRequestsCard(card, modifier)
        is ToolCard.HrRequests -> HrRequestsCard(card, modifier)
        is ToolCard.Tasks -> TasksCard(card, modifier)
        is ToolCard.Appointments -> AppointmentsCard(card, modifier)
        is ToolCard.Notifications -> NotificationsCard(card, modifier)
    }
}

// ---- Shared building blocks ----------------------------------------------------------

@Composable
private fun CardFrame(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    OutlinedCard(modifier = modifier.widthIn(max = 340.dp).fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

/** Renders up to [MAX_ROWS] rows with dividers, then "+N more", or an empty note. */
@Composable
private fun <T> Rows(items: List<T>, row: @Composable (T) -> Unit) {
    if (items.isEmpty()) {
        Text(
            stringResource(R.string.tool_card_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    items.take(MAX_ROWS).forEachIndexed { index, item ->
        if (index > 0) HorizontalDivider()
        row(item)
    }
    if (items.size > MAX_ROWS) {
        Text(
            stringResource(R.string.tool_card_more, items.size - MAX_ROWS),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListRow(title: String, subtitle: String?, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

// ---- Cards -------------------------------------------------------------------------------

@Composable
private fun ProfileCard(card: ToolCard.Profile, modifier: Modifier) {
    CardFrame(card.name, Icons.Filled.Person, modifier) {
        Text("${card.jobTitle} · ${card.department}", style = MaterialTheme.typography.bodyMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LabelValue(stringResource(R.string.tool_profile_manager), card.manager)
            LabelValue(stringResource(R.string.tool_profile_location), card.location)
            LabelValue(stringResource(R.string.tool_profile_started), card.startDate.formatMedium())
            card.email?.let { LabelValue(stringResource(R.string.tool_profile_email), it) }
        }
    }
}

@Composable
private fun LeaveBalanceCard(card: ToolCard.LeaveBalance, modifier: Modifier) {
    CardFrame(stringResource(R.string.tool_leave_balance_title, card.year), Icons.Filled.DateRange, modifier) {
        Rows(card.items) { item ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(item.type, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        stringResource(R.string.tool_leave_balance_left, item.remaining.days(), item.entitled.days()),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val progress = if (item.entitled > 0) (item.remaining / item.entitled).toFloat().coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LeaveRequestsCard(card: ToolCard.LeaveRequests, modifier: Modifier) {
    CardFrame(stringResource(R.string.tool_leave_requests_title), Icons.Filled.DateRange, modifier) {
        Rows(card.items) { item ->
            ListRow(
                title = stringResource(R.string.tool_leave_request_row, item.type, daysText(item.days)),
                subtitle = dateRange(item.start, item.end),
            ) { StatusPill(leaveStatusText(item.status), leaveStatusTone(item.status)) }
        }
    }
}

@Composable
private fun HrRequestsCard(card: ToolCard.HrRequests, modifier: Modifier) {
    CardFrame(stringResource(R.string.tool_hr_requests_title), Icons.Filled.Build, modifier) {
        Rows(card.items) { item ->
            ListRow(
                title = item.title,
                subtitle = stringResource(R.string.tool_hr_request_sub, item.category, item.updatedOn.formatShort()),
            ) { StatusPill(hrStatusText(item.status), hrStatusTone(item.status)) }
        }
    }
}

@Composable
private fun TasksCard(card: ToolCard.Tasks, modifier: Modifier) {
    CardFrame(stringResource(R.string.tool_tasks_title), Icons.AutoMirrored.Filled.List, modifier) {
        Rows(card.items) { item ->
            ListRow(
                title = item.title,
                subtitle = stringResource(R.string.tool_task_sub, item.category, item.due.formatShort()),
            ) {
                when {
                    item.done -> StatusPill(stringResource(R.string.tool_status_done), StatusTone.Success)
                    item.overdue -> StatusPill(stringResource(R.string.tool_status_overdue), StatusTone.Error)
                    else -> StatusPill(stringResource(R.string.tool_status_to_do), StatusTone.Neutral)
                }
            }
        }
    }
}

@Composable
private fun AppointmentsCard(card: ToolCard.Appointments, modifier: Modifier) {
    CardFrame(stringResource(R.string.tool_appointments_title), Icons.Filled.DateRange, modifier) {
        Rows(card.items) { item ->
            ListRow(
                title = item.title,
                subtitle = stringResource(
                    R.string.tool_appointment_sub,
                    timeRange(item.start, item.end),
                    item.with,
                    item.location,
                ),
            )
        }
    }
}

@Composable
private fun NotificationsCard(card: ToolCard.Notifications, modifier: Modifier) {
    CardFrame(stringResource(R.string.tool_notifications_title), Icons.Filled.Notifications, modifier) {
        Rows(card.items) { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Unread marker: a dot plus bold title, so it doesn't rely on colour alone.
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(8.dp)
                        .background(
                            if (item.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
                            CircleShape,
                        ),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                    )
                    Text(
                        item.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.createdAt.formatDateTime(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---- Status and formatting ----------------------------------------------------------------

@Composable
private fun leaveStatusText(status: String) = when (status) {
    "approved" -> stringResource(R.string.tool_status_approved)
    "rejected" -> stringResource(R.string.tool_status_rejected)
    "pending" -> stringResource(R.string.tool_status_pending)
    else -> status
}

private fun leaveStatusTone(status: String) = when (status) {
    "approved" -> StatusTone.Success
    "rejected" -> StatusTone.Error
    "pending" -> StatusTone.Warning
    else -> StatusTone.Neutral
}

@Composable
private fun hrStatusText(status: String) = when (status) {
    "submitted" -> stringResource(R.string.tool_status_submitted)
    "in_progress" -> stringResource(R.string.tool_status_in_progress)
    "resolved" -> stringResource(R.string.tool_status_resolved)
    else -> status
}

private fun hrStatusTone(status: String) = when (status) {
    "resolved" -> StatusTone.Success
    "in_progress" -> StatusTone.Warning
    else -> StatusTone.Neutral
}

/** "1 day", "3 days", "2.5 days", using the language's plural rules. */
@Composable
private fun daysText(days: Double): String {
    // Whole numbers pick the language's plural form; fractions use "other" (2.5 days).
    val quantity = if (days % 1.0 == 0.0) days.toInt() else Int.MAX_VALUE
    return pluralStringResource(R.plurals.tool_days, quantity, days.days())
}

/** 14.0 → "14", 2.5 → "2.5". */
private fun Double.days(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

// Built per call (cheap) so a language change in system settings applies immediately.
private fun shortDate() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private fun mediumDate() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
private fun dateTime() =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
private fun time() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

private fun LocalDate.formatShort(): String = toJavaLocalDate().format(shortDate())

private fun LocalDate.formatMedium(): String = toJavaLocalDate().format(mediumDate())

private fun dateRange(start: LocalDate, end: LocalDate): String =
    if (start == end) start.formatMedium() else "${start.formatShort()} – ${end.formatMedium()}"

/** Meeting times arrive in UTC and are shown in the phone's time zone. */
private fun Instant.local() = toJavaInstant().atZone(ZoneId.systemDefault())

private fun Instant.formatDateTime(): String = local().format(dateTime())

private fun timeRange(start: Instant, end: Instant): String =
    "${start.local().format(dateTime())} – ${end.local().format(time())}"

// ---- Previews ------------------------------------------------------------------------------

@Preview(name = "Leave balance")
@Composable
private fun LeaveBalancePreview() {
    KeystoneTheme {
        ToolCardView(
            ToolCard.LeaveBalance(
                2026,
                listOf(
                    ToolCard.LeaveBalance.Item("Annual", 20.0, 6.0, 14.0),
                    ToolCard.LeaveBalance.Item("Sick", 10.0, 1.0, 9.0),
                ),
            ),
        )
    }
}

@Preview(name = "Tasks, dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TasksPreview() {
    KeystoneTheme(darkTheme = true) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolActivity(ToolActivityUi("1", "Checking your tasks", ToolActivityUi.State.Running))
            ToolCardView(
                ToolCard.Tasks(
                    listOf(
                        ToolCard.Tasks.Item("1", "Upload ID proof", "Onboarding", LocalDate(2026, 10, 14), done = false, overdue = true),
                        ToolCard.Tasks.Item("2", "Security training", "Compliance", LocalDate(2026, 10, 21), done = false, overdue = false),
                        ToolCard.Tasks.Item("3", "Read code of conduct", "Onboarding", LocalDate(2026, 10, 2), done = true, overdue = false),
                    ),
                ),
            )
        }
    }
}
