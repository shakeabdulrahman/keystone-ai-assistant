package io.github.shakeabdulrahman.keystone.llm.serializable

import io.github.shakeabdulrahman.keystone.llm.ChatRequest
import io.github.shakeabdulrahman.keystone.llm.Message
import io.github.shakeabdulrahman.keystone.llm.ModelRef
import io.github.shakeabdulrahman.keystone.llm.StructuredOutputException
import io.github.shakeabdulrahman.keystone.llm.chatStructured
import io.github.shakeabdulrahman.keystone.llm.schema.JsonSchemaGenerator
import io.github.shakeabdulrahman.keystone.llm.testing.FakeProvider
import io.github.shakeabdulrahman.keystone.llm.testing.answer
import io.github.shakeabdulrahman.keystone.llm.testing.testClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

// Needs the kotlinx.serialization compiler plugin, so it only runs in the Gradle build.

@Serializable
enum class Mood { Happy, Sad }

@Serializable
data class Person(val name: String, val age: Int, val mood: Mood, val nickname: String? = null, val tags: List<String> = emptyList())

class SerializableStructuredOutputTest {
    private val model = ModelRef("a", "m")

    @Test
    fun schemaIsDerivedFromTheDataClass() {
        val schema = JsonSchemaGenerator.from(serializer<Person>().descriptor)
        val expected = Json.parseToJsonElement(
            """
            {"type":"object","properties":{
              "name":{"type":"string"},
              "age":{"type":"integer"},
              "mood":{"type":"string","enum":["Happy","Sad"]},
              "nickname":{"type":["string","null"]},
              "tags":{"type":"array","items":{"type":"string"}}},
             "required":["name","age","mood"],
             "additionalProperties":false}
            """,
        )
        assertEquals(expected, schema)
    }

    @Test
    fun reifiedChatStructuredDecodesTheDataClass() = runTest {
        val provider = FakeProvider("a") { _, _ -> answer("""{"name":"Asha","age":31,"mood":"Happy"}""") }
        val (client, _) = testClient(provider)
        val person = client.chatStructured<Person>(ChatRequest.of(model, Message.user("Invent a person"))).value
        assertEquals(Person("Asha", 31, Mood.Happy), person)
    }

    @Test
    fun anInventedEnumValueIsASchemaMismatch() = runTest {
        val provider = FakeProvider("a") { _, _ -> answer("""{"name":"Asha","age":31,"mood":"Ecstatic"}""") }
        val (client, _) = testClient(provider)
        val e = assertFailsWith<StructuredOutputException> {
            client.chatStructured<Person>(ChatRequest.of(model, Message.user("Invent a person")))
        }
        assertIs<StructuredOutputException.Reason.SchemaMismatch>(e.reason)
    }
}
