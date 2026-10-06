package com.keystone.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.android.R
import com.keystone.designsystem.component.SkeletonBlock
import com.keystone.designsystem.component.StatusPill
import com.keystone.designsystem.component.StatusTone
import com.keystone.designsystem.theme.KeystoneTheme
import com.keystone.shared.core.AppError
import com.keystone.shared.domain.model.BackendInfo

/** Stateful entry point: owns the ViewModel. */
@Composable
fun HomeRoute(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state = state, onRetry = viewModel::onRetry, modifier = modifier)
}

/** Stateless screen: renders state and reports events. Easy to preview and test. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GreetingCard()
        BackendCard(status = state.backend, onRetry = onRetry)
    }
}

@Composable
private fun GreetingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.home_greeting_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.home_greeting_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun BackendCard(status: BackendStatus, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                // Screen readers announce status changes without the user re-focusing.
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_backend_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                StatusPill(text = status.label(), tone = status.tone())
            }
            when (status) {
                BackendStatus.Checking -> SkeletonBlock(
                    Modifier.fillMaxWidth(0.6f).height(20.dp),
                    loadingDescription = stringResource(R.string.status_checking),
                )
                is BackendStatus.Connected -> Text(
                    text = stringResource(
                        R.string.backend_details,
                        status.info.name,
                        status.info.version,
                        status.info.environment,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is BackendStatus.Unreachable -> {
                    Text(
                        text = status.error.message(),
                        style = MaterialTheme.typography.bodyMedium,
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
    is AppError.Http -> stringResource(R.string.error_server, status)
    AppError.InvalidResponse -> stringResource(R.string.error_invalid_response)
    AppError.Unknown -> stringResource(R.string.error_unknown)
}

@Preview(name = "Connected")
@Composable
private fun HomeConnectedPreview() {
    KeystoneTheme {
        HomeScreen(
            state = HomeUiState(BackendStatus.Connected(BackendInfo("keystone", "0.1.0", "local"))),
            onRetry = {},
        )
    }
}

@Preview(name = "Unreachable, dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeUnreachablePreview() {
    KeystoneTheme(darkTheme = true) {
        HomeScreen(state = HomeUiState(BackendStatus.Unreachable(AppError.Network)), onRetry = {})
    }
}
