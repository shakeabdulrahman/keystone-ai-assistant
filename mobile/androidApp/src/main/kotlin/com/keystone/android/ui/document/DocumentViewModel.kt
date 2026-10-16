package com.keystone.android.ui.document

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.PolicyDocument
import com.keystone.shared.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DocumentUiState {
    data object Loading : DocumentUiState
    data class Loaded(val document: PolicyDocument, val highlightedSection: Int?) : DocumentUiState
    data class Failed(val error: AppError) : DocumentUiState
}

@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val documents: DocumentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val documentId: String = checkNotNull(savedStateHandle[DOCUMENT_ID_ARG])
    private val sectionIndex: Int = savedStateHandle[SECTION_INDEX_ARG] ?: -1

    private val _uiState = MutableStateFlow<DocumentUiState>(DocumentUiState.Loading)
    val uiState: StateFlow<DocumentUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        _uiState.value = DocumentUiState.Loading
        viewModelScope.launch {
            _uiState.value = when (val result = documents.document(documentId)) {
                is AppResult.Success -> {
                    val doc = result.value
                    // A stale citation (document edited since) may point past the end: show the top.
                    val highlight = sectionIndex.takeIf { i -> doc.sections.any { it.index == i } }
                    DocumentUiState.Loaded(doc, highlight)
                }
                is AppResult.Failure -> DocumentUiState.Failed(result.error)
            }
        }
    }

    companion object {
        /** Match the property names in DocumentDestination (type-safe navigation). */
        const val DOCUMENT_ID_ARG = "documentId"
        const val SECTION_INDEX_ARG = "sectionIndex"
    }
}
