package com.keystone.android.ui.conversations

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.android.R
import com.keystone.designsystem.component.SkeletonBlock
import com.keystone.designsystem.theme.KeystoneTheme
import com.keystone.shared.core.AppError
import com.keystone.shared.domain.model.Conversation
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours

@Composable
fun ConversationsRoute(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ConversationsScreen(
        state = state,
        onBack = onBack,
        onOpen = onOpenConversation,
        onNew = onNewConversation,
        onRefresh = viewModel::onRefresh,
        onLoadMore = viewModel::onLoadMore,
        onRename = viewModel::onRename,
        onDelete = viewModel::onDelete,
        onActionErrorShown = viewModel::onActionErrorShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    state: ConversationsUiState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onActionErrorShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val actionFailed = stringResource(R.string.history_action_failed)
    LaunchedEffect(state.actionError) {
        if (state.actionError != null) {
            snackbar.showSnackbar(actionFailed)
            onActionErrorShown()
        }
    }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.history_new)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing && state.hasRefreshedOnce,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                state.conversations.isEmpty() && !state.hasRefreshedOnce -> LoadingList()
                state.conversations.isEmpty() && state.refreshError != null -> ErrorState(onRefresh)
                state.conversations.isEmpty() -> EmptyState(onNew)
                else -> ConversationList(
                    state = state,
                    onOpen = onOpen,
                    onLoadMore = onLoadMore,
                    onRenameRequest = { renaming = it },
                    onDeleteRequest = { deleting = it },
                )
            }
        }
    }

    renaming?.let { conversation ->
        RenameDialog(
            initial = conversation.title,
            onConfirm = { title ->
                onRename(conversation.id, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_body, conversation.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(conversation.id)
                    deleting = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ConversationList(
    state: ConversationsUiState,
    onOpen: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRenameRequest: (Conversation) -> Unit,
    onDeleteRequest: (Conversation) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        if (state.refreshError != null) {
            item(key = "offline-banner") { OfflineBanner() }
        }
        items(state.conversations, key = { it.id }) { conversation ->
            ConversationRow(
                conversation = conversation,
                onClick = { onOpen(conversation.id) },
                onRename = { onRenameRequest(conversation) },
                onDelete = { onDeleteRequest(conversation) },
            )
            HorizontalDivider()
        }
        if (state.canLoadMore) {
            item(key = "load-more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onLoadMore, enabled = !state.isLoadingMore) {
                        Text(stringResource(R.string.history_load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relativeTime(conversation.lastMessageAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.history_more_actions, conversation.title))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(ConversationsViewModel.MAX_TITLE_CHARS) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun OfflineBanner() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.history_offline),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LoadingList() {
    val loading = stringResource(R.string.history_loading)
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(6) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(Modifier.fillMaxWidth(0.7f).height(18.dp), loadingDescription = loading)
                SkeletonBlock(Modifier.fillMaxWidth(0.3f).height(12.dp), loadingDescription = loading)
            }
        }
    }
}

@Composable
private fun EmptyState(onNew: () -> Unit) {
    CenteredMessage(
        title = stringResource(R.string.history_empty_title),
        body = stringResource(R.string.history_empty_body),
        action = stringResource(R.string.history_new),
        onAction = onNew,
    )
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    CenteredMessage(
        title = stringResource(R.string.history_error_title),
        body = stringResource(R.string.error_network),
        action = stringResource(R.string.action_retry),
        onAction = onRetry,
    )
}

@Composable
private fun CenteredMessage(title: String, body: String, action: String, onAction: () -> Unit) {
    // Scrollable so pull-to-refresh also works on the empty and error states.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp)) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 96.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                )
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

private fun relativeTime(instant: Instant): String =
    DateUtils.getRelativeTimeSpanString(
        instant.toEpochMilliseconds(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

@Preview
@Composable
private fun ConversationsPreview() {
    val now = Clock.System.now()
    KeystoneTheme {
        ConversationsScreen(
            state = ConversationsUiState(
                conversations = listOf(
                    Conversation("1", "Annual leave request steps", now),
                    Conversation("2", "Parental leave policy", now - 26.hours),
                ),
                hasRefreshedOnce = true,
                refreshError = AppError.Network,
            ),
            onBack = {}, onOpen = {}, onNew = {}, onRefresh = {}, onLoadMore = {},
            onRename = { _, _ -> }, onDelete = {}, onActionErrorShown = {},
        )
    }
}
