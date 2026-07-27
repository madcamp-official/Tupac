package com.example.mobileguiagent.model

import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolDefinition
import com.example.mobileguiagent.device.DeviceToolExecutor
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.DeviceToolResult
import org.json.JSONArray
import org.json.JSONObject

class LocalDeviceToolAdapter(
    private val registry: DeviceToolRegistry = DeviceToolRegistry(),
) : DeviceToolExecutor {
    private val jsonOutputAdapter = JsonToolCallAdapter()
    private val exaoneOutputAdapter = ExaoneToolCallAdapter(registry.definitions)
    private val lfm2OutputAdapter = Lfm2ToolCallAdapter()

    /**
     * Serializes common Device Tools into the constrained JSON protocol shown
     * to an on-device model. This is a local-model adapter, not an MCP client.
     */
    fun promptSection(
        excludedToolNames: Set<String> = emptySet(),
    ): String = buildString {
        appendLine("AVAILABLE_DEVICE_TOOLS:")
        appendLine(
            JSONArray(
                registry.definitions
                    .filterNot { definition -> definition.name in excludedToolNames }
                    .map { definition -> definition.toPromptJson() },
            ).toString(),
        )
        appendLine("To call a tool, return only this JSON object:")
        appendLine("""{"tool":"tool_name","arguments":{}}""")
        append(
            "For fill_secret, node_id and secret_ref are different identifiers: " +
                "node_id must be an exact node_N from the latest observe_ui result, while " +
                "secret_ref must be an opaque R_... resource reference. Never copy one into the other.",
        )
    }

    /**
     * Native function-list format used by LFM2 Tool checkpoints.
     *
     * Device Tool schemas stay model-independent. Only this serialization and
     * the matching parser differ from the JSON protocol used by Qwen/MiniCPM.
     */
    fun lfm2PromptSection(
        excludedToolNames: Set<String> = emptySet(),
    ): String = buildString {
        val definitions = registry.definitions
            .filterNot { definition -> definition.name in excludedToolNames }
            .map { definition ->
                JSONObject()
                    .put("name", definition.name)
                    .put("description", definition.description)
                    .put(
                        "parameters",
                        JSONObject(definition.inputSchema.toString()),
                    )
            }
        append("List of tools: <|tool_list_start|>")
        append(JSONArray(definitions))
        append("<|tool_list_end|>")
    }

    fun promptSectionFor(
        protocol: ModelToolCallProtocol,
        excludedToolNames: Set<String> = emptySet(),
    ): String = when (protocol) {
        ModelToolCallProtocol.JSON -> promptSection(excludedToolNames)
        ModelToolCallProtocol.EXAONE_JSON_DSL_FALLBACK ->
            exaonePromptSection(excludedToolNames)
        ModelToolCallProtocol.LFM2_NATIVE -> lfm2PromptSection(excludedToolNames)
    }

    /**
     * EXAONE still receives canonical JSON as its primary protocol. The final
     * line documents a very small fallback language for checkpoints whose
     * reasoning exhausts the JSON output budget.
     */
    private fun exaonePromptSection(
        excludedToolNames: Set<String>,
    ): String = buildString {
        append(promptSection(excludedToolNames))
        appendLine()
        appendLine("Do not output reasoning or <think> blocks.")
        append(
            "If and only if valid JSON cannot be completed, return one compact fallback line: " +
                "tool_name positional_arguments. Never mix JSON and the fallback line.",
        )
    }

    /**
     * Smaller registry view for GUI-specialized models that already understand
     * Android actions. It keeps the exact allowlist and argument types without
     * repeating long descriptions or executable history JSON.
     */
    fun compactPromptSection(
        excludedToolNames: Set<String> = emptySet(),
    ): String = buildString {
        appendLine("Allowed tools and arguments:")
        registry.definitions
            .filterNot { definition -> definition.name in excludedToolNames }
            .forEach { definition ->
                val properties = definition.inputSchema.optJSONObject("properties")
                    ?: JSONObject()
                val required = definition.inputSchema.optJSONArray("required")
                    ?: JSONArray()
                val fields = properties.keys().asSequence().joinToString(",") { field ->
                    val type = properties.optJSONObject(field)?.optString("type").orEmpty()
                    val isRequired = (0 until required.length()).any { index ->
                        required.optString(index) == field
                    }
                    "$field:$type${if (isRequired) "" else "?"}"
                }
                appendLine("- ${definition.name}($fields)")
            }
        append(
            "Return JSON with exactly two top-level keys, tool and arguments. " +
                "tool must be one exact name listed above.",
        )
    }

    fun parseToolCall(
        modelOutput: String,
        protocol: ModelToolCallProtocol = ModelToolCallProtocol.JSON,
    ): DeviceToolCall? {
        val parsed = outputAdapter(protocol).parse(modelOutput) ?: return null
        val name = parsed.name
        if (name == GUI_OWL_AGGREGATE_TOOL_NAME) {
            // GUI-Owl exposes one aggregate mobile_use function. It is never
            // executable itself: translate only exact, supported action shapes
            // into registered Device Tools.
            return parsed.envelope?.let(::normalizeGuiOwlCall)
        }
        val definition = registry.definitions.firstOrNull { it.name == name }
        val canonicalCall = definition?.let { registeredDefinition ->
            DeviceToolCall(
                name = name,
                arguments = normalizedArguments(
                    rawArguments = parsed.arguments,
                    definition = registeredDefinition,
                ),
            )
        }
        // "swipe" and "wait" are both canonical tool names and GUI-Owl direct
        // aliases. Prefer a schema-valid canonical call; only reinterpret an
        // invalid shape when its envelope matches GUI-Owl's coordinate grammar.
        if (canonicalCall != null && validationError(canonicalCall) == null) {
            return canonicalCall
        }
        if (name in GUI_OWL_DIRECT_ACTION_NAMES) {
            val guiOwlCall = parsed.envelope?.let { envelope ->
                normalizeGuiOwlDirectAction(action = name, envelope = envelope)
            }
            if (guiOwlCall != null) return guiOwlCall
        }
        return canonicalCall
    }

    private fun outputAdapter(protocol: ModelToolCallProtocol): ToolCallOutputAdapter =
        when (protocol) {
            ModelToolCallProtocol.JSON -> jsonOutputAdapter
            ModelToolCallProtocol.EXAONE_JSON_DSL_FALLBACK -> exaoneOutputAdapter
            ModelToolCallProtocol.LFM2_NATIVE -> lfm2OutputAdapter
        }

    override fun execute(call: DeviceToolCall): DeviceToolResult = registry.execute(call)

    /**
     * Validates the compact JSON-Schema subset used by Device Tools before any
     * Android action is executed.
     */
    fun validationError(call: DeviceToolCall): String? {
        val definition = registry.definitions.firstOrNull { it.name == call.name }
            ?: return "\"${call.name}\" is not a registered tool."
        val schema = definition.inputSchema
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val required = schema.optJSONArray("required")
        if (required != null) {
            for (index in 0 until required.length()) {
                val field = required.optString(index)
                if (!call.arguments.has(field) || call.arguments.isNull(field)) {
                    return "${call.name}.arguments is missing required field \"$field\"."
                }
            }
        }
        val argumentKeys = call.arguments.keys()
        while (argumentKeys.hasNext()) {
            val field = argumentKeys.next()
            val propertySchema = properties.optJSONObject(field)
                ?: return "${call.name}.arguments contains unsupported field \"$field\"."
            val expectedType = propertySchema.optString("type")
            val value = call.arguments.opt(field)
            val validType = when (expectedType) {
                "string" -> value is String
                "number" -> value is Number
                "integer" -> value is Byte || value is Short || value is Int || value is Long
                "object" -> value is JSONObject
                "boolean" -> value is Boolean
                else -> true
            }
            if (!validType) {
                return "${call.name}.arguments.$field must be $expectedType."
            }
        }
        return null
    }

    /**
     * Serializes a tool result for the small on-device planner.
     *
     * Accessibility trees can contain hundreds of repeated layout and icon
     * nodes. Sending them in traversal order both overflows MiniCPM's context
     * and can hide a useful control near the end of the tree. Keep the complete
     * snapshot in memory for execution, while ranking a bounded, generic set of
     * actionable/semantic nodes for the planner. [goal] only raises visible
     * lexical matches; it never maps an app name to a package or an action.
     */
    fun resultJson(
        result: DeviceToolResult,
        goal: String? = null,
    ): JSONObject = when (result) {
        is DeviceToolResult.Action -> JSONObject()
            .put("type", "action")
            .put("action", result.action)
            .put("success", result.success)
            .put("message", result.message)

        is DeviceToolResult.Success -> JSONObject()
            .put("type", "action")
            .put("success", true)
            .put("message", result.message ?: "Action dispatched.")

        is DeviceToolResult.Error -> JSONObject()
            .put("type", "error")
            .put("code", result.code)
            .put("message", result.message)

        is DeviceToolResult.Screenshot -> JSONObject()
            .put("type", "screenshot")
            .put("width", result.width)
            .put("height", result.height)
            .put("message", "Screenshot captured; the image is attached to the next turn.")

        is DeviceToolResult.UiObservation -> {
            val snapshot = result.snapshot
            val plannerNodes = snapshot.nodes
                .asSequence()
                .filter { node ->
                    node.enabled && node.isMeaningfulForAgent()
                }
                .sortedWith(
                    compareByDescending<com.example.mobileguiagent.model.UiNode> { node ->
                        plannerNodeScore(node, goal)
                    }.thenBy { node -> node.depth },
                )
                .distinctBy { node ->
                    listOf(
                        node.text.orEmpty(),
                        node.contentDescription.orEmpty(),
                        node.viewId.orEmpty(),
                        node.clickable.toString(),
                        node.editable.toString(),
                        node.bounds.flattenToString(),
                    ).joinToString("|")
                }
                .take(MAX_OBSERVATION_NODES)
                .toList()
            JSONObject()
                .put("type", "ui_observation")
                .put("foreground_package", snapshot.packageName)
                .put("fingerprint", snapshot.fingerprint.hash)
                .put("node_count", snapshot.nodes.size)
                .put("returned_node_count", plannerNodes.size)
                .put(
                    "nodes",
                    JSONArray(
                        plannerNodes.map { node ->
                                JSONObject()
                                    .put("id", node.id)
                                    .put("text", node.text.orEmpty().take(MAX_NODE_TEXT_LENGTH))
                                    .put(
                                        "content_description",
                                        node.contentDescription.orEmpty()
                                            .take(MAX_NODE_TEXT_LENGTH),
                                    )
                                    .put(
                                        "view_id",
                                        node.viewId.orEmpty().take(MAX_VIEW_ID_LENGTH),
                                    )
                                    .put("semantic_role", node.semanticRole())
                                    .put("clickable", node.clickable)
                                    .put("editable", node.editable)
                                    .put("scrollable", node.scrollable)
                                    .put(
                                        "bounds",
                                        JSONArray(
                                            listOf(
                                                node.bounds.left,
                                                node.bounds.top,
                                                node.bounds.right,
                                                node.bounds.bottom,
                                            ),
                                        ),
                                    )
                            },
                    ),
                )
        }
    }

    fun callSignature(call: DeviceToolCall): String =
        "${call.name}:${call.arguments}"

    /**
     * Returns the requested name even when it is not registered. The caller
     * uses this only to recover into the GUI workflow; unknown tools are never
     * executed and never exposed as assistant/TTS output.
     */
    fun requestedToolName(
        modelOutput: String,
        protocol: ModelToolCallProtocol = ModelToolCallProtocol.JSON,
    ): String? = outputAdapter(protocol).requestedToolName(modelOutput)

    private fun normalizeGuiOwlCall(envelope: JSONObject): DeviceToolCall? {
        if (!envelope.hasExactlyKeys("name", "arguments")) return null
        val arguments = envelope.optJSONObject("arguments") ?: return null
        val action = arguments.opt("action") as? String ?: return null
        return when (action) {
            "click" -> {
                if (!arguments.hasExactlyKeys("action", "coordinate")) return null
                val (x, y) = arguments.exactCoordinate("coordinate") ?: return null
                registeredCall(
                    name = "tap",
                    arguments = JSONObject().put("x", x).put("y", y),
                )
            }

            "type" -> {
                if (!arguments.hasExactlyKeys("action", "text")) return null
                val text = (arguments.opt("text") as? String)
                    ?.takeIf(String::isNotBlank)
                    ?: return null
                registeredCall(
                    name = "set_text",
                    arguments = JSONObject().put("text", text),
                )
            }

            "swipe" -> {
                if (
                    !arguments.hasExactlyKeys(
                        "action",
                        "coordinate",
                        "coordinate2",
                    )
                ) {
                    return null
                }
                val (startX, startY) =
                    arguments.exactCoordinate("coordinate") ?: return null
                val (endX, endY) =
                    arguments.exactCoordinate("coordinate2") ?: return null
                registeredCall(
                    name = "swipe",
                    arguments = JSONObject()
                        .put("start_x", startX)
                        .put("start_y", startY)
                        .put("end_x", endX)
                        .put("end_y", endY),
                )
            }

            "system_button" -> {
                if (!arguments.hasExactlyKeys("action", "button")) return null
                when (arguments.opt("button")) {
                    "Home" -> registeredCall(name = "go_home", arguments = JSONObject())
                    "Back" -> registeredCall(name = "go_back", arguments = JSONObject())
                    else -> null
                }
            }

            "wait" -> {
                if (!arguments.hasExactlyKeys("action", "time")) return null
                val seconds = (arguments.opt("time") as? Number)?.toDouble()
                    ?.takeIf(Double::isFinite)
                    ?: return null
                val milliseconds = seconds * MILLIS_PER_SECOND
                val durationMs = milliseconds.toLong()
                if (
                    durationMs.toDouble() != milliseconds ||
                    !registeredIntegerArgumentWithinBounds(
                        toolName = "wait",
                        field = "duration_ms",
                        value = durationMs,
                    )
                ) {
                    return null
                }
                registeredCall(
                    name = "wait",
                    arguments = JSONObject().put("duration_ms", durationMs),
                )
            }

            "terminate" -> {
                if (!arguments.hasExactlyKeys("action", "status")) return null
                val message = when (arguments.opt("status")) {
                    "success" -> "작업을 완료했습니다."
                    "failure" -> "작업을 완료하지 못했습니다."
                    else -> return null
                }
                registeredCall(
                    name = "finish",
                    arguments = JSONObject().put("message", message),
                )
            }

            else -> null
        }
    }

    private fun normalizeGuiOwlDirectAction(
        action: String,
        envelope: JSONObject,
    ): DeviceToolCall? {
        if (
            !envelope.hasExactlyKeys("tool", "arguments") &&
            !envelope.hasExactlyKeys("name", "arguments")
        ) {
            return null
        }
        val directArguments = envelope.optJSONObject("arguments") ?: return null
        val aggregateArguments = JSONObject(directArguments.toString())
            .put("action", action)
        return normalizeGuiOwlCall(
            JSONObject()
                .put("name", GUI_OWL_AGGREGATE_TOOL_NAME)
                .put("arguments", aggregateArguments),
        )
    }

    private fun registeredCall(
        name: String,
        arguments: JSONObject,
    ): DeviceToolCall? {
        if (registry.definitions.none { definition -> definition.name == name }) return null
        val call = DeviceToolCall(name = name, arguments = arguments)
        return call.takeIf { validationError(it) == null }
    }

    private fun registeredIntegerArgumentWithinBounds(
        toolName: String,
        field: String,
        value: Long,
    ): Boolean {
        val propertySchema = registry.definitions
            .firstOrNull { definition -> definition.name == toolName }
            ?.inputSchema
            ?.optJSONObject("properties")
            ?.optJSONObject(field)
            ?: return false
        val minimum = propertySchema.optLong("minimum", Long.MIN_VALUE)
        val maximum = propertySchema.optLong("maximum", Long.MAX_VALUE)
        return value in minimum..maximum
    }

    private fun JSONObject.exactCoordinate(key: String): Pair<Number, Number>? {
        val coordinate = optJSONArray(key) ?: return null
        if (coordinate.length() != 2) return null
        val x = coordinate.opt(0) as? Number ?: return null
        val y = coordinate.opt(1) as? Number ?: return null
        if (!x.toDouble().isFinite() || !y.toDouble().isFinite()) return null
        return x to y
    }

    private fun JSONObject.hasExactlyKeys(vararg expectedKeys: String): Boolean {
        val actualKeys = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            actualKeys += iterator.next()
        }
        return actualKeys == expectedKeys.toSet()
    }

    private fun normalizedArguments(
        rawArguments: Any?,
        definition: DeviceToolDefinition,
    ): JSONObject {
        if (rawArguments is JSONObject) return rawArguments

        if (rawArguments is String) {
            runCatching { JSONObject(rawArguments) }
                .getOrNull()
                ?.let { return it }
        }

        // Small models sometimes emit `"arguments":"node_8"` for a tool whose
        // schema has exactly one required string field. Recover that shape from
        // the schema rather than from a tool-name or app-specific mapping.
        if (rawArguments is String) {
            val required = definition.inputSchema.optJSONArray("required")
            if (required?.length() == 1) {
                val field = required.optString(0)
                val type = definition.inputSchema
                    .optJSONObject("properties")
                    ?.optJSONObject(field)
                    ?.optString("type")
                if (type == "string") {
                    return JSONObject().put(field, rawArguments)
                }
            }
        }
        return JSONObject()
    }

    private fun DeviceToolDefinition.toPromptJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("input_schema", JSONObject(inputSchema.toString()))

    private fun plannerNodeScore(
        node: com.example.mobileguiagent.model.UiNode,
        goal: String?,
    ): Int {
        val label = listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ")
            .trim()
        val semanticId = node.viewId.orEmpty().lowercase()
        var score = 0
        if (!goal.isNullOrBlank() && visibleWordsOverlap(label, goal)) score += 1_000
        score += when (node.semanticRole()) {
            "text_input" -> 900
            // A launcher search field must outrank dozens of app labels that
            // all happen to overlap a generic goal such as "open an app".
            "search_input" -> 1_800
            "app_collection" -> 600
            "search" -> 500
            "voice_search" -> 300
            "button" -> 200
            "scrollable" -> 150
            else -> 0
        }
        if (SEMANTIC_CONTROL_ID_HINTS.any(semanticId::contains)) score += 100
        if (node.clickable && label.isNotBlank()) score += 300
        if (node.clickable) score += 200
        if (node.scrollable) score += 150
        if (label.isNotBlank()) score += 50
        if (node.viewId?.isNotBlank() == true) score += 25
        return score
    }

    private fun com.example.mobileguiagent.model.UiNode.semanticRole(): String {
        val semanticId = viewId.orEmpty().lowercase()
        return when {
            editable -> "text_input"
            "search" in semanticId &&
                SEARCH_INPUT_ID_HINTS.any(semanticId::contains) -> "search_input"
            "voice" in semanticId && "search" in semanticId -> "voice_search"
            "search" in semanticId -> "search"
            APP_COLLECTION_ID_HINTS.any(semanticId::contains) -> "app_collection"
            "button" in semanticId -> "button"
            scrollable -> "scrollable"
            else -> ""
        }
    }

    private fun visibleWordsOverlap(label: String, goal: String): Boolean {
        val labelWords = label.lowercase()
            .split(NON_WORD_SEPARATOR)
            .filter { word -> word.length >= MIN_MATCH_WORD_LENGTH }
        val goalWords = goal.lowercase()
            .split(NON_WORD_SEPARATOR)
            .filter { word -> word.length >= MIN_MATCH_WORD_LENGTH }
        return labelWords.any { labelWord ->
            goalWords.any { goalWord ->
                labelWord.contains(goalWord) || goalWord.contains(labelWord)
            }
        }
    }

    companion object {
        private const val MAX_OBSERVATION_NODES = 24
        private const val MAX_NODE_TEXT_LENGTH = 80
        private const val MAX_VIEW_ID_LENGTH = 120
        private const val MIN_MATCH_WORD_LENGTH = 2
        private const val MILLIS_PER_SECOND = 1_000.0
        private const val GUI_OWL_AGGREGATE_TOOL_NAME = "mobile_use"
        private val NON_WORD_SEPARATOR = Regex("""[^\p{L}\p{N}]+""")
        private val GUI_OWL_DIRECT_ACTION_NAMES = setOf(
            "click",
            "type",
            "swipe",
            "system_button",
            "wait",
            "terminate",
        )
        private val SEMANTIC_CONTROL_ID_HINTS = listOf(
            "search",
            "input",
            "edit",
            "button",
            "drawer",
            "apps",
            "all_app",
        )
        private val SEARCH_INPUT_ID_HINTS = listOf("edit", "input", "field", "wrapper")
        private val APP_COLLECTION_ID_HINTS = listOf("drawer", "apps", "all_app")
    }
}
