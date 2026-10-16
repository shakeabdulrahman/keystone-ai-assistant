package com.keystone.android.ui.document

import androidx.lifecycle.SavedStateHandle
import com.keystone.android.MainDispatcherRule
import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.PolicyDocument
import com.keystone.shared.domain.repository.DocumentRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val leave = PolicyDocument(
        id = "leave-policy",
        title = "Leave Policy",
        version = "2026.1",
        effective = LocalDate(2026, 1, 1),
        owner = "People Team",
        sections = listOf(
            PolicyDocument.Section(0, "Overview", "Intro"),
            PolicyDocument.Section(1, "Carry-over and expiry", "Up to 5 days."),
        ),
    )

    private class FakeDocuments(var results: MutableList<AppResult<PolicyDocument>>) : DocumentRepository {
        val requested = mutableListOf<String>()
        override suspend fun document(id: String): AppResult<PolicyDocument> {
            requested += id
            return results.removeAt(0)
        }
    }

    private fun viewModel(repo: DocumentRepository, section: Int?) = DocumentViewModel(
        repo,
        SavedStateHandle(
            buildMap<String, Any?> {
                put(DocumentViewModel.DOCUMENT_ID_ARG, "leave-policy")
                if (section != null) put(DocumentViewModel.SECTION_INDEX_ARG, section)
            },
        ),
    )

    @Test
    fun `opens the document at the cited section`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeDocuments(mutableListOf(AppResult.Success(leave)))
        val vm = viewModel(repo, section = 1)
        advanceUntilIdle()

        assertEquals(DocumentUiState.Loaded(leave, highlightedSection = 1), vm.uiState.value)
        assertEquals(listOf("leave-policy"), repo.requested)
    }

    @Test
    fun `a section that no longer exists opens the top without highlight`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(FakeDocuments(mutableListOf(AppResult.Success(leave))), section = 9)
            advanceUntilIdle()
            assertEquals(DocumentUiState.Loaded(leave, highlightedSection = null), vm.uiState.value)
        }

    @Test
    fun `failure can be retried`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeDocuments(mutableListOf(AppResult.Failure(AppError.Network), AppResult.Success(leave)))
        val vm = viewModel(repo, section = null)
        advanceUntilIdle()
        assertEquals(DocumentUiState.Failed(AppError.Network), vm.uiState.value)

        vm.retry()
        advanceUntilIdle()
        assertEquals(DocumentUiState.Loaded(leave, highlightedSection = null), vm.uiState.value)
    }
}
