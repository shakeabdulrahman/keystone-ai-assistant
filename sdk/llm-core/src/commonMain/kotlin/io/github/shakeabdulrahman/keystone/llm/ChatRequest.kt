package io.github.shakeabdulrahman.keystone.llm

import kotlinx.serialization.json.JsonObject

/** A model on a registered provider, e.g. `ModelRef("groq", "openai/gpt-oss-120b")`. */
public data class ModelRef(val provider: String, val model: String) {
    override fun toString(): String = "$provider/$model"
}

/**
 * The models to try, in order. The first is the primary; the rest are failover targets,
 * used only as allowed by the client's [FailoverPolicy].
 */
public data class Route(val targets: List<ModelRef>) {
    init {
        require(targets.isNotEmpty()) { "A route needs at least one model" }
    }

    public constructor(primary: ModelRef, vararg fallbacks: ModelRef) : this(listOf(primary, *fallbacks))
}

/** How the model must format its answer. */
public sealed interface ResponseFormat {
    public data object Text : ResponseFormat

    /** Any JSON object ("JSON mode"). Validated client-side as JSON only. */
    public data object JsonMode : ResponseFormat

    /**
     * JSON matching [schema].
     *
     * @property strict ask the provider for strict schema adherence where it offers it
     * (OpenAI `strict`). Strict mode usually requires every property to be `required`.
     * @property allowDowngrade if the target can't enforce a schema, use JSON mode or (failing
     * that) instructions in the prompt instead of failing with [LlmErrorKind.Unsupported].
     * The answer is validated client-side either way.
     */
    public data class JsonSchema(
        val name: String,
        val schema: JsonObject,
        val strict: Boolean = false,
        val allowDowngrade: Boolean = false,
    ) : ResponseFormat
}

/**
 * A request to a chat model.
 *
 * @property route which model(s) to use. `null` means the client's default route.
 * @property providerOptions raw JSON fields merged into the provider's request body, keyed by
 * provider id. An escape hatch for vendor features the SDK doesn't model; it overrides
 * fields the SDK sets, so use it carefully.
 */
public data class ChatRequest(
    val messages: List<Message>,
    val route: Route? = null,
    val tools: List<Tool> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val responseFormat: ResponseFormat = ResponseFormat.Text,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stopSequences: List<String> = emptyList(),
    val providerOptions: Map<String, JsonObject> = emptyMap(),
) {
    init {
        require(messages.isNotEmpty()) { "A request needs at least one message" }
        require(tools.map { it.name }.toSet().size == tools.size) { "Tool names must be unique" }
        if (toolChoice is ToolChoice.Specific) {
            require(tools.any { it.name == toolChoice.toolName }) { "toolChoice names an undeclared tool" }
        }
        maxOutputTokens?.let { require(it > 0) { "maxOutputTokens must be positive" } }
    }

    public companion object {
        /** Shorthand for a one-model request. */
        public fun of(model: ModelRef, vararg messages: Message): ChatRequest =
            ChatRequest(messages = messages.toList(), route = Route(model))
    }
}
