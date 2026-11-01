package io.github.shakeabdulrahman.keystone.llm.internal

import io.github.shakeabdulrahman.keystone.llm.ProviderEvent
import io.github.shakeabdulrahman.keystone.llm.StreamEvent
import io.github.shakeabdulrahman.keystone.llm.Tool
import io.github.shakeabdulrahman.keystone.llm.ToolArgumentError
import io.github.shakeabdulrahman.keystone.llm.ToolArguments
import io.github.shakeabdulrahman.keystone.llm.ToolCall
import io.github.shakeabdulrahman.keystone.llm.schema.JsonSchemaValidator
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Builds complete tool calls from streamed pieces, keyed by index, so ids, names and argument
 * fragments of parallel calls never mix. Validation happens once, when the answer ends.
 */
internal class ToolCallAssembler(tools: List<Tool>) {
    private val toolsByName = tools.associateBy { it.name }

    private class Slot(val index: Int) {
        var id: String? = null
        var name = StringBuilder()
        val arguments = StringBuilder()
        var metadata: Map<String, String> = emptyMap()
        var announced = false
    }

    private val slots = LinkedHashMap<Int, Slot>()

    /** The stream indices of the calls, in the order [complete] returns them. */
    fun indices(): List<Int> = slots.keys.sorted()

    /** Applies one chunk and returns the stream events it produces. */
    fun accept(chunk: ProviderEvent.ToolCallChunk): List<StreamEvent> {
        val slot = slots.getOrPut(chunk.index) { Slot(chunk.index) }
        // Once a call is announced its id is fixed, so ToolCallStarted and ToolCallCompleted agree.
        if (!chunk.id.isNullOrEmpty() && !slot.announced) slot.id = chunk.id
        // The name comes whole in the first piece that has one; it is fixed once announced.
        if (!chunk.name.isNullOrEmpty() && !slot.announced) slot.name.append(chunk.name)
        if (chunk.providerMetadata.isNotEmpty()) slot.metadata = slot.metadata + chunk.providerMetadata
        val out = ArrayList<StreamEvent>(2)
        if (!slot.announced && slot.name.isNotEmpty()) {
            slot.announced = true
            val id = slot.id ?: syntheticId(slot.index).also { slot.id = it }
            out += StreamEvent.ToolCallStarted(slot.index, id, slot.name.toString())
            if (slot.arguments.isNotEmpty()) out += StreamEvent.ToolCallDelta(slot.index, slot.arguments.toString())
        }
        if (chunk.argumentsDelta.isNotEmpty()) {
            slot.arguments.append(chunk.argumentsDelta)
            if (slot.announced) out += StreamEvent.ToolCallDelta(slot.index, chunk.argumentsDelta)
        }
        return out
    }

    /** All calls, in index order, with validated arguments. */
    fun complete(): List<ToolCall> = slots.values.sortedBy { it.index }.map { slot ->
        val raw = slot.arguments.toString()
        val name = slot.name.toString()
        ToolCall(
            id = slot.id ?: syntheticId(slot.index),
            name = name,
            arguments = validate(name, raw),
            rawArguments = raw,
            providerMetadata = slot.metadata,
        )
    }

    private fun validate(name: String, raw: String): ToolArguments {
        val tool = toolsByName[name] ?: return ToolArguments.Invalid(ToolArgumentError.UnknownTool)
        // An empty string is how several providers send "no arguments".
        val element = if (raw.isBlank()) {
            JsonObject(emptyMap())
        } else {
            try {
                Json.parseToJsonElement(raw)
            } catch (e: SerializationException) {
                return ToolArguments.Invalid(ToolArgumentError.MalformedJson(e.message?.take(200) ?: "invalid JSON"))
            }
        }
        if (element !is JsonObject) return ToolArguments.Invalid(ToolArgumentError.NotAnObject)
        val violations = JsonSchemaValidator.validate(element, tool.parameters)
        return if (violations.isEmpty()) ToolArguments.Valid(element) else ToolArguments.Invalid(ToolArgumentError.SchemaMismatch(violations))
    }

    private fun syntheticId(index: Int) = "call_$index"
}
