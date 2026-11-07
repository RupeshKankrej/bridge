package bridge.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Minimal JSON Schema (2020-12) validator covering only the keywords tools/protocol/ uses.
 * Any other keyword throws, so a schema can never pass because a rule was silently ignored.
 * shortcut: subset only; switch to a full validator if schemas need $ref, oneOf, etc.
 */
object SchemaValidator {
    private val annotations = setOf("\$schema", "\$id", "title", "description")
    private val supported = annotations + setOf(
        "type", "required", "properties", "additionalProperties", "const", "enum", "pattern", "items", "minimum",
    )

    /** Returns violations as "path: problem"; empty means valid. */
    fun validate(schema: JsonObject, value: JsonElement, path: String = "$"): List<String> {
        val unknown = schema.keys - supported
        require(unknown.isEmpty()) { "unsupported schema keyword(s) at $path: $unknown" }
        val errors = mutableListOf<String>()

        schema["type"]?.let { t ->
            val allowed = if (t is JsonArray) t.map { it.jsonPrimitive.content } else listOf(t.jsonPrimitive.content)
            if (allowed.none { hasType(value, it) }) errors += "$path: expected $allowed, got $value"
        }
        schema["const"]?.let { if (value != it) errors += "$path: expected const $it, got $value" }
        schema["enum"]?.let { if (value !in it.jsonArray) errors += "$path: $value not in ${it.jsonArray}" }
        schema["pattern"]?.let { p ->
            if (value is JsonPrimitive && value.isString && !Regex(p.jsonPrimitive.content).containsMatchIn(value.content)) {
                errors += "$path: '${value.content}' does not match ${p.jsonPrimitive.content}"
            }
        }
        schema["minimum"]?.let { m ->
            val n = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (n != null && n < m.jsonPrimitive.long) errors += "$path: $n < minimum ${m.jsonPrimitive.long}"
        }
        if (value is JsonObject) {
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            schema["required"]?.jsonArray?.forEach { r ->
                if (r.jsonPrimitive.content !in value) errors += "$path: missing required '${r.jsonPrimitive.content}'"
            }
            for ((key, child) in value) {
                val childSchema = properties[key]
                when {
                    childSchema != null -> errors += validate(childSchema.jsonObject, child, "$path.$key")

                    schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false ->
                        errors += "$path: unexpected property '$key'"
                }
            }
        }
        if (value is JsonArray) {
            schema["items"]?.let { items ->
                value.forEachIndexed { i, child -> errors += validate(items.jsonObject, child, "$path[$i]") }
            }
        }
        return errors
    }

    private fun hasType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value is JsonNull
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value !is JsonNull && value.content.toDoubleOrNull() != null
        else -> throw IllegalArgumentException("unsupported type '$type'")
    }
}
