package com.keystone.shared.data.remote

import com.keystone.shared.data.local.cardsFromParts
import com.keystone.shared.data.local.citationsFromParts
import com.keystone.shared.data.remote.dto.DocumentDto
import com.keystone.shared.data.remote.dto.DocumentSectionDto
import com.keystone.shared.data.remote.sse.SseEvent
import com.keystone.shared.data.repository.toDomain
import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.model.Citation
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CitationsTest {

    private val leave = Citation(1, "leave-policy", "Leave Policy", 5, "Carry-over and expiry", "You may carry over…")

    @Test
    fun citationsEventMapsEveryItem() {
        val event = ChatStreamMapper.map(
            SseEvent(
                "citations",
                """{"seq":6,"items":[{"ref":1,"document_id":"leave-policy","title":"Leave Policy",
                   "section_index":5,"heading":"Carry-over and expiry","snippet":"You may carry over…",
                   "future_field":true}]}""",
            ),
        )
        assertEquals(ChatEvent.Citations(listOf(leave)), event)
    }

    @Test
    fun storedPartsGiveBothCardsAndCitations() {
        val parts = """[
            {"type":"tool_result","call_id":"c1","tool":"searchPolicies","status":"ok","ui":"policy_search",
             "data":{"query":"carry over","results":[]}},
            {"type":"citations","items":[{"ref":1,"document_id":"leave-policy","title":"Leave Policy",
             "section_index":5,"heading":"Carry-over and expiry","snippet":"You may carry over…"}]}
        ]"""
        assertEquals(listOf(leave), citationsFromParts(parts))
        assertEquals(emptyList(), cardsFromParts(parts)) // policy_search has no card
    }

    @Test
    fun malformedCitationsPartIsSkipped() {
        assertEquals(emptyList(), citationsFromParts("""[{"type":"citations","items":"oops"}]"""))
        assertEquals(emptyList(), citationsFromParts("[]"))
    }

    @Test
    fun documentDtoMapsToDomain() {
        val doc = DocumentDto(
            id = "leave-policy",
            title = "Leave Policy",
            version = "2026.1",
            effective = "2026-01-01",
            owner = "People Team",
            sections = listOf(DocumentSectionDto(0, "Overview", "Intro")),
        ).toDomain()
        assertEquals(LocalDate(2026, 1, 1), doc.effective)
        assertEquals("Overview", doc.sections.single().heading)
    }
}
