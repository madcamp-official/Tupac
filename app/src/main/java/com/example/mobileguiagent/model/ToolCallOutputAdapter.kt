package com.example.mobileguiagent.model

import com.example.mobileguiagent.device.DeviceToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * Syntax-only result produced at the model boundary.
 *
 * It is deliberately not executable. [LocalDeviceToolAdapter] still resolves
 * aliases, validates the registered schema, applies policy, and only then
 * creates the canonical DeviceToolCall consumed by Android.
 */
internal data class ParsedToolCallOutput(
    val name: String,
    val arguments: Any?,
    val envelope: JSONObject? = null,
)

/**
 * Converts one model-specific output protocol into a syntax-only tool call.
 *
 * Model quirks stay here. The Device Tool registry and executor never need to
 * know whether a checkpoint emitted JSON, EXAONE's compact fallback DSL, or a
 * native LFM2 function call.
 */
internal interface ToolCallOutputAdapter {
    fun parse(output: String): ParsedToolCallOutput?

    fun requestedToolName(output: String): String? = parse(output)?.name
}

internal class JsonToolCallAdapter : ToolCallOutputAdapter {
    override fun parse(output: String): ParsedToolCallOutput? {
        val balanced = firstBalancedJsonObject(output)
        val jsonText = balanced
            ?.takeIf { candidate -> runCatching { JSONObject(candidate) }.isSuccess }
            ?: recoverMalformedDirectClick(output)
            ?: return null
        val envelope = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val name = envelope.requestedToolName() ?: return null
        return ParsedToolCallOutput(
            name = name,
            arguments = envelope.opt("arguments"),
            envelope = envelope,
        )
    }

    override fun requestedToolName(output: String): String? {
        val balanced = firstBalancedJsonObject(output)
        val jsonText = balanced
            ?.takeIf { candidate -> runCatching { JSONObject(candidate) }.isSuccess }
            ?: recoverMalformedDirectClick(output)
            ?: return null
        return runCatching { JSONObject(jsonText).requestedToolName() }.getOrNull()
    }

    /**
     * Finds only the first complete object. A later object must never turn an
     * invalid first action into an executable second action.
     */
    private fun firstBalancedJsonObject(output: String): String? {
        var startIndex = -1
        var depth = 0
        var insideString = false
        var escaped = false

        output.forEachIndexed { index, character ->
            if (startIndex < 0) {
                if (character == '{') {
                    startIndex = index
                    depth = 1
                }
                return@forEachIndexed
            }

            if (insideString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> insideString = false
                }
                return@forEachIndexed
            }

            when (character) {
                '"' -> insideString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return output.substring(startIndex, index + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
    }

    /**
     * Recovers one observed Q4 formatting error and no broader malformed JSON:
     * `"coordinate":436,934` becomes the supported direct click envelope.
     */
    private fun recoverMalformedDirectClick(output: String): String? {
        val match = MALFORMED_DIRECT_CLICK_PATTERN.matchEntire(output.trim()) ?: return null
        val x = match.groupValues[1].toDoubleOrNull()?.takeIf(Double::isFinite)
            ?: return null
        val y = match.groupValues[2].toDoubleOrNull()?.takeIf(Double::isFinite)
            ?: return null
        return JSONObject()
            .put("name", "click")
            .put("arguments", JSONObject().put("coordinate", JSONArray(listOf(x, y))))
            .toString()
    }

    private fun JSONObject.requestedToolName(): String? {
        val legacyName = optString("tool").trim()
        val nativeName = optString("name").trim()
        if (
            legacyName.isNotEmpty() &&
            nativeName.isNotEmpty() &&
            legacyName != nativeName
        ) {
            return null
        }
        return legacyName.ifEmpty { nativeName }.takeIf(String::isNotBlank)
    }

    private companion object {
        val MALFORMED_DIRECT_CLICK_PATTERN = Regex(
            """\{\s*"(?:tool|name)"\s*:\s*"click"\s*,\s*"arguments"\s*:\s*\{\s*"coordinate"\s*:\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\}\s*\}""",
        )
    }
}

/**
 * EXAONE uses canonical JSON first. The compact DSL is only a bounded fallback
 * for checkpoints that still spend their small output budget on reasoning.
 */
internal class ExaoneToolCallAdapter(
    definitions: List<DeviceToolDefinition>,
    private val jsonAdapter: JsonToolCallAdapter = JsonToolCallAdapter(),
) : ToolCallOutputAdapter {
    private val definitionsByName = definitions.associateBy(DeviceToolDefinition::name)

    override fun parse(output: String): ParsedToolCallOutput? {
        val answer = stripCompletedThinking(output)
        jsonAdapter.parse(answer)?.let { return it }
        return parseDsl(answer)
    }

    override fun requestedToolName(output: String): String? {
        val answer = stripCompletedThinking(output)
        jsonAdapter.requestedToolName(answer)?.let { return it }
        return DSL_CALL_PATTERN.matchEntire(firstAnswerLine(answer))
            ?.groupValues
            ?.get(1)
    }

    private fun parseDsl(output: String): ParsedToolCallOutput? {
        val line = firstAnswerLine(output)
        val match = DSL_CALL_PATTERN.matchEntire(line) ?: return null
        val name = match.groupValues[1]
        val definition = definitionsByName[name]
            ?: return ParsedToolCallOutput(name, JSONObject())
        val rawArguments = match.groupValues[2].trim()
        val arguments = when {
            rawArguments.isEmpty() -> JSONObject()
            rawArguments.startsWith('{') ->
                runCatching { JSONObject(rawArguments) }.getOrNull() ?: return null
            NAMED_ARGUMENT_START.containsMatchIn(rawArguments) ->
                parseNamedArguments(rawArguments, definition) ?: return null
            else -> parsePositionalArguments(rawArguments, definition) ?: return null
        }
        return ParsedToolCallOutput(
            name = name,
            arguments = arguments,
            envelope = JSONObject()
                .put("tool", name)
                .put("arguments", arguments),
        )
    }

    private fun parseNamedArguments(
        source: String,
        definition: DeviceToolDefinition,
    ): JSONObject? {
        val properties = definition.inputSchema.optJSONObject("properties") ?: JSONObject()
        val output = JSONObject()
        var cursor = 0
        while (cursor < source.length) {
            val match = NAMED_ARGUMENT_PATTERN.find(source, cursor) ?: return null
            if (match.range.first != cursor) return null
            val field = match.groupValues[1]
            val fieldSchema = properties.optJSONObject(field) ?: return null
            val value = parseScalar(match.groupValues[2], fieldSchema) ?: return null
            output.put(field, value)
            cursor = match.range.last + 1
        }
        return output
    }

    private fun parsePositionalArguments(
        source: String,
        definition: DeviceToolDefinition,
    ): JSONObject? {
        val properties = definition.inputSchema.optJSONObject("properties") ?: JSONObject()
        val required = definition.inputSchema.optJSONArray("required") ?: JSONArray()
        val orderedFields = buildList {
            for (index in 0 until required.length()) {
                required.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
            properties.keys().forEach { field ->
                if (field !in this) add(field)
            }
        }

        // A one-string command intentionally consumes the rest of the line so
        // Korean text with spaces does not require a custom escaping language.
        if (
            orderedFields.size == 1 &&
            properties.optJSONObject(orderedFields.single())?.optString("type") == "string"
        ) {
            val value = parseScalar(source, properties.getJSONObject(orderedFields.single()))
                ?: source
            return JSONObject().put(orderedFields.single(), value)
        }

        val tokens = tokenizeScalars(source) ?: return null
        if (tokens.size !in required.length()..orderedFields.size) return null
        return JSONObject().apply {
            tokens.forEachIndexed { index, token ->
                val field = orderedFields[index]
                val value = parseScalar(token, properties.optJSONObject(field) ?: return null)
                    ?: return null
                put(field, value)
            }
        }
    }

    private fun tokenizeScalars(source: String): List<String>? {
        val tokens = mutableListOf<String>()
        var cursor = 0
        while (cursor < source.length) {
            while (cursor < source.length && source[cursor].isWhitespace()) cursor += 1
            if (cursor >= source.length) break
            val start = cursor
            if (source[cursor] == '"') {
                cursor += 1
                var escaped = false
                var closed = false
                while (cursor < source.length) {
                    val character = source[cursor]
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> {
                            cursor += 1
                            closed = true
                            break
                        }
                    }
                    cursor += 1
                }
                if (!closed) return null
            } else {
                while (cursor < source.length && !source[cursor].isWhitespace()) cursor += 1
            }
            tokens += source.substring(start, cursor)
        }
        return tokens
    }

    private fun parseScalar(raw: String, schema: JSONObject): Any? {
        val trimmed = raw.trim().removeSuffix(",")
        return when (schema.optString("type")) {
            "string" ->
                if (trimmed.startsWith('"')) {
                    runCatching {
                        JSONObject("""{"value":$trimmed}""").getString("value")
                    }.getOrNull()
                } else {
                    trimmed.takeIf(String::isNotBlank)
                }
            "boolean" -> when (trimmed) {
                "true" -> true
                "false" -> false
                else -> null
            }
            "integer" -> trimmed.toLongOrNull()
            "number" -> trimmed.toDoubleOrNull()?.takeIf(Double::isFinite)
            else -> null
        }
    }

    private fun stripCompletedThinking(output: String): String {
        val stripped = COMPLETE_THINK_BLOCK.replace(output, "").trim()
        // An unfinished think block has no final answer. Reject it instead of
        // guessing an action from private reasoning text.
        return if ("<think>" in stripped && "</think>" !in stripped) "" else stripped
    }

    private fun firstAnswerLine(output: String): String = output
        .lineSequence()
        .map(String::trim)
        .filter { line -> line.isNotEmpty() && !line.startsWith("```") }
        .firstOrNull()
        .orEmpty()

    private companion object {
        val COMPLETE_THINK_BLOCK = Regex(
            """<think>.*?</think>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val DSL_CALL_PATTERN = Regex("""^([A-Za-z_][A-Za-z0-9_]*)(?:\s+(.*))?$""")
        val NAMED_ARGUMENT_START = Regex("""^[A-Za-z_][A-Za-z0-9_]*\s*=""")
        val NAMED_ARGUMENT_PATTERN = Regex(
            """\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*("(?:\\.|[^"\\])*"|true|false|-?\d+(?:\.\d+)?)\s*(?:,\s*|\s+|$)""",
        )
    }
}

internal class Lfm2ToolCallAdapter : ToolCallOutputAdapter {
    override fun parse(output: String): ParsedToolCallOutput? {
        val match = LFM2_TOOL_CALL_PATTERN.find(output) ?: return null
        val arguments = parseArguments(match.groupValues[2]) ?: return null
        return ParsedToolCallOutput(
            name = match.groupValues[1],
            arguments = arguments,
        )
    }

    override fun requestedToolName(output: String): String? =
        LFM2_TOOL_CALL_PATTERN.find(output)?.groupValues?.get(1)

    private fun parseArguments(rawArguments: String): JSONObject? {
        val source = rawArguments.trim()
        if (source.isEmpty()) return JSONObject()
        val output = JSONObject()
        var cursor = 0
        while (cursor < source.length) {
            val match = LFM2_ARGUMENT_PATTERN.find(source, cursor) ?: return null
            if (match.range.first != cursor) return null
            val key = match.groupValues[1]
            val rawValue = match.groupValues[2]
            val value: Any = when {
                rawValue.startsWith('"') ->
                    runCatching {
                        JSONObject("""{"value":$rawValue}""").getString("value")
                    }.getOrNull() ?: return null
                rawValue == "true" -> true
                rawValue == "false" -> false
                rawValue == "null" -> return null
                '.' in rawValue -> rawValue.toDoubleOrNull() ?: return null
                else -> rawValue.toLongOrNull() ?: return null
            }
            output.put(key, value)
            cursor = match.range.last + 1
        }
        return output
    }

    private companion object {
        val LFM2_TOOL_CALL_PATTERN = Regex(
            """(?:<\|tool_call_start\|>\s*)?\[\s*([A-Za-z_][A-Za-z0-9_]*)\s*\((.*?)\)\s*]\s*(?:<\|tool_call_end\|>)?""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val LFM2_ARGUMENT_PATTERN = Regex(
            """\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*("(?:\\.|[^"\\])*"|true|false|null|-?\d+(?:\.\d+)?)\s*(?:,\s*|$)""",
        )
    }
}
