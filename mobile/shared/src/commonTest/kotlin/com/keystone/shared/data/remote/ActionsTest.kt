package com.keystone.shared.data.remote

import com.keystone.shared.data.local.actionsFromParts
import com.keystone.shared.data.remote.sse.SseEvent
import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.model.PendingAction
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActionsTest {

    private val card = """"tool":"submitLeaveRequest","title":"Request annual leave",
        "fields":[{"label":"Dates","value":"Mon 26 Oct 2026"}],"notes":["Ask early"],
        "confirm_label":"Submit request","expires_at":"2026-10-15T09:15:00+00:00""""

    @Test
    fun confirmationEventMapsToAPendingAction() {
        val event = assertIs<ChatEvent.ConfirmationRequired>(
            ChatStreamMapper.map(SseEvent("confirmation.required", """{"seq":4,"action_id":"a1",$card}""")),
        )
        assertEquals(
            PendingAction(
                id = "a1",
                tool = "submitLeaveRequest",
                title = "Request annual leave",
                fields = listOf(PendingAction.Field("Dates", "Mon 26 Oct 2026")),
                notes = listOf("Ask early"),
                confirmLabel = "Submit request",
                expiresAt = Instant.parse("2026-10-15T09:15:00Z"),
                status = PendingAction.Status.Pending,
            ),
            event.action,
        )
    }

    @Test
    fun storedPartsCarryTheCurrentState() {
        val parts = """[
            {"type":"tool_result","call_id":"c1","tool":"submitLeaveRequest","status":"ok","ui":null,"data":{}},
            {"type":"confirmation","action_id":"a1",$card,"status":"executed",
             "result":{"reference":"LR-1050","message":"Submitted."},"error":null},
            {"type":"confirmation","action_id":"a2",$card,"status":"failed",
             "result":null,"error":{"code":"rejected","message":"Only 1 day left."}},
            {"type":"confirmation","action_id":"a3",$card,"status":"something_new"},
            {"type":"confirmation","action_id":"a4","tool":"x","title":"x","expires_at":"not a date"}
        ]"""
        val actions = actionsFromParts(parts)
        assertEquals(listOf("a1", "a2", "a3"), actions.map { it.id }) // a4 is unusable: skipped
        assertEquals(PendingAction.Result("LR-1050", "Submitted."), actions[0].result)
        assertEquals("Only 1 day left.", actions[1].failureMessage)
        // An unknown status is treated as closed: no buttons for something we don't understand.
        assertEquals(PendingAction.Status.Failed, actions[2].status)
    }
}
