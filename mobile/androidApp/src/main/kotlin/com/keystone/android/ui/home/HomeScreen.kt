package com.keystone.android.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.android.R
import com.keystone.android.ui.common.userMessage
import com.keystone.designsystem.component.SkeletonBlock
import com.keystone.designsystem.component.StatusPill
import com.keystone.designsystem.component.StatusTone
import com.keystone.designsystem.theme.KeystoneTheme
import com.keystone.shared.core.AppError
import com.keystone.shared.domain.model.AttentionItem
import com.keystone.shared.domain.model.BackendInfo
import com.keystone.shared.domain.model.HomeCounts
import com.keystone.shared.domain.model.HomeDashboard
import com.keystone.shared.domain.model.LeaveSummary
import com.keystone.shared.domain.model.UpcomingMeeting
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Stateful entry point: owns the ViewModel and the notification-permission prompt. */
@Composable
fun HomeRoute(
    userEmail: String?,
    onOpenChat: () -> Unit,
    onAsk: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Coming back to the app (e.g. from a notification) shows fresh data.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onRefresh() }

    val context = LocalContext.current
    var askNotifications by remember { mutableStateOf(shouldOfferNotifications(context)) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        rememberNotificationsOffered(context)
        askNotifications = false
    }

    HomeScreen(
        state = state,
        userEmail = userEmail,
        offerNotifications = askNotifications,
        onEnableNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onDismissNotifications = {
            rememberNotificationsOffered(context)
            askNotifications = false
        },
        onRefresh = viewModel::onRefresh,
        onRetry = viewModel::onRetry,
        onOpenChat = onOpenChat,
        onAttention = { action ->
            when (action) {
                is AttentionItem.Action.Ask -> onAsk(action.prompt)
                is AttentionItem.Action.OpenConversation -> onOpenConversation(action.conversationId)
                AttentionItem.Action.None -> Unit
            }
        },
        onAsk = onAsk,
        onOpenHistory = onOpenHistory,
        onSignOut = onSignOut,
    )
}

private const val PREFS = "keystone_ui"
private const val NOTIFICATIONS_OFFERED = "notifications_offered"

/** Android 13+ needs permission. Offer it once, from an explained card, never as a cold pop-up. */
private fun shouldOfferNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED &&
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(NOTIFICATIONS_OFFERED, false)

private fun rememberNotificationsOffered(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(NOTIFICATIONS_OFFERED, true).apply()
}

/** Stateless screen: renders state and reports events. Easy to preview and test. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    userEmail: String?,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenChat: () -> Unit,
    onAttention: (AttentionItem.Action) -> Unit,
    onAsk: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    offerNotifications: Boolean = false,
    onEnableNotifications: () -> Unit = {},
    onDismissNotifications: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { TextButton(onClick = onSignOut) { Text(stringResource(R.string.action_sign_out)) } },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing && state.dashboard != null,
            onRefresh = onRefresh,
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { Greeting(state.dashboard?.firstName, userEmail, onOpenChat, onOpenHistory) }
                if (offerNotifications) {
                    item { NotificationsCard(onEnableNotifications, onDismissNotifications) }
                }
                val dashboard = state.dashboard
                when {
                    dashboard != null -> {
                        state.refreshError?.let { item { OfflineNote(dashboard.generatedAt) } }
                        item { SectionTitle(stringResource(R.string.home_attention_title)) }
                        if (dashboard.attention.isEmpty()) {
                            item { CaughtUp() }
                        } else {
                            items(dashboard.attention, key = { it.id }) { AttentionCard(it, onAttention) }
                        }
                        if (dashboard.leave.isNotEmpty()) item { LeaveTiles(dashboard.leave) }
                        if (dashboard.upcoming.isNotEmpty()) item { Upcoming(dashboard.upcoming) }
                        if (dashboard.suggestions.isNotEmpty()) item { Suggestions(dashboard.suggestions, onAsk) }
                    }
                    state.refreshError != null -> item { LoadFailed(state.refreshError, onRetry) }
                    else -> item { DashboardSkeleton() }
                }
                item { BackendCard(status = state.backend, onRetry = onRetry) }
            }
        }
    }
}

@Composable
private fun Greeting(firstName: String?, userEmail: String?, onOpenChat: () -> Unit, onOpenHistory: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = greeting(firstName),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.home_greeting_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (firstName == null && userEmail != null) {
                Text(
                    text = stringResource(R.string.home_signed_in_as, userEmail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onOpenChat) { Text(stringResource(R.string.home_open_chat)) }
                OutlinedButton(onClick = onOpenHistory) { Text(stringResource(R.string.home_open_history)) }
            }
        }
    }
}

@Composable
private fun greeting(firstName: String?): String {
    val hour = java.time.LocalTime.now().hour
    val part = stringResource(
        when {
            hour < 12 -> R.string.home_good_morning
            hour < 18 -> R.string.home_good_afternoon
            else -> R.string.home_good_evening
        },
    )
    return if (firstName.isNullOrBlank()) part else stringResource(R.string.home_greeting_named, part, firstName)
}

@Composable
private fun NotificationsCard(onEnable: () -> Unit, onDismiss: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_notifications_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.home_notifications_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEnable) { Text(stringResource(R.string.home_notifications_enable)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_notifications_later)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun AttentionCard(item: AttentionItem, onAttention: (AttentionItem.Action) -> Unit) {
    val tappable = item.action != AttentionItem.Action.None
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (tappable) Modifier.clickable(role = Role.Button) { onAttention(item.action) } else Modifier,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isUrgent) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (item.detail.isNotBlank()) {
                    Text(item.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (item.isUrgent) StatusPill(stringResource(R.string.home_urgent), StatusTone.Error)
        }
    }
}

@Composable
private fun CaughtUp() {
    Text(
        stringResource(R.string.home_caught_up),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LeaveTiles(leave: List<LeaveSummary>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.home_leave_title))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            leave.take(3).forEach { item ->
                OutlinedCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.remaining.asDays(), style = MaterialTheme.typography.headlineMedium)
                        Text(
                            stringResource(R.string.home_leave_of, item.entitled.asDays()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(item.type, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Upcoming(meetings: List<UpcomingMeeting>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.home_upcoming_title))
        meetings.forEach { m ->
            Column {
                Text(m.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.home_upcoming_detail, m.start.formatLocal(), m.location),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(suggestions: List<String>, onAsk: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.home_suggestions_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { s -> SuggestionChip(onClick = { onAsk(s) }, label = { Text(s) }) }
        }
    }
}

@Composable
private fun OfflineNote(generatedAt: Instant) {
    Text(
        stringResource(R.string.home_offline, generatedAt.formatLocal()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun LoadFailed(error: AppError, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(error.message(), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun DashboardSkeleton() {
    val loading = stringResource(R.string.home_loading)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { SkeletonBlock(Modifier.fillMaxWidth().height(64.dp), loadingDescription = loading) }
    }
}

@Composable
private fun BackendCard(status: BackendStatus, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                // Screen readers announce status changes without the user re-focusing.
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.home_backend_title), style = MaterialTheme.typography.labelLarge)
                StatusPill(text = status.label(), tone = status.tone())
            }
            when (status) {
                BackendStatus.Checking -> Unit
                is BackendStatus.Connected -> Text(
                    text = stringResource(
                        R.string.backend_details,
                        status.info.name,
                        status.info.version,
                        status.info.environment,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is BackendStatus.Unreachable -> {
                    Text(
                        text = status.error.message(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}

@Composable
private fun BackendStatus.label(): String = when (this) {
    BackendStatus.Checking -> stringResource(R.string.status_checking)
    is BackendStatus.Connected -> stringResource(R.string.status_connected)
    is BackendStatus.Unreachable -> stringResource(R.string.status_unreachable)
}

private fun BackendStatus.tone(): StatusTone = when (this) {
    BackendStatus.Checking -> StatusTone.Neutral
    is BackendStatus.Connected -> StatusTone.Success
    is BackendStatus.Unreachable -> StatusTone.Error
}

/** Maps domain errors to user-facing copy. Raw exception text never reaches the screen. */
@Composable
private fun AppError.message(): String = when (this) {
    AppError.Network -> stringResource(R.string.error_network)
    is AppError.Http -> userMessage()
    is AppError.Assistant -> stringResource(R.string.error_assistant_unavailable)
    AppError.InvalidResponse -> stringResource(R.string.error_invalid_response)
    AppError.Unknown -> stringResource(R.string.error_unknown)
}

/** 14.0 → "14", 2.5 → "2.5". */
private fun Double.asDays(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

private fun Instant.formatLocal(): String =
    toJavaInstant().atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()))

private val previewDashboard = HomeDashboard(
    generatedAt = Clock.System.now(),
    firstName = "Aisha",
    leave = listOf(LeaveSummary("Annual", 14.0, 20.0), LeaveSummary("Sick", 9.0, 10.0), LeaveSummary("Casual", 4.0, 6.0)),
    attention = listOf(
        AttentionItem(
            "a1",
            AttentionItem.Kind.PendingAction,
            isUrgent = true,
            title = "Waiting for you: request annual leave",
            detail = "Prepared in chat but not confirmed yet.",
            action = AttentionItem.Action.OpenConversation("c1", "a1"),
        ),
        AttentionItem(
            "t1",
            AttentionItem.Kind.TaskDueSoon,
            isUrgent = false,
            title = "Complete information security training",
            detail = "Due Mon 19 Oct",
            action = AttentionItem.Action.Ask("Help me with my task T-303"),
        ),
    ),
    upcoming = listOf(
        UpcomingMeeting("A-50", "1:1 with your manager", "Your manager", Clock.System.now() + 2.days + 3.hours, "Teams"),
    ),
    counts = HomeCounts(openHrRequests = 2, pendingLeaveRequests = 1, unreadNotifications = 3),
    suggestions = listOf("Which of my tasks are overdue?", "How many leave days do I have left?"),
)

@Preview(name = "Dashboard")
@Composable
private fun HomeDashboardPreview() {
    KeystoneTheme {
        HomeScreen(
            state = HomeUiState(
                backend = BackendStatus.Connected(BackendInfo("keystone", "0.1.0", "local")),
                dashboard = previewDashboard,
            ),
            userEmail = "test@keystone.dev",
            offerNotifications = true,
            onRefresh = {}, onRetry = {}, onOpenChat = {}, onAttention = {}, onAsk = {}, onOpenHistory = {}, onSignOut = {},
        )
    }
}

@Preview(name = "Offline first launch, dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeOfflinePreview() {
    KeystoneTheme(darkTheme = true) {
        HomeScreen(
            state = HomeUiState(BackendStatus.Unreachable(AppError.Network), refreshError = AppError.Network),
            userEmail = "test@keystone.dev",
            onRefresh = {}, onRetry = {}, onOpenChat = {}, onAttention = {}, onAsk = {}, onOpenHistory = {}, onSignOut = {},
        )
    }
}
