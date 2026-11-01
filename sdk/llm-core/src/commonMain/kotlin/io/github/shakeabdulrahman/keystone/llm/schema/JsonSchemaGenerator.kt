package io.github.shakeabdulrahman.keystone.llm.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Derives a JSON Schema from a kotlinx.serialization descriptor, for tool parameters and
 * structured output.
 *
 * Supported: primitives, enums, classes/objects, lists, maps with string keys, nullability.
 * A property with a default value is optional (not in `required`). Classes get
 * `additionalProperties: false`. Unsupported (throws [IllegalArgumentException]): polymorphic
 * and contextual types, non-string map keys, and recursive types. Write those schemas by hand.
 *
 * Property descriptions aren't available from descriptors; add them by editing the result.
 */
@OptIn(ExperimentalSerializationApi::class)
public object JsonSchemaGenerator {
    public fun from(descriptor: SerialDescriptor): JsonObject = schema(descriptor, emptySet())

    private fun schema(d: SerialDescriptor, seen: Set<String>): JsonObject {
        val base = nonNull(d, seen)
        if (!d.isNullable) return base
        return when (val t = base["type"]) {
            is JsonPrimitive -> JsonObject(base + ("type" to kotlinx.serialization.json.JsonArray(listOf(t, JsonPrimitive("null")))))
            else -> buildJsonObject {
                putJsonArray("anyOf") {
                    add(base)
                    add(buildJsonObject { put("type", "null") })
                }
            }
        }
    }

    private fun nonNull(d: SerialDescriptor, seen: Set<String>): JsonObject = when (val kind = d.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> type("string")
        PrimitiveKind.BOOLEAN -> type("boolean")
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> type("integer")
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number")
        SerialKind.ENUM -> buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { for (i in 0 until d.elementsCount) add(d.getElementName(i)) }
        }
        StructureKind.LIST -> buildJsonObject {
            put("type", "array")
            put("items", schema(d.getElementDescriptor(0), seen))
        }
        StructureKind.MAP -> {
            val key = d.getElementDescriptor(0)
            require(key.kind == PrimitiveKind.STRING || key.kind == SerialKind.ENUM) {
                "Map keys must be strings for a JSON schema: ${d.serialName}"
            }
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", schema(d.getElementDescriptor(1), seen))
            }
        }
        StructureKind.CLASS, StructureKind.OBJECT -> {
            val name = d.serialName.removeSuffix("?")
            require(name !in seen) { "Recursive type $name: write this schema by hand" }
            val inner = seen + name
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    for (i in 0 until d.elementsCount) put(d.getElementName(i), schema(d.getElementDescriptor(i), inner))
                }
                putJsonArray("required") {
                    for (i in 0 until d.elementsCount) if (!d.isElementOptional(i)) add(d.getElementName(i))
                }
                put("additionalProperties", false)
            }
        }
        is PolymorphicKind, SerialKind.CONTEXTUAL ->
            throw IllegalArgumentException("${d.serialName}: polymorphic/contextual types need a hand-written schema")
        else -> throw IllegalArgumentException("${d.serialName}: unsupported kind $kind")
    }

    private fun type(name: String) = buildJsonObject { put("type", name) }
}
