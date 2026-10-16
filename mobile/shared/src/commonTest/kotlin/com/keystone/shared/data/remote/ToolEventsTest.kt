package com.keystone.shared.data.remote

import com.keystone.shared.data.local.cardsFromParts
import com.keystone.shared.data.remote.sse.SseEvent
import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.model.ToolCard
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolEventsTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun result(ui: String, data: String, status: String = "ok") = SseEvent(
        event = "tool.result",
        data = """{"seq":3,"call_id":"c1","tool":"t","status":"$status","ui":"$ui","data":$data}""",
    )

    @Test
    fun toolStartedMapsLabel() {
        val event = ChatStreamMapper.map(
            SseEvent("tool.started", """{"seq":2,"call_id":"c1","tool":"getTasks","label":"Checking your tasks"}"""),
        )
        assertEquals(ChatEvent.ToolStarted("c1", "getTasks", "Checking your tasks"), event)
    }

    @Test
    fun failedToolHasNoCard() {
        val event = assertIs<ChatEvent.ToolResult>(
            ChatStreamMapper.map(result("tasks", """{"error":"tool_timeout"}""", status = "error")),
        )
        assertEquals(false, event.ok)
        assertNull(event.card)
    }

    @Test
    fun unknownCardTypeIsIgnoredButResultStillArrives() {
        val event = assertIs<ChatEvent.ToolResult>(ChatStreamMapper.map(result("payslip", """{"net":1}""")))
        assertTrue(event.ok)
        assertNull(event.card)
    }

    @Test
    fun malformedCardDataGivesNoCardInsteadOfCrashing() {
        assertNull(ToolCardParser.parse("tasks", obj("""{"items":[{"id":"1"}]}""")))
        assertNull(ToolCardParser.parse("profile", obj("""{"name":"A","job_title":"x","department":"d","manager":"m","location":"l","start_date":"not-a-date"}""")))
    }

    @Test
    fun parsesTasksWithStatusAndOverdue() {
        val card = ToolCardParser.parse(
            "tasks",
            obj("""{"filter":"all","items":[
                {"id":"t1","title":"Sign handbook","category":"onboarding","due":"2026-10-10","status":"pending","overdue":true},
                {"id":"t2","title":"Upload ID","category":"onboarding","due":"2026-10-01","status":"done","overdue":false}]}"""),
        )
        assertEquals(
            ToolCard.Tasks(
                listOf(
                    ToolCard.Tasks.Item("t1", "Sign handbook", "onboarding", LocalDate(2026, 10, 10), done = false, overdue = true),
                    ToolCard.Tasks.Item("t2", "Upload ID", "onboarding", LocalDate(2026, 10, 1), done = true, overdue = false),
                ),
            ),
            card,
        )
    }

    @Test
    fun parsesAppointmentsWithOffsetTimestamps() {
        val card = assertIs<ToolCard.Appointments>(
            ToolCardParser.parse(
                "appointments",
                obj("""{"filter":"upcoming","items":[{"id":"a1","title":"1:1","with":"Priya","start":"2026-10-16T10:00:00+00:00","end":"2026-10-16T10:30:00+00:00","location":"Room 2"}]}"""),
            ),
        )
        assertEquals(Instant.parse("2026-10-16T10:00:00Z"), card.items.single().start)
    }

    @Test
    fun storedPartsBecomeCardsSkippingErrorsAndUnknownTypes() {
        val parts = """[
            {"type":"tool_result","call_id":"c1","tool":"getLeaveBalance","status":"ok","ui":"leave_balance",
             "data":{"year":2026,"unit":"days","items":[{"type":"Annual","entitled":20,"used":6,"remaining":14}]}},
            {"type":"tool_result","call_id":"c2","tool":"getTasks","status":"error","ui":"tasks","data":{"error":"tool_failed"}},
            {"type":"tool_result","call_id":"c3","tool":"x","status":"ok","ui":"future_card","data":{}},
            {"type":"citation","text":"later phases","data":[1,2]},
            {"no_type":true},
            "not even an object"
        ]"""
        val cards = cardsFromParts(parts)
        assertEquals(1, cards.size)
        assertIs<ToolCard.LeaveBalance>(cards.single())
    }

    @Test
    fun toolEventsWithNullOptionalFieldsDoNotBreakTheStream() {
        assertEquals(
            ChatEvent.ToolStarted("c1", "getTasks", ""),
            ChatStreamMapper.map(SseEvent("tool.started", """{"seq":2,"call_id":"c1","tool":"getTasks","label":null}""")),
        )
        val result = assertIs<ChatEvent.ToolResult>(
            ChatStreamMapper.map(
                SseEvent("tool.result", """{"seq":3,"call_id":"c1","tool":"getTasks","status":"error","ui":null,"data":null}"""),
            ),
        )
        assertNull(result.card)
    }

    @Test
    fun corruptPartsJsonGivesNoCards() {
        assertEquals(emptyList(), cardsFromParts("not json"))
    }
}
