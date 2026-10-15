package com.keystone.shared.data.repository

import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.data.local.ConversationEntity
import com.keystone.shared.data.local.FakeConversationDao
import com.keystone.shared.data.local.FakeMessageDao
import com.keystone.shared.data.local.MessageEntity
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.createHttpClient
import com.keystone.shared.domain.model.StoredMessage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConversationRepositoryImplTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val conversationDao = FakeConversationDao()
    private val messageDao = FakeMessageDao()

    private fun repository(handler: MockRequestHandler) = ConversationRepositoryImpl(
        KeystoneApi(createHttpClient(MockEngine(handler)), "http://test"),
        conversationDao,
        messageDao,
    )

    private fun conversationJson(id: String, title: String, at: String) =
        """{"id":"$id","title":"$title","created_at":"$at","updated_at":"$at","last_message_at":"$at"}"""

    @Test
    fun refreshReplacesCacheNewestFirst() = runTest {
        conversationDao.upsertAll(listOf(ConversationEntity("stale", "Deleted elsewhere", 1)))
        val repo = repository {
            respond(
                """{"items":[${conversationJson("b", "Newer", "2026-10-15T10:00:00.5Z")},""" +
                    """${conversationJson("a", "Older", "2026-10-14T09:00:00Z")}],"next_cursor":null}""",
                HttpStatusCode.OK,
                json,
            )
        }

        assertEquals(AppResult.Success(false), repo.refreshConversations())

        val cached = repo.observeConversations().first()
        assertEquals(listOf("b", "a"), cached.map { it.id })
        assertEquals("Newer", cached.first().title)
    }

    @Test
    fun offlineRefreshFailsButKeepsCache() = runTest {
        conversationDao.upsertAll(listOf(ConversationEntity("a", "Cached", 1)))
        val repo = repository { throw IOException("offline") }

        assertEquals(AppResult.Failure(AppError.Network), repo.refreshConversations())
        assertEquals(listOf("Cached"), repo.observeConversations().first().map { it.title })
    }

    @Test
    fun renameRollsBackWhenServerRefuses() = runTest {
        conversationDao.upsertAll(listOf(ConversationEntity("a", "Original", 1)))
        val repo = repository { respond("""{"code":"not_found"}""", HttpStatusCode.NotFound, json) }

        val result = repo.rename("a", "New name")

        assertIs<AppResult.Failure>(result)
        assertEquals("Original", conversationDao.get("a")?.title)
    }

    @Test
    fun renameKeepsNewTitleOnSuccess() = runTest {
        conversationDao.upsertAll(listOf(ConversationEntity("a", "Original", 1)))
        val repo = repository { request ->
            assertEquals(HttpMethod.Patch, request.method)
            respond(conversationJson("a", "New name", "2026-10-15T10:00:00Z"), HttpStatusCode.OK, json)
        }

        assertEquals(AppResult.Success(Unit), repo.rename("a", "New name"))
        assertEquals("New name", conversationDao.get("a")?.title)
    }

    @Test
    fun deleteRestoresConversationAndMessagesWhenOffline() = runTest {
        conversationDao.upsertAll(listOf(ConversationEntity("a", "Keep me", 1)))
        messageDao.upsertAll(listOf(MessageEntity("m1", "a", "user", "Hi", "complete", 1)))
        val repo = repository { throw IOException("offline") }

        assertIs<AppResult.Failure>(repo.delete("a"))

        assertEquals("Keep me", conversationDao.get("a")?.title)
        assertEquals(listOf("m1"), messageDao.getAll("a").map { it.id })
    }

    @Test
    fun refreshMessagesMapsRolesAndStatuses() = runTest {
        val repo = repository {
            respond(
                """{"items":[
                    {"id":"1","role":"user","content":"Q","status":"complete","created_at":"2026-10-15T10:00:00Z"},
                    {"id":"2","role":"assistant","content":"Part","status":"interrupted","created_at":"2026-10-15T10:00:01Z"}
                ],"next_cursor":null}""",
                HttpStatusCode.OK,
                json,
            )
        }

        repo.refreshMessages("c1")

        val messages = repo.observeMessages("c1").first()
        assertEquals(listOf(StoredMessage.Role.User, StoredMessage.Role.Assistant), messages.map { it.role })
        assertEquals(StoredMessage.Status.Interrupted, messages[1].status)
    }

    @Test
    fun clearCacheWipesEverything() = runTest {
        conversationDao.upsertAll(listOf(ConversationEntity("a", "x", 1)))
        messageDao.upsertAll(listOf(MessageEntity("m1", "a", "user", "Hi", "complete", 1)))
        val repo = repository { error("no network calls expected") }

        repo.clearCache()

        assertEquals(emptyList(), repo.observeConversations().first())
        assertEquals(emptyList(), repo.observeMessages("a").first())
    }

    @Test
    fun malformedDateIsAnErrorNotACrash() = runTest {
        val repo = repository {
            respond("""{"items":[${conversationJson("a", "t", "not-a-date")}]}""", HttpStatusCode.OK, json)
        }

        assertIs<AppResult.Failure>(repo.refreshConversations())
    }
}
