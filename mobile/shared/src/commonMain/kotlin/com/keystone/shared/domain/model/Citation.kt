package com.keystone.shared.domain.model

import kotlinx.datetime.LocalDate

/** A policy passage an answer relied on. [ref] is the number in the answer text: "[1]". */
data class Citation(
    val ref: Int,
    val documentId: String,
    val title: String,
    /** Opens the document at this section (index into [PolicyDocument.sections]). */
    val sectionIndex: Int,
    val heading: String,
    val snippet: String,
)

data class PolicyDocument(
    val id: String,
    val title: String,
    val version: String,
    val effective: LocalDate,
    val owner: String,
    val sections: List<Section>,
) {
    /** [body] is Markdown (paragraphs, bullet lists, **bold**). */
    data class Section(val index: Int, val heading: String, val body: String)
}
