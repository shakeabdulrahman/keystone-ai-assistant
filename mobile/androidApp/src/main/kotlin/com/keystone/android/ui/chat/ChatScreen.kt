package com.keystone.android.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.android.R
import com.keystone.android.ui.chat.ChatMessageUi.Role
import com.keystone.android.ui.chat.ChatMessageUi.Status
import com.keystone.android.ui.chat.cards.ConfirmationCard
import com.keystone.android.ui.chat.cards.ToolActivity
import com.keystone.android.voice.SpeechError
import com.keystone.designsystem.theme.KeystoneTheme
import com.keystone.shared.core.AppError
import com.keystone.shared.core.isRetryable
import com.keystone.shared.domain.model.Citation
import com.keystone.shared.domain.model.ToolCard

@Composable
fun ChatRoute(
    onBack: () -> Unit,
    onOpenSource: (Citation) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The microphone permission is asked for the first time the mic is tapped, not at launch.
    val askForMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onMicPressed() else viewModel.onMicPermissionDenied()
    }
    val onMic = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onMicPressed() else askForMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    ChatScreen(
        state = state,
        onBack = onBack,
        onInputChange = viewModel::onInputChange,
        onSend = viewModel::onSend,
        onStop = viewModel::onStop,
        onRetry = viewModel::onRetry,
        onSuggestion = viewModel::onSuggestion,
        onOpenSource = onOpenSource,
        onConfirmAction = viewModel::onConfirmAction,
        onCancelAction = viewModel::onCancelAction,
        onMic = onMic,
        onToggleReadAloud = viewModel::onToggleReadAloud,
        onDismissVoiceError = viewModel::onDismissVoiceError,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: (String) -> Unit,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSource: (Citation) -> Unit = {},
    onConfirmAction: (String) -> Unit = {},
    onCancelAction: (String) -> Unit = {},
    onMic: () -> Unit = {},
    onToggleReadAloud: () -> Unit = {},
    onDismissVoiceError: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    val readAloud = state.voice.readAloud
                    IconButton(onClick = onToggleReadAloud) {
                        Icon(
                            if (readAloud) VoiceIcons.VolumeUp else VoiceIcons.VolumeOff,
                            stringResource(if (readAloud) R.string.voice_read_aloud_on else R.string.voice_read_aloud_off),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                input = state.input,
                canSend = state.canSend,
                isResponding = state.isResponding,
                voice = state.voice,
                onInputChange = onInputChange,
                onSend = onSend,
                onStop = onStop,
                onMic = onMic,
                onDismissVoiceError = onDismissVoiceError,
            )
        },
    ) { innerPadding ->
        if (state.messages.isEmpty() && state.isLoadingHistory) {
            Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                TypingIndicator()
            }
        } else if (state.messages.isEmpty()) {
            EmptyChat(onSuggestion = onSuggestion, modifier = Modifier.padding(innerPadding))
        } else {
            MessageList(
                state.messages,
                onRetry,
                onOpenSource,
                onConfirmAction,
                onCancelAction,
                Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessageUi>,
    onRetry: (String) -> Unit,
    onOpenSource: (Citation) -> Unit,
    onConfirmAction: (String) -> Unit,
    onCancelAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Follow the conversation: scroll when a message is added or the last one grows.
    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.text?.length, last?.status, last?.tools, last?.citations, last?.actions) {
        listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(
                message,
                onRetry = { onRetry(message.id) },
                onOpenSource = onOpenSource,
                onConfirmAction = onConfirmAction,
                onCancelAction = onCancelAction,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessageUi,
    onRetry: () -> Unit,
    onOpenSource: (Citation) -> Unit,
    onConfirmAction: (String) -> Unit = {},
    onCancelAction: (String) -> Unit = {},
) {
    val isUser = message.role == Role.User
    val speaker = stringResource(if (isUser) R.string.chat_you else R.string.app_name)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Tool progress rows and cards come first: the text answer is written from them.
            message.tools.forEach { ToolActivity(it) }
            val toolRunning = message.tools.any { it.state == ToolActivityUi.State.Running }
            val showBubble = message.text.isNotEmpty() ||
                (message.status == Status.Streaming && !toolRunning) ||
                (message.status == Status.Failed && message.tools.isEmpty())
            if (showBubble) {
                Surface(
                    color = if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isUser) 20.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 20.dp,
                    ),
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "$speaker: ${message.text}"
                        },
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        when {
                            message.text.isEmpty() && message.status == Status.Streaming -> TypingIndicator()
                            message.status == Status.Streaming -> Text(message.text + " ▍")
                            message.text.isNotEmpty() -> Text(message.text)
                        }
                    }
                }
            }
            message.actions.forEach { item ->
                ConfirmationCard(
                    item,
                    onConfirm = { onConfirmAction(item.action.id) },
                    onCancel = { onCancelAction(item.action.id) },
                )
            }
            if (message.citations.isNotEmpty()) Sources(message.citations, onOpenSource)
            when (message.status) {
                Status.Stopped -> Caption(stringResource(R.string.chat_stopped))
                Status.Failed -> FailureRow(message.error, onRetry)
                else -> Unit
            }
        }
    }
}

/** Tappable source chips: "[1] Leave Policy · Carry-over and expiry". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sources(citations: List<Citation>, onOpenSource: (Citation) -> Unit) {
    Column(Modifier.widthIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.chat_sources),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            citations.forEach { citation ->
                val hasHeading = citation.heading.isNotBlank()
                val description = if (hasHeading) {
                    stringResource(R.string.chat_source_open, citation.title, citation.heading)
                } else {
                    stringResource(R.string.chat_source_open_document, citation.title)
                }
                AssistChip(
                    onClick = { onOpenSource(citation) },
                    label = {
                        Text(
                            if (hasHeading) {
                                stringResource(R.string.chat_source_chip, citation.ref, citation.title, citation.heading)
                            } else {
                                stringResource(R.string.chat_source_chip_document, citation.ref, citation.title)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "typingAlpha",
    )
    val thinking = stringResource(R.string.chat_thinking)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .alpha(alpha)
            .semantics { contentDescription = thinking },
    ) {
        repeat(3) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
private fun FailureRow(error: AppError?, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = error.toMessage(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp).widthIn(max = 240.dp),
        )
        if (error?.isRetryable == true) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    val suggestions = listOf(
        stringResource(R.string.chat_suggestion_leave),
        stringResource(R.string.chat_suggestion_onboarding),
        stringResource(R.string.chat_suggestion_request),
        stringResource(R.string.chat_suggestion_policy),
        stringResource(R.string.chat_suggestion_action),
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.chat_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { suggestion ->
                SuggestionChip(onClick = { onSuggestion(suggestion) }, label = { Text(suggestion) })
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    canSend: Boolean,
    isResponding: Boolean,
    voice: VoiceUi,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
    onDismissVoiceError: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            voice.error?.let { error ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        voiceErrorText(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(onClick = onDismissVoiceError) { Text(stringResource(R.string.action_ok)) }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = {
                        Text(stringResource(if (voice.listening) R.string.voice_listening else R.string.chat_input_hint))
                    },
                    maxLines = 5,
                    modifier = Modifier.weight(1f),
                )
                // The mic replaces Send while the field is empty, and stays while listening.
                val showMic = voice.micAvailable && !isResponding && (input.isBlank() || voice.listening)
                if (showMic) {
                    MicButton(listening = voice.listening, onClick = onMic)
                } else if (isResponding) {
                    val stop = stringResource(R.string.action_stop)
                    FilledTonalIconButton(onClick = onStop, modifier = Modifier.semantics { contentDescription = stop }) {
                        // A filled square is the universal "stop" symbol.
                        Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onSecondaryContainer))
                    }
                } else {
                    FilledIconButton(onClick = onSend, enabled = canSend) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.action_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val description = stringResource(if (listening) R.string.voice_stop_listening else R.string.voice_start)
    if (listening) {
        // A gently pulsing button: visibly "on air", and tapping it again finishes.
        val transition = rememberInfiniteTransition(label = "mic")
        val scale by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "micScale",
        )
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.scale(scale).semantics { contentDescription = description },
        ) { Icon(VoiceIcons.Mic, contentDescription = null) }
    } else {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
            Icon(VoiceIcons.Mic, contentDescription = null)
        }
    }
}

@Composable
private fun voiceErrorText(error: SpeechError): String = stringResource(
    when (error) {
        SpeechError.NoMatch -> R.string.voice_error_no_match
        SpeechError.NoPermission -> R.string.voice_error_permission
        SpeechError.Network -> R.string.voice_error_network
        SpeechError.Busy -> R.string.voice_error_busy
        SpeechError.Unavailable -> R.string.voice_error_unavailable
        SpeechError.Other -> R.string.voice_error_other
    },
)

@Composable
private fun AppError?.toMessage(): String = when (this) {
    AppError.Network -> stringResource(R.string.chat_error_network)
    is AppError.Assistant -> when (code) {
        "ai_timeout" -> stringResource(R.string.chat_error_timeout)
        "ai_rate_limited" -> stringResource(R.string.chat_error_busy)
        "ai_misconfigured" -> stringResource(R.string.chat_error_misconfigured)
        else -> stringResource(R.string.error_assistant_unavailable)
    }
    is AppError.Http -> stringResource(R.string.error_server, status)
    AppError.InvalidResponse -> stringResource(R.string.error_invalid_response)
    AppError.Unknown, null -> stringResource(R.string.error_unknown)
}

@Preview(name = "Conversation")
@Composable
private fun ChatConversationPreview() {
    KeystoneTheme {
        ChatScreen(
            state = ChatUiState(
                messages = listOf(
                    ChatMessageUi("1", Role.User, "How many leave days do I have left?"),
                    ChatMessageUi(
                        "2",
                        Role.Assistant,
                        "You have 14 days of annual leave left",
                        Status.Streaming,
                        tools = listOf(
                            ToolActivityUi(
                                "c1",
                                "Checking your leave balance",
                                ToolActivityUi.State.Done,
                                ToolCard.LeaveBalance(2026, listOf(ToolCard.LeaveBalance.Item("Annual", 20.0, 6.0, 14.0))),
                            ),
                        ),
                    ),
                ),
                isResponding = true,
            ),
            onBack = {}, onInputChange = {}, onSend = {}, onStop = {}, onRetry = {}, onSuggestion = {},
        )
    }
}

@Preview(name = "Answer with sources")
@Composable
private fun ChatSourcesPreview() {
    KeystoneTheme {
        ChatScreen(
            state = ChatUiState(
                messages = listOf(
                    ChatMessageUi("1", Role.User, "Can I carry over unused leave?"),
                    ChatMessageUi(
                        "2",
                        Role.Assistant,
                        "Yes, up to 5 days, which must be used by 31 March [1].",
                        citations = listOf(
                            Citation(1, "leave-policy", "Leave Policy", 5, "Carry-over and expiry", "You may carry over…"),
                        ),
                    ),
                ),
            ),
            onBack = {}, onInputChange = {}, onSend = {}, onStop = {}, onRetry = {}, onSuggestion = {},
        )
    }
}

@Preview(name = "Failed, dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatFailedPreview() {
    KeystoneTheme(darkTheme = true) {
        ChatScreen(
            state = ChatUiState(
                messages = listOf(
                    ChatMessageUi("1", Role.User, "What's the parental leave policy?"),
                    ChatMessageUi("2", Role.Assistant, "", Status.Failed, AppError.Network),
                ),
                input = "Try again",
            ),
            onBack = {}, onInputChange = {}, onSend = {}, onStop = {}, onRetry = {}, onSuggestion = {},
        )
    }
}

@Preview(name = "Empty")
@Composable
private fun ChatEmptyPreview() {
    KeystoneTheme {
        ChatScreen(
            state = ChatUiState(),
            onBack = {}, onInputChange = {}, onSend = {}, onStop = {}, onRetry = {}, onSuggestion = {},
        )
    }
}
