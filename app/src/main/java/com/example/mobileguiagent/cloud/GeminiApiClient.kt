package com.example.mobileguiagent.cloud

import android.util.Base64
import android.os.SystemClock
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.credentials.PublicCredentialDescriptor
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.model.isMeaningfulForAgent
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
    val x: Double? = null,
    val y: Double? = null,
    val endX: Double? = null,
    val endY: Double? = null,
    val nodeId: String? = null,
    val elementId: String? = null,
    val text: String? = null,
    val durationMs: Long? = null,
    val message: String? = null,
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
)

data class GeminiMeasuredDecision(
    val action: GeminiPlannerAction,
    val latencyMs: Long,
    val requestBytes: Int,
    val promptTokenCount: Int?,
    val candidatesTokenCount: Int?,
    val totalTokenCount: Int?,
)

class GeminiApiClient {
    /**
     * Cloud preflight that chooses opaque references only. The returned IDs
     * are intersected with the exact offered allowlist before local use.
     */
    suspend fun selectCredentialResources(
        apiKey: String,
        model: GeminiModel,
        goal: String,
        resources: List<PublicCredentialDescriptor>,
    ): Set<String> = withContext(Dispatchers.IO) {
        if (resources.isEmpty()) return@withContext emptySet()
        require(apiKey.isNotBlank())
        val offeredIds = resources.map(PublicCredentialDescriptor::id).toSet()
        val connection = (
            URL("$API_BASE/models/${model.apiId}:generateContent")
                .openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            val body = buildCredentialSelectionRequest(
                goal = goal,
                resources = resources,
            )
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            coroutineContext.ensureActive()
            val status = connection.responseCode
            val responseText = (
                if (status in 200..299) connection.inputStream else connection.errorStream
                )?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                error("Gemini credential selector HTTP $status")
            }
            parseCredentialSelection(responseText)
                .filterTo(linkedSetOf()) { it in offeredIds }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun decide(
        apiKey: String,
        model: GeminiModel,
        request: GeminiPlannerRequest,
    ): GeminiPlannerAction = decideMeasured(apiKey, model, request).action

    suspend fun decideMeasured(
        apiKey: String,
        model: GeminiModel,
        request: GeminiPlannerRequest,
    ): GeminiMeasuredDecision = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) {
            "GEMINI_API_KEY가 설정되지 않았습니다. local.properties에 값을 추가하세요."
        }
        val started = SystemClock.elapsedRealtime()
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
            val requestBody = buildRequestBody(request)
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
                error("Gemini API 오류 HTTP $status: ${apiMessage ?: responseText.take(300)}")
            }
            val response = JSONObject(responseText)
            val usage = response.optJSONObject("usageMetadata")
            GeminiMeasuredDecision(
                action = parseResponse(responseText),
                latencyMs = SystemClock.elapsedRealtime() - started,
                requestBytes = requestBytes.size,
                promptTokenCount = usage.optionalInt("promptTokenCount"),
                candidatesTokenCount = usage.optionalInt("candidatesTokenCount"),
                totalTokenCount = usage.optionalInt("totalTokenCount"),
            )
        } finally {
            connection.disconnect()
        }
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
                    .put("responseJsonSchema", responseSchema())
                    .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    .put(
                        "thinkingConfig",
                        JSONObject().put("thinkingLevel", "minimal"),
                    ),
            )
    }

    internal fun buildCredentialSelectionRequest(
        goal: String,
        resources: List<PublicCredentialDescriptor>,
    ): JSONObject {
        val prompt = buildString {
            appendLine("Select only the local resource IDs that may be needed for this goal.")
            appendLine("You are selecting opaque references, not reading their values.")
            appendLine("Return an empty list when the goal does not require a listed resource.")
            appendLine("Never invent an ID.")
            appendLine("GOAL: $goal")
            appendLine(
                "AVAILABLE_RESOURCES: " +
                    JSONArray(resources.map(PublicCredentialDescriptor::toPlannerJson)),
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
                            JSONArray().put(JSONObject().put("text", prompt)),
                        ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put(
                        "responseJsonSchema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(
                                    "resource_ids",
                                    JSONObject()
                                        .put("type", "array")
                                        .put(
                                            "items",
                                            JSONObject().put("type", "string"),
                                        ),
                                ),
                            )
                            .put("required", JSONArray().put("resource_ids"))
                            .put("additionalProperties", false),
                    )
                    .put("maxOutputTokens", 128)
                    .put(
                        "thinkingConfig",
                        JSONObject().put("thinkingLevel", "minimal"),
                    ),
            )
    }

    internal fun parseCredentialSelection(responseText: String): Set<String> {
        val response = JSONObject(responseText)
        val parts = response
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: error("Gemini resource selector 응답이 없습니다.")
        val text = buildString {
            for (index in 0 until parts.length()) {
                append(parts.optJSONObject(index)?.optString("text").orEmpty())
            }
        }.trim()
        val ids = JSONObject(text).getJSONArray("resource_ids")
        return buildSet {
            for (index in 0 until ids.length()) {
                ids.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    internal fun parseResponse(responseText: String): GeminiPlannerAction {
        val response = JSONObject(responseText)
        val parts = response
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: error("Gemini 응답에 action JSON이 없습니다.")
        val generatedText = buildString {
            for (index in 0 until parts.length()) {
                append(parts.optJSONObject(index)?.optString("text").orEmpty())
            }
        }.trim()
        if (generatedText.isBlank()) {
            error("Gemini가 빈 action을 반환했습니다.")
        }
        val action = JSONObject(generatedText)
        return GeminiPlannerAction(
            action = action.getString("action"),
            x = action.optionalDouble("x"),
            y = action.optionalDouble("y"),
            endX = action.optionalDouble("end_x"),
            endY = action.optionalDouble("end_y"),
            nodeId = action.optString("node_id").takeIf(String::isNotBlank),
            elementId = action.optString("element_id").takeIf(String::isNotBlank),
            text = action.optString("text").takeIf(String::isNotBlank),
            durationMs = action.optionalLong("duration_ms"),
            message = action.optString("message").takeIf(String::isNotBlank),
        )
    }

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
            appendLine("The runtime executes the action, captures a newer screen, and calls you again.")
            appendLine("Never invent package launch, shell, intent, MCP, or unavailable actions.")
            appendLine("Prefer tap_node when a current node clearly identifies the target.")
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
            appendLine("- tap: x and y")
            appendLine("- tap_element: element_id from the current SCREEN_ELEMENTS")
            appendLine("- tap_node: node_id from the current ACCESSIBILITY_NODES")
            appendLine("- swipe: x, y, end_x, and end_y (all four are mandatory)")
            appendLine("- type: text")
            appendLine("- submit: no arguments; invokes Search/Go/Done on the current field")
            appendLine("- wait: optional duration_ms")
            appendLine(
                "Keep swipe coordinates away from system edges: every coordinate should " +
                    "normally be between 50 and 950.",
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
            appendLine("Return finish_success only when this screenshot proves the whole goal complete.")
            appendLine()
            appendLine("GOAL: ${request.goal}")
            appendLine("STEP: ${request.step}/${request.maxSteps}")
            appendLine("PHYSICAL_SCREEN: ${request.screenWidth}x${request.screenHeight}")
            appendLine("FOREGROUND_PACKAGE: ${request.observation.packageName}")
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

    private fun responseSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "action",
                    JSONObject()
                        .put("type", "string")
                        .put(
                            "enum",
                            JSONArray(
                                listOf(
                                    "tap",
                                    "tap_element",
                                    "tap_node",
                                    "swipe",
                                    "type",
                                    "submit",
                                    "home",
                                    "back",
                                    "wait",
                                    "request_visual",
                                    "finish_success",
                                    "finish_failure",
                                ),
                            ),
                        ),
                )
                .put("x", normalizedCoordinateSchema())
                .put("y", normalizedCoordinateSchema())
                .put("end_x", normalizedCoordinateSchema())
                .put("end_y", normalizedCoordinateSchema())
                .put("node_id", JSONObject().put("type", "string"))
                .put("element_id", JSONObject().put("type", "string"))
                .put("text", JSONObject().put("type", "string"))
                .put(
                    "duration_ms",
                    JSONObject()
                        .put("type", "integer")
                        .put("minimum", 300)
                        .put("maximum", 5_000),
                )
                .put("message", JSONObject().put("type", "string")),
        )
        .put("required", JSONArray(listOf("action")))
        .put("additionalProperties", false)

    private fun normalizedCoordinateSchema(): JSONObject = JSONObject()
        .put("type", "number")
        .put("minimum", 0)
        .put("maximum", 1_000)

    private fun JSONObject.optionalDouble(name: String): Double? =
        takeIf { has(name) && !isNull(name) }?.optDouble(name)?.takeIf(Double::isFinite)

    private fun JSONObject.optionalLong(name: String): Long? =
        takeIf { has(name) && !isNull(name) }?.optLong(name)

    private fun JSONObject?.optionalInt(name: String): Int? =
        this?.takeIf { has(name) && !isNull(name) }?.optInt(name)

    private companion object {
        const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_OUTPUT_TOKENS = 256
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
