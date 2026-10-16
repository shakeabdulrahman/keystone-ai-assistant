package com.keystone.android.ui.document

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keystone.android.R
import com.keystone.designsystem.theme.KeystoneTheme
import com.keystone.shared.core.AppError
import com.keystone.shared.domain.model.PolicyDocument
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun DocumentRoute(onBack: () -> Unit, viewModel: DocumentViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DocumentScreen(state, onBack = onBack, onRetry = viewModel::retry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    state: DocumentUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = (state as? DocumentUiState.Loaded)?.document?.title ?: stringResource(R.string.document_title)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                DocumentUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is DocumentUiState.Failed -> FailedDocument(state.error, onRetry, Modifier.align(Alignment.Center))
                is DocumentUiState.Loaded -> DocumentBody(state.document, state.highlightedSection)
            }
        }
    }
}

@Composable
private fun DocumentBody(document: PolicyDocument, highlighted: Int?) {
    val listState = rememberLazyListState()
    // Item 0 is the header; section i is item i + 1.
    LaunchedEffect(document.id, highlighted) {
        if (highlighted != null) {
            val position = document.sections.indexOfFirst { it.index == highlighted }
            if (position >= 0) listState.animateScrollToItem(position + 1)
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(
                    R.string.document_meta,
                    document.version,
                    document.effective.formatMedium(),
                    document.owner,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Keyed by position: a list key must be unique even if the server repeated an index.
        itemsIndexed(document.sections, key = { position, _ -> position }) { _, section ->
            SectionBlock(section, isHighlighted = section.index == highlighted)
        }
    }
}

@Composable
private fun SectionBlock(section: PolicyDocument.Section, isHighlighted: Boolean) {
    val background = if (isHighlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .padding(if (isHighlighted) 12.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isHighlighted) {
            Text(
                stringResource(R.string.document_cited_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            section.heading,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        parseMarkdown(section.body).forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", style = MaterialTheme.typography.bodyMedium)
                            Text(inlineMarkdown(item), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FailedDocument(error: AppError, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = when {
                error == AppError.Network -> stringResource(R.string.error_network)
                error is AppError.Http && error.status == 404 -> stringResource(R.string.document_not_found)
                else -> stringResource(R.string.error_unknown)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

private fun LocalDate.formatMedium(): String =
    toJavaLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

@Preview(name = "Cited section")
@Composable
private fun DocumentPreview() {
    KeystoneTheme {
        DocumentScreen(
            state = DocumentUiState.Loaded(
                PolicyDocument(
                    id = "leave-policy",
                    title = "Leave Policy",
                    version = "2026.1",
                    effective = LocalDate(2026, 1, 1),
                    owner = "People Team",
                    sections = listOf(
                        PolicyDocument.Section(0, "Leave entitlement", "- **Annual leave:** 20 working days\n- **Sick leave:** 10 working days"),
                        PolicyDocument.Section(1, "Carry-over and expiry", "You may carry over up to **5 days** of unused annual leave."),
                    ),
                ),
                highlightedSection = 1,
            ),
            onBack = {},
            onRetry = {},
        )
    }
}
