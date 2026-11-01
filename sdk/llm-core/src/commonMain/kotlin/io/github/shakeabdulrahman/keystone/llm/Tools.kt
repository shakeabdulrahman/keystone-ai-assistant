package io.github.shakeabdulrahman.keystone.llm

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A function the model may ask your app to call.
 *
 * The SDK never runs tools. It reports [ToolCall]s; your app decides whether and how to run
 * them and sends back [Message.ToolResult]s.
 *
 * @property parameters JSON Schema of the arguments object. The SDK validates arguments
 * against the subset described in [io.github.shakeabdulrahman.keystone.llm.schema.JsonSchemaValidator].
 */
public class Tool(
    public val name: String,
    public val description: String,
    public val parameters: JsonObject,
) {
    init {
        require(NAME.matches(name)) { "Tool name must match ${NAME.pattern}: '$name'" }
    }

    override fun equals(other: Any?): Boolean =
        other is Tool && name == other.name && description == other.description && parameters == other.parameters

    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = "Tool($name)"

    private companion object {
        // The intersection of what OpenAI-compatible APIs and Gemini accept.
        val NAME = Regex("^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$")
    }
}

/** Whether, and which, tools the model must call. */
public sealed interface ToolChoice {
    public data object Auto : ToolChoice
    public data object None : ToolChoice
    public data object Required : ToolChoice
    public data class Specific(val toolName: String) : ToolChoice
}

/**
 * A complete tool call requested by the model.
 *
 * @property id the provider's call id, or `call_<index>` if the provider sent none.
 * @property arguments the validated arguments, or why they are invalid.
 * @property rawArguments the arguments exactly as the model produced them.
 * @property providerMetadata opaque values the provider needs back with this call
 * (for example Gemini's thought signature). Do not modify.
 */
public data class ToolCall(
    val id: String,
    val name: String,
    val arguments: ToolArguments,
    val rawArguments: String,
    val providerMetadata: Map<String, String> = emptyMap(),
) {
    /** The arguments, or `null` when they failed validation (see [arguments]). */
    public val argumentsOrNull: JsonObject? get() = (arguments as? ToolArguments.Valid)?.json

    /**
     * Decodes valid arguments into [T].
     * @throws IllegalStateException if the arguments are invalid.
     * @throws SerializationException if they don't match [T].
     */
    public fun <T> decodeArguments(deserializer: DeserializationStrategy<T>, json: Json = Json): T {
        val valid = arguments as? ToolArguments.Valid
            ?: throw IllegalStateException("Tool call '$name' has invalid arguments: ${(arguments as ToolArguments.Invalid).error}")
        return json.decodeFromJsonElement(deserializer, valid.json)
    }
}

public sealed interface ToolArguments {
    public data class Valid(val json: JsonObject) : ToolArguments
    public data class Invalid(val error: ToolArgumentError) : ToolArguments
}

public sealed interface ToolArgumentError {
    /** The model called a tool that the request did not declare. */
    public data object UnknownTool : ToolArgumentError

    /** The arguments are not valid JSON (often: the output was cut off). */
    public data class MalformedJson(val detail: String) : ToolArgumentError

    /** Valid JSON, but not an object. */
    public data object NotAnObject : ToolArgumentError

    public data class SchemaMismatch(val violations: List<SchemaViolation>) : ToolArgumentError
}

/** One way a JSON value doesn't match a schema. [path] is like `$.items[2].name`. */
public data class SchemaViolation(val path: String, val message: String)
