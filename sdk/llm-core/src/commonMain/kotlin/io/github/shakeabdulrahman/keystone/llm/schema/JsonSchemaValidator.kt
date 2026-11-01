package io.github.shakeabdulrahman.keystone.llm.schema

import io.github.shakeabdulrahman.keystone.llm.SchemaViolation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Validates JSON against the subset of JSON Schema that LLM tool and output schemas use:
 * `type` (a name or a list of names), `properties`, `required`, `additionalProperties`
 * (boolean or schema), `items`, `enum`, `anyOf`, and OpenAPI's `nullable`.
 *
 * Other keywords (`format`, `minLength`, `pattern`, `$ref`, ...) are **not** checked. This
 * catches what models typically get wrong (missing fields, wrong types, invented enum values);
 * it is not a full validator.
 */
public object JsonSchemaValidator {
    public fun validate(value: JsonElement, schema: JsonObject): List<SchemaViolation> {
        val out = mutableListOf<SchemaViolation>()
        check(value, schema, "$", out, depth = 0)
        return out
    }

    private fun check(value: JsonElement, schema: JsonObject, path: String, out: MutableList<SchemaViolation>, depth: Int) {
        if (depth > 64) return // pathological nesting: stop rather than overflow the stack
        if (value is JsonNull && schema["nullable"]?.jsonPrimitiveOrNull()?.booleanOrNull == true) return

        (schema["anyOf"] as? JsonArray)?.let { options ->
            val schemas = options.filterIsInstance<JsonObject>()
            if (schemas.isNotEmpty() && schemas.none { validateInto(value, it, path, depth).isEmpty() }) {
                out += SchemaViolation(path, "does not match any allowed schema")
            }
        }

        val types = when (val t = schema["type"]) {
            is JsonPrimitive -> listOf(t.content)
            is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }.map { it.lowercase() }
        if (types.isNotEmpty() && types.none { matchesType(value, it) }) {
            out += SchemaViolation(path, "expected ${types.joinToString(" or ")}, got ${describe(value)}")
            return
        }

        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (allowed.none { sameJson(it, value) }) out += SchemaViolation(path, "value not in enum")
        }

        if (value is JsonObject) {
            val props = schema["properties"] as? JsonObject
            (schema["required"] as? JsonArray)?.forEach { r ->
                val name = (r as? JsonPrimitive)?.content ?: return@forEach
                if (name !in value) out += SchemaViolation(path, "missing required property '$name'")
            }
            for ((key, child) in value) {
                val childSchema = props?.get(key) as? JsonObject
                when {
                    childSchema != null -> check(child, childSchema, "$path.$key", out, depth + 1)
                    schema["additionalProperties"] is JsonObject ->
                        check(child, schema["additionalProperties"] as JsonObject, "$path.$key", out, depth + 1)
                    schema["additionalProperties"]?.jsonPrimitiveOrNull()?.booleanOrNull == false ->
                        out += SchemaViolation("$path.$key", "property not allowed")
                }
            }
        }
        if (value is JsonArray) {
            val items = schema["items"] as? JsonObject
            if (items != null) value.forEachIndexed { i, e -> check(e, items, "$path[$i]", out, depth + 1) }
        }
    }

    private fun validateInto(value: JsonElement, schema: JsonObject, path: String, depth: Int): List<SchemaViolation> =
        mutableListOf<SchemaViolation>().also { check(value, schema, path, it, depth + 1) }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value is JsonNull
        "string" -> value is JsonPrimitive && value !is JsonNull && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value !is JsonNull &&
            (value.longOrNull != null || value.doubleOrNull?.let { it % 1.0 == 0.0 } == true)
        "number" -> value is JsonPrimitive && !value.isString && value !is JsonNull && value.doubleOrNull != null
        else -> true // unknown type keyword: don't reject
    }

    private fun describe(value: JsonElement): String = when {
        value is JsonNull -> "null"
        value is JsonObject -> "object"
        value is JsonArray -> "array"
        value is JsonPrimitive && value.isString -> "string"
        value is JsonPrimitive && value.booleanOrNull != null -> "boolean"
        else -> "number"
    }

    private fun sameJson(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonPrimitive && b is JsonPrimitive && a !is JsonNull && b !is JsonNull && !a.isString && !b.isString) {
            val x = a.doubleOrNull
            val y = b.doubleOrNull
            if (x != null && y != null) return x == y
        }
        return a == b
    }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
}
