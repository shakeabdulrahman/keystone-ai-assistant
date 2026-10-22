package com.keystone.shared.data.repository

import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.data.local.FakeCachedJsonDao
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.createHttpClient
import com.keystone.shared.domain.model.AttentionItem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HomeRepositoryImplTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val body = """{"generated_at":"2026-10-15T09:00:00+00:00","first_name":"Aisha",
        "leave":[{"type":"Annual","remaining":14,"entitled":20}],
        "attention":[
          {"id":"action-1","kind":"pending_action","severity":"high","title":"Waiting for you",
           "detail":"Not confirmed","action":{"type":"open_conversation","conversation_id":"c1","action_id":"a1"}},
          {"id":"task-T-301","kind":"overdue_task","severity":"high","title":"Upload ID",
           "detail":"Overdue by 2 days","action":{"type":"ask","prompt":"Help me with my task T-301"}},
          {"id":"x","kind":"something_new","title":"Future item","action":{"type":"teleport"}}],
        "upcoming":[{"id":"A-50","title":"1:1","with":"Your manager","start":"2026-10-22T11:00:00+00:00","location":"Teams"}],
        "counts":{"open_hr_requests":2,"pending_leave_requests":1,"unread_notifications":3},
        "suggestions":["Which of my tasks are overdue?"]}"""

    private fun repository(cache: FakeCachedJsonDao, handler: MockRequestHandler) =
        HomeRepositoryImpl(KeystoneApi(createHttpClient(MockEngine(handler)), "http://test"), cache)

    @Test
    fun refreshFillsTheCacheAndObserversSeeTheDashboard() = runTest {
        val cache = FakeCachedJsonDao()
        val repo = repository(cache) { request ->
            assertEquals("http://test/v1/home", request.url.toString())
            respond(body, HttpStatusCode.OK, json)
        }
        assertNull(repo.observe().first())

        assertEquals(AppResult.Success(Unit), repo.refresh())

        val home = assertNotNull(repo.observe().first())
        assertEquals("Aisha", home.firstName)
        assertEquals(
            AttentionItem.Action.OpenConversation("c1", "a1"),
            home.attention[0].action,
        )
        assertEquals(AttentionItem.Action.Ask("Help me with my task T-301"), home.attention[1].action)
        // An item kind this version doesn't know is still shown, without a broken action.
        assertEquals(AttentionItem.Kind.Other, home.attention[2].kind)
        assertEquals(AttentionItem.Action.None, home.attention[2].action)
        assertEquals(3, home.counts.unreadNotifications)
    }

    @Test
    fun offlineKeepsTheLastDashboard() = runTest {
        val cache = FakeCachedJsonDao()
        var online = true
        val repo = repository(cache) {
            if (!online) throw IOException("offline")
            respond(body, HttpStatusCode.OK, json)
        }
        repo.refresh()
        online = false

        assertEquals(AppResult.Failure(AppError.Network), repo.refresh())
        assertEquals("Aisha", repo.observe().first()?.firstName)
    }

    @Test
    fun anUnusableResponseIsNotCached() = runTest {
        val cache = FakeCachedJsonDao()
        val repo = repository(cache) { respond(body.replace("2026-10-15T09:00:00+00:00", "soon"), HttpStatusCode.OK, json) }
        assertIs<AppResult.Failure>(repo.refresh())
        assertNull(repo.observe().first())
    }

    @Test
    fun clearCacheForgetsTheDashboard() = runTest {
        val cache = FakeCachedJsonDao()
        val repo = repository(cache) { respond(body, HttpStatusCode.OK, json) }
        repo.refresh()
        repo.clearCache()
        assertNull(repo.observe().first())
    }

    @Test
    fun devicesRegisterAndUnregisterWithTheirToken() = runTest {
        val calls = mutableListOf<String>()
        val api = KeystoneApi(
            createHttpClient(
                MockEngine { request ->
                    calls += "${request.url.encodedPath} ${request.body.toByteArray().decodeToString()}"
                    respond("", HttpStatusCode.NoContent)
                },
            ),
            "http://test",
        )
        val devices = DeviceRepositoryImpl(api, platform = "android")
        assertEquals(AppResult.Success(Unit), devices.register("tok-123"))
        assertEquals(AppResult.Success(Unit), devices.unregister("tok-123"))
        assertEquals(
            listOf(
                """/v1/devices {"token":"tok-123","platform":"android"}""",
                """/v1/devices/unregister {"token":"tok-123"}""",
            ),
            calls,
        )
    }
}
