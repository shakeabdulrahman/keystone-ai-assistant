package com.keystone.shared.data.remote.dto

import com.keystone.shared.domain.model.Citation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class CitationDto(
    @SerialName("ref") val ref: Int,
    @SerialName("document_id") val documentId: String,
    @SerialName("title") val title: String,
    @SerialName("section_index") val sectionIndex: Int,
    @SerialName("heading") val heading: String = "",
    @SerialName("snippet") val snippet: String = "",
)

internal fun CitationDto.toDomain() = Citation(ref, documentId, title, sectionIndex, heading, snippet)

/** The `citations` stream event, and the `citations` entry of a stored message's parts. */
@Serializable
internal data class CitationsDto(@SerialName("items") val items: List<CitationDto> = emptyList())

@Serializable
internal data class DocumentSectionDto(
    @SerialName("index") val index: Int,
    @SerialName("heading") val heading: String,
    @SerialName("body") val body: String,
)

@Serializable
internal data class DocumentDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("version") val version: String,
    @SerialName("effective") val effective: String,
    @SerialName("owner") val owner: String,
    @SerialName("sections") val sections: List<DocumentSectionDto> = emptyList(),
)
