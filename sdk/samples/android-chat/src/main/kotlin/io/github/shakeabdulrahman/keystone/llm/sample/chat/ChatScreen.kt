package io.github.shakeabdulrahman.keystone.llm.sample.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Keystone LLM Chat")
                        Text(
                            if (state.providers.isEmpty()) "No providers configured" else "Route: ${state.providers.joinToString(" → ")}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (state.providers.isEmpty()) {
                MissingKeys(Modifier.weight(1f))
            } else {
                if (state.providers.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Simulate primary failure (shows failover)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = state.breakPrimary, onCheckedChange = viewModel::onBreakPrimaryChange, enabled = !state.isStreaming)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { MessageBubble(it) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask something") },
                    enabled = state.providers.isNotEmpty(),
                    maxLines = 4,
                )
                if (state.isStreaming) {
                    OutlinedButton(onClick = viewModel::stop) { Text("Stop") }
                } else {
                    Button(onClick = viewModel::send, enabled = state.input.isNotBlank() && state.providers.isNotEmpty()) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(
                    when {
                        message.fromUser -> colors.primaryContainer
                        message.failed -> colors.errorContainer
                        else -> colors.surfaceVariant
                    },
                    RoundedCornerShape(16.dp),
                )
                .padding(12.dp),
        ) {
            message.attemptLog.forEach { line ->
                Text("↻ $line", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = colors.tertiary)
            }
            message.answeredBy?.let {
                Text("answered by $it", style = MaterialTheme.typography.labelSmall, color = colors.primary)
            }
            if (message.text.isEmpty() && message.streaming) {
                CircularProgressIndicator(Modifier.padding(4.dp).size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
            message.footer?.let {
                Spacer(Modifier.size(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MissingKeys(modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Add keys to sdk/local.properties, then rebuild:", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "groq.apiKey=...\ngroq.model=openai/gpt-oss-120b\ngemini.apiKey=...\ngemini.model=gemini-3.8-flash",
            fontFamily = FontFamily.Monospace,
        )
    }
}
