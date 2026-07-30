package com.example.mobileguiagent.cloud

import android.util.Base64
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.model.AgentGoalSpec
import com.example.mobileguiagent.model.AgentGoalSpecJson
import com.example.mobileguiagent.model.AgentSkillBundle
import com.example.mobileguiagent.model.TaskContract
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.model.isMeaningfulForAgent
import com.example.mobileguiagent.agent.AgentWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class GeminiPlannerAction(
    val action: String,
    /** Short auditable rationale, not hidden chain-of-thought. */
    val reasonCode: String = "UNSPECIFIED",
    val target: String = "UNSPECIFIED",
    val expectedChange: String = "UNSPECIFIED",
    val x: Double? = null,
    val y: Double? = null,
    val endX: Double? = null,
    val endY: Double? = null,
    val nodeId: String? = null,
    val option: String? = null,
    val direction: String? = null,
    val elementId: String? = null,
    val text: String? = null,
    val appName: String? = null,
    val query: String? = null,
    val durationMs: Long? = null,
    val message: String? = null,
    /** Auditable, concise plan—not private chain-of-thought. */
    val plan: List<String> = emptyList(),
    val progressSummary: String = "No durable progress reported",
)

data class GeminiPlannerRequest(
    val goal: String,
    val step: Int,
    val maxSteps: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val observation: UiSnapshot,
    /**
     * Deduplicated accessibility + OCR elements. Empty keeps the legacy
     * accessibility-only payload for before/after benchmarks.
     */
    val screenElements: List<ScreenElement> = emptyList(),
    /** Null for the cheap UI-tree-first pass; attached only on explicit visual request. */
    val screenshot: DeviceToolResult.Screenshot? = null,
    val recentActions: List<String>,
    val skills: AgentSkillBundle = AgentSkillBundle.EMPTY,
    val taskContract: TaskContract? = null,
    val workspace: AgentWorkspace? = null,
    /** Package that hosts the agent UI itself, not the user's target app. */
    val agentHostPackage: String? = null,
)

data class GeminiMeasuredDecision(
    val action: GeminiPlannerAction,
    val requestBytes: Int,
    val promptTokenCount: Int?,
    val candidatesTokenCount: Int?,
    val totalTokenCount: Int?,
)

class GeminiApiClient : AgentPlanner, AgentGoalInterpreter {
    override suspend fun decide(
        apiKey: String,
        model: GeminiModel,
        request: GeminiPlannerRequest,
    ): GeminiMeasuredDecision {
        val measured = executeJson(
            apiKey = apiKey,
            model = model,
            requestBody = buildRequestBody(request),
        )
        return GeminiMeasuredDecision(
            action = parseResponse(measured.responseText),
            requestBytes = measured.requestBytes,
            promptTokenCount = measured.promptTokenCount,
            candidatesTokenCount = measured.candidatesTokenCount,
            totalTokenCount = measured.totalTokenCount,
        )
    }

    override suspend fun interpret(
        apiKey: String,
        model: GeminiModel,
        request: AgentGoalInterpretationRequest,
    ): AgentMeasuredGoalSpec {
        val startedAt = System.nanoTime()
        val measured = executeJson(
            apiKey = apiKey,
            model = model,
            requestBody = buildGoalInterpretationRequestBody(request),
        )
        return AgentMeasuredGoalSpec(
            spec = parseGoalSpec(measured.responseText),
            latencyMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND,
            requestBytes = measured.requestBytes,
            promptTokenCount = measured.promptTokenCount,
            candidatesTokenCount = measured.candidatesTokenCount,
            totalTokenCount = measured.totalTokenCount,
        )
    }

    private suspend fun executeJson(
        apiKey: String,
        model: GeminiModel,
        requestBody: JSONObject,
    ): MeasuredJsonResponse = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) {
            "GEMINI_API_KEY가 설정되지 않았습니다. local.properties에 값을 추가하세요."
        }
        coroutineContext.ensureActive()
        val connection = (
            URL(
                "$API_BASE/models/${model.apiId}:generateContent",
            ).openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            val requestBytes = requestBody.toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { output ->
                output.write(requestBytes)
            }
            coroutineContext.ensureActive()
            val status = connection.responseCode
            val responseText = (
                if (status in 200..299) connection.inputStream else connection.errorStream
                )?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val apiMessage = runCatching {
                    JSONObject(responseText)
                        .optJSONObject("error")
                        ?.optString("message")
                }.getOrNull()
                throw GeminiPlannerException(
                    httpStatus = status,
                    message = "Gemini API 오류 HTTP $status: " +
                        (apiMessage ?: responseText.take(300)),
                )
            }
            val response = JSONObject(responseText)
            val usage = response.optJSONObject("usageMetadata")
            MeasuredJsonResponse(
                responseText = responseText,
                requestBytes = requestBytes.size,
                promptTokenCount = usage.optionalInt("promptTokenCount"),
                candidatesTokenCount = usage.optionalInt("candidatesTokenCount"),
                totalTokenCount = usage.optionalInt("totalTokenCount"),
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildGoalInterpretationRequestBody(
        request: AgentGoalInterpretationRequest,
    ): JSONObject = JSONObject()
        .put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", goalInterpretationPrompt(request)),
                        ),
                    ),
            ),
        )
        .put(
            "generationConfig",
            JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseJsonSchema", goalSpecSchema())
                .put("maxOutputTokens", GOAL_SPEC_MAX_OUTPUT_TOKENS)
                .put(
                    "thinkingConfig",
                    JSONObject().put("thinkingLevel", "minimal"),
                ),
        )

    internal fun parseGoalSpec(responseText: String): AgentGoalSpec {
        val generated = generatedJsonText(responseText)
        return AgentGoalSpecJson.decode(JSONObject(generated))
    }

    internal fun buildRequestBody(request: GeminiPlannerRequest): JSONObject {
        val parts = JSONArray()
            .put(JSONObject().put("text", plannerPrompt(request)))
        request.screenshot?.let { screenshot ->
            val imageData = Base64.encodeToString(
                screenshot.jpegBytes,
                Base64.NO_WRAP,
            )
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", imageData),
                ),
            )
        }
        return JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            parts,
                        ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseJsonSchema", PlannerActionCatalog.responseSchema())
                    .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    .put(
                        "thinkingConfig",
                        JSONObject().put("thinkingLevel", "minimal"),
                    ),
            )
    }

    internal fun parseResponse(responseText: String): GeminiPlannerAction {
        val action = JSONObject(generatedJsonText(responseText))
        return GeminiPlannerAction(
            action = action.getString("action"),
            reasonCode = action.optString("reason_code", "UNSPECIFIED"),
            target = action.optString("target", "UNSPECIFIED"),
            expectedChange = action.optString("expected_change", "UNSPECIFIED"),
            x = action.optionalDouble("x"),
            y = action.optionalDouble("y"),
            endX = action.optionalDouble("end_x"),
            endY = action.optionalDouble("end_y"),
            nodeId = action.optString("node_id").takeIf(String::isNotBlank),
            option = action.optString("option").takeIf(String::isNotBlank),
            direction = action.optString("direction").takeIf(String::isNotBlank),
            elementId = action.optString("element_id").takeIf(String::isNotBlank),
            text = action.optString("text").takeIf(String::isNotBlank),
            appName = action.optString("app_name").takeIf(String::isNotBlank),
            query = action.optString("query").takeIf(String::isNotBlank),
            durationMs = action.optionalLong("duration_ms"),
            message = action.optString("message").takeIf(String::isNotBlank),
            plan = action.optJSONArray("plan").stringList(),
            progressSummary = action.optString(
                "progress_summary",
                "No durable progress reported",
            ),
        )
    }

    private fun generatedJsonText(responseText: String): String {
        val parts = JSONObject(responseText)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: error("Gemini 응답에 JSON이 없습니다.")
        val generatedText = buildString {
            for (index in 0 until parts.length()) {
                append(parts.optJSONObject(index)?.optString("text").orEmpty())
            }
        }.trim()
        require(generatedText.isNotBlank()) { "Gemini가 빈 JSON을 반환했습니다." }
        return generatedText
    }

    private fun goalInterpretationPrompt(request: AgentGoalInterpretationRequest): String =
        buildString {
            appendLine("Interpret the user's goal into one app-independent structured goal spec.")
            appendLine(
                "You are the semantic goal interpreter, not the GUI planner. Do not choose " +
                    "screen coordinates, node ids, view ids, packages, or Android tools.",
            )
            appendLine(
                "Use open semantic names such as product, variant.color, quantity, date, " +
                    "movie, audience.adult, seat.type, or price.max. The schema must remain " +
                    "usable for unfamiliar apps and domains.",
            )
            appendLine(
                "Put explicitly required objects in entities. Put non-negotiable conditions " +
                    "in constraints and ranking wishes in preferences. Values are strings; " +
                    "normalize unambiguous dates to YYYY-MM-DD and times to HH:mm.",
            )
            appendLine(
                "When the user names a target app or service, include it as a required entity " +
                    "named app using its ordinary product name. The runtime uses semantic " +
                    "entities—not raw-goal regexes—to activate optional skills.",
            )
            appendLine(
                "Do not invent missing user choices. Record a necessary default in assumptions. " +
                    "Completion criteria must be observable, and forbidden_actions must include " +
                    "any irreversible boundary the user says not to cross.",
            )
            appendLine(
                "The runtime always keeps execute_payment behind a user confirmation boundary; " +
                    "include it in forbidden_actions for purchase or booking preparation.",
            )
            request.skills.activationCatalogSection()
                .takeIf(String::isNotBlank)
                ?.let { skills ->
                appendLine()
                appendLine(skills)
                appendLine(
                    "Do not copy app-specific workflow fields into the semantic goal spec.",
                )
            }
            appendLine()
            appendLine("USER_GOAL: ${request.goal}")
        }

    private fun goalSpecSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("objective", stringSchema())
                .put(
                    "entities",
                    arraySchema(
                        objectSchema(
                            JSONObject()
                                .put("name", keySchema())
                                .put("value", stringSchema())
                                .put("required", JSONObject().put("type", "boolean")),
                            listOf("name", "value", "required"),
                        ),
                    ),
                )
                .put(
                    "constraints",
                    arraySchema(
                        objectSchema(
                            JSONObject()
                                .put("subject", keySchema())
                                .put("operator", keySchema())
                                .put("value", stringSchema())
                                .put("hard", JSONObject().put("type", "boolean")),
                            listOf("subject", "operator", "value", "hard"),
                        ),
                    ),
                )
                .put(
                    "preferences",
                    arraySchema(
                        objectSchema(
                            JSONObject()
                                .put("subject", keySchema())
                                .put("operator", keySchema())
                                .put("value", stringSchema())
                                .put("fallback", stringSchema()),
                            listOf("subject", "operator", "value"),
                        ),
                    ),
                )
                .put("success_criteria", arraySchema(stringSchema()))
                .put("forbidden_actions", arraySchema(keySchema()))
                .put("assumptions", arraySchema(stringSchema())),
        )
        .put(
            "required",
            JSONArray(
                listOf(
                    "objective",
                    "entities",
                    "constraints",
                    "preferences",
                    "success_criteria",
                    "forbidden_actions",
                    "assumptions",
                ),
            ),
        )
        .put("additionalProperties", false)

    private fun stringSchema(): JSONObject = JSONObject()
        .put("type", "string")
        .put("maxLength", 500)

    private fun keySchema(): JSONObject = JSONObject()
        .put("type", "string")
        .put("pattern", """^[\p{L}\p{N}_.-]+$""")
        .put("maxLength", 80)

    private fun arraySchema(items: JSONObject): JSONObject = JSONObject()
        .put("type", "array")
        .put("items", items)

    private fun objectSchema(
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required))
        .put("additionalProperties", false)

    private fun plannerPrompt(request: GeminiPlannerRequest): String {
        val legacyNodes = request.observation.nodes
            .asSequence()
            .filter { node -> node.enabled && node.isMeaningfulForAgent() }
            // Traversal order often puts dozens of launcher icons before the
            // app search field. Preserve the payload cap, but spend it on the
            // controls most useful for planning instead of the first layouts
            // encountered in the accessibility tree.
            .sortedByDescending { node -> node.plannerPriority() }
            .take(MAX_UI_NODES)
            .map { node ->
                JSONObject()
                    .put("id", node.id)
                    .put("text", node.text.orEmpty())
                    .put("description", node.contentDescription.orEmpty())
                    .put("view_id", node.viewId.orEmpty())
                    .put("clickable", node.clickable)
                    .put("editable", node.editable)
                    .put("scrollable", node.scrollable)
                    .put("checked", node.checked ?: JSONObject.NULL)
                    .put("selected", node.selected)
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
            }
            .toList()
        val fusedElements = request.screenElements
            .asSequence()
            .sortedByDescending { element -> element.plannerPriority() }
            .take(MAX_SCREEN_ELEMENTS)
            .map { element ->
                JSONObject()
                    .put("id", element.id)
                    .put("node_id", element.nodeId ?: JSONObject.NULL)
                    .put("text", element.text.orEmpty())
                    .put("description", element.contentDescription.orEmpty())
                    .put("view_id", element.viewId.orEmpty())
                    .put("clickable", element.clickable)
                    .put("editable", element.editable)
                    .put("scrollable", element.scrollable)
                    .put("checked", element.checked ?: JSONObject.NULL)
                    .put("selected", element.selected ?: JSONObject.NULL)
                    .put(
                        "confidence",
                        element.confidence ?: JSONObject.NULL,
                    )
                    .put(
                        "sources",
                        JSONArray(
                            element.sources.map { source ->
                                source.name.lowercase()
                            },
                        ),
                    )
                    .put(
                        "bounds",
                        JSONArray(
                            listOf(
                                element.bounds.left,
                                element.bounds.top,
                                element.bounds.right,
                                element.bounds.bottom,
                            ),
                        ),
                    )
            }
            .toList()
        val fusedPayload = fusedElements.isNotEmpty()
        return buildString {
            appendLine("You are the visual action planner for an Android GUI agent.")
            appendLine("Choose exactly one safe next action from the current Android screen state.")
            appendLine(
                "Return a short auditable decision summary with every action: reason_code " +
                    "(machine-readable uppercase label), target (the visible control or " +
                    "container), and expected_change (one short observable result). Do not " +
                    "return private chain-of-thought.",
            )
            appendLine(
                "Also return plan as a short ordered list of remaining outcome-level steps " +
                    "and progress_summary as one factual sentence. Revise the plan when the " +
                    "durable workspace or current screen disproves an assumption.",
            )
            appendLine("The runtime executes the action, captures a newer screen, and calls you again.")
            appendLine(
                "Never invent a package name, shell, intent, MCP, or unavailable action. " +
                    "Use launch_app with the app's visible name when the goal names an app.",
            )
            appendLine("Prefer tap_node when a current node clearly identifies an ordinary button.")
            appendLine(
                "When the goal requires an exact value and the screen shows an option, size, " +
                    "color, dropdown, spinner, or '옵션을 선택' control, use select_option " +
                    "directly with that selector's node_id and the exact requested option. " +
                    "Do not tap the selector first and do not retry tap_node on it; " +
                    "select_option owns opening the control, finding the option, and selecting it.",
            )
            appendLine(
                "If a select_option tool result reports OPTION_NOT_FOUND, treat that as " +
                    "definitive evidence that the current item does not offer the required " +
                    "option. Never tap or retry the same selector. Navigate back and choose " +
                    "a different candidate that satisfies the goal.",
            )
            if (fusedPayload) {
                appendLine(
                    "SCREEN_ELEMENTS already deduplicates accessibility, local OCR, and " +
                        "local icon-shape candidates. sources may include accessibility, " +
                        "ocr, or icon.",
                )
                appendLine(
                    "Use tap_element with the element's id for every SCREEN_ELEMENTS target. " +
                        "The Android runtime resolves accessibility nodes or exact OCR bounds; " +
                        "do not calculate coordinates for a listed element.",
                )
                appendLine(
                    "An icon-only close candidate is not proof that a popup exists. Use its " +
                        "bounds, confidence, nearby text, and the current goal before selecting it.",
                )
            }
            appendLine("Required arguments by action:")
            PlannerActionCatalog.contract.forEach { (action, arguments) ->
                appendLine("- $action: $arguments")
            }
            appendLine(
                "Keep swipe coordinates away from system edges: every coordinate should " +
                    "normally be between 50 and 950.",
            )
            appendLine(
                "Prefer scroll over swipe whenever ACCESSIBILITY_NODES or SCREEN_ELEMENTS " +
                    "contains the intended scrollable container. Use raw swipe only for an " +
                    "intentional drag or when no scrollable container exists.",
            )
            appendLine(
                "On an Android launcher, do not keep paging horizontally when an app is " +
                    "not visible. Open the app drawer with a vertical upward swipe from " +
                    "the content area (start_y=850, end_y=200), then use the launcher's app search.",
            )
            appendLine(
                "If RECENT_ACTIONS says screen_changed=false, the action did not achieve " +
                    "navigation even when dispatch reported success. Change strategy.",
            )
            if (request.screenshot == null) {
                appendLine(
                    "No screenshot is attached. If the UI tree cannot ground the next action, " +
                        "return request_visual instead of guessing.",
                )
            } else {
                appendLine("A current screenshot is attached for visual grounding.")
                appendLine("Use tap coordinates only for visual controls absent from the UI tree.")
            }
            appendLine("Coordinates use a virtual 0..1000 screen, independent of image resizing.")
            appendLine(
                "Android's origin is top-left: x increases rightward and y increases downward. " +
                    "An upward swipe MUST have start_y > end_y.",
            )
            appendLine("For a popup or WebView advertisement, click its visible close/X control.")
            appendLine("Do not assume a dispatched action succeeded; verify on the next screenshot.")
            appendLine(
                "Return finish_success only when this screenshot proves the whole goal complete. " +
                    "For finish_success, target must quote the exact visible label that proves " +
                    "completion; generic summaries are rejected.",
            )
            appendLine()
            appendLine("GOAL: ${request.goal}")
            request.taskContract?.let { contract ->
                appendLine()
                appendLine(contract.promptSection())
            }
            request.skills.promptSection().takeIf(String::isNotBlank)?.let { skillSection ->
                appendLine()
                appendLine(skillSection)
            }
            request.workspace?.let { workspace ->
                appendLine()
                appendLine(workspace.promptSection())
            }
            appendLine("STEP: ${request.step}/${request.maxSteps}")
            appendLine("PHYSICAL_SCREEN: ${request.screenWidth}x${request.screenHeight}")
            appendLine("FOREGROUND_PACKAGE: ${request.observation.packageName}")
            request.agentHostPackage?.let { hostPackage ->
                appendLine("AGENT_HOST_PACKAGE: $hostPackage")
                if (request.observation.packageName == hostPackage) {
                    appendLine(
                        "The foreground screen belongs to the agent host. Its progress text, " +
                            "tool traces, and generation status are instrumentation, not " +
                            "external-app loading or goal progress. Never wait for those host " +
                            "labels. Launch the goal's target app, or finish only if the goal " +
                            "is already satisfied without device interaction.",
                    )
                }
            }
            appendLine("RECENT_ACTIONS: ${JSONArray(request.recentActions)}")
            appendLine("VISUAL_ATTACHED: ${request.screenshot != null}")
            if (fusedPayload) {
                appendLine("SCREEN_ELEMENTS: ${JSONArray(fusedElements)}")
            } else {
                appendLine("ACCESSIBILITY_NODES: ${JSONArray(legacyNodes)}")
            }
        }
    }

    private fun UiNode.plannerPriority(): Int {
        val label = listOfNotNull(text, contentDescription, viewId)
            .joinToString(" ")
            .lowercase()
        var score = 0
        if (editable) score += 1_000
        if (SEARCH_TERMS.any(label::contains)) score += 900
        if (scrollable) score += 500
        if (clickable) score += 200
        if (focused) score += 100
        if (!text.isNullOrBlank() || !contentDescription.isNullOrBlank()) score += 50
        return score
    }

    private fun ScreenElement.plannerPriority(): Int {
        val label = listOfNotNull(text, contentDescription, viewId)
            .joinToString(" ")
            .lowercase()
        var score = 0
        if (editable) score += 1_000
        if (SEARCH_TERMS.any(label::contains)) score += 900
        if (CLOSE_TERMS.any(label::contains)) score += 800
        if (scrollable) score += 500
        if (clickable || nodeId != null) score += 200
        if (!text.isNullOrBlank() || !contentDescription.isNullOrBlank()) score += 50
        if (ScreenElementSource.OCR in sources) score += 25
        if (ScreenElementSource.ICON in sources) score += 75
        return score
    }

    private fun JSONObject.optionalDouble(name: String): Double? =
        takeIf { has(name) && !isNull(name) }?.optDouble(name)?.takeIf(Double::isFinite)

    private fun JSONObject.optionalLong(name: String): Long? =
        takeIf { has(name) && !isNull(name) }?.optLong(name)

    private fun JSONObject?.optionalInt(name: String): Int? =
        this?.takeIf { has(name) && !isNull(name) }?.optInt(name)

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private data class MeasuredJsonResponse(
        val responseText: String,
        val requestBytes: Int,
        val promptTokenCount: Int?,
        val candidatesTokenCount: Int?,
        val totalTokenCount: Int?,
    )

    private companion object {
        const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_OUTPUT_TOKENS = 512
        const val GOAL_SPEC_MAX_OUTPUT_TOKENS = 1_024
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_UI_NODES = 36
        const val MAX_SCREEN_ELEMENTS = 64
        val SEARCH_TERMS = listOf(
            "search",
            "검색",
            "query",
        )
        val CLOSE_TERMS = listOf(
            "닫기",
            "close",
            "그만 보기",
            "보지 않기",
        )
    }
}
