# Tools and structured output

## Tools

```kotlin
val weather = Tool(
    name = "get_weather",
    description = "Current weather for a city",
    parameters = JsonSchemaGenerator.from(serializer<WeatherArgs>().descriptor), // or a hand-written JsonObject
)
val response = llm.chat(request.copy(tools = listOf(weather), toolChoice = ToolChoice.Auto))

for (call in response.toolCalls) {
    when (val args = call.arguments) {
        is ToolArguments.Valid -> {
            val result = runMyTool(call.decodeArguments(WeatherArgs.serializer()))
            next += Message.ToolResult(call.id, call.name, Json.encodeToString(result))
        }
        is ToolArguments.Invalid -> next += Message.ToolResult(call.id, call.name, "Invalid arguments: ${args.error}", isError = true)
    }
}
// Continue: original messages + response.message (unchanged) + the tool results.
```

- **The SDK never runs tools.** It reports calls; your app decides.
- Arguments are validated when the answer ends: valid JSON, an object, a declared tool, and
  the schema subset below. Problems are reported as `ToolArguments.Invalid` with a reason
  (`UnknownTool`, `MalformedJson`, `NotAnObject`, `SchemaMismatch`). They never throw, so you
  can send the error back to the model to fix.
- Call ids stay stable from `ToolCallStarted` to `ToolCallCompleted`. Providers that send no
  id (Gemini) get `call_<index>`.
- Send the assistant message back unchanged: `providerMetadata` carries data some providers
  require (Gemini's thought signature).

## Structured output

```kotlin
@Serializable data class Person(val name: String, val age: Int, val nickname: String? = null)

val result = llm.chatStructured<Person>(request)            // schema derived from the serializer
result.value          // Person
result.enforcement    // ProviderSchema | ProviderJsonMode | PromptOnly
```

| Who validates | When |
|---|---|
| **Client, always** | The answer is parsed, checked against the schema subset, then decoded |
| Provider, in addition | Only when `enforcement == ProviderSchema` (OpenAI `json_schema`, Gemini `responseSchema`; Gemini's is a converted subset) |

If the target can't enforce schemas, the default is `LlmErrorKind.Unsupported`, which lets the
request fail over to a target that can. With `allowDowngrade = true`, the SDK uses JSON mode
(`ProviderJsonMode`) or, failing that, an instruction in the prompt (`PromptOnly`, where a
Markdown code fence is tolerated). The result is validated the same way.

Failures are `StructuredOutputException` (kind `StructuredOutput`) with a `reason`:
`Truncated` (hit the token limit), `NoContent`, `MalformedJson`, `SchemaMismatch(violations)`
or `DecodingFailed`. `rawText` holds the model's answer. **Structured output is not retried
automatically**: retrying costs money and often fails the same way, so the decision is yours.

`strict = true` asks OpenAI for strict mode, which requires every property to be `required`.
The generator marks properties with defaults as optional, so use strict only with schemas
that satisfy that rule.

## Schema subset

`JsonSchemaValidator` checks `type` (a name or a list), `properties`, `required`,
`additionalProperties` (boolean or schema), `items`, `enum`, `anyOf` and OpenAPI `nullable`.
It does not check `format`, `pattern`, length/number bounds or `$ref`. `JsonSchemaGenerator`
supports primitives, enums, classes, lists, string-keyed maps and nullability. Polymorphic,
contextual and recursive types need a hand-written schema.
