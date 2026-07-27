package com.example.mobileguiagent.device

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.credentials.LocalCredentialRepository
import com.example.mobileguiagent.credentials.SecretAccessResult
import com.example.mobileguiagent.model.UiSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object LatestUiObservation {
    @Volatile
    var snapshot: UiSnapshot? = null
}

/**
 * Prevents a value inserted by [FillSecretDeviceTool] from re-entering a
 * model prompt or trace through the next accessibility observation.
 *
 * The executor keeps the original snapshot in [LatestUiObservation] for exact
 * node actions and stale-screen checks. Only the observation returned to model
 * adapters is copied and redacted.
 */
internal object SensitiveUiRedaction {
    private const val REDACTED_VALUE = "[LOCAL_VALUE_REDACTED]"
    private const val REDACTION_TTL_MS = 5 * 60 * 1_000L

    @Volatile
    private var marker: Marker? = null

    fun markFilledField(packageName: String, node: com.example.mobileguiagent.model.UiNode) {
        marker = Marker(
            packageName = packageName,
            nodeId = node.id,
            viewId = node.viewId.orEmpty(),
            bounds = android.graphics.Rect(node.bounds),
            expiresAtMillis = System.currentTimeMillis() + REDACTION_TTL_MS,
        )
    }

    fun redact(snapshot: UiSnapshot): UiSnapshot {
        val current = marker ?: return snapshot
        if (
            current.expiresAtMillis < System.currentTimeMillis() ||
            current.packageName != snapshot.packageName
        ) {
            marker = null
            return snapshot
        }
        return snapshot.copy(
            nodes = snapshot.nodes.map { node ->
                if (current.matches(node)) {
                    node.copy(
                        text = REDACTED_VALUE,
                        contentDescription = node.contentDescription
                            ?.takeIf(String::isNotBlank)
                            ?.let { REDACTED_VALUE },
                    )
                } else {
                    node
                }
            },
        )
    }

    private data class Marker(
        val packageName: String,
        val nodeId: String,
        val viewId: String,
        val bounds: android.graphics.Rect,
        val expiresAtMillis: Long,
    ) {
        fun matches(node: com.example.mobileguiagent.model.UiNode): Boolean {
            if (!node.editable) return false
            if (viewId.isNotBlank() && node.viewId == viewId) return true
            return node.id == nodeId && android.graphics.Rect.intersects(bounds, node.bounds)
        }
    }
}

/**
 * Locally resolves an opaque reference and fills one exact observed node.
 *
 * The model never supplies plaintext. The broker validates the foreground
 * package, field type and one-time user grant before decrypting, and neither
 * this result nor logs include the secret value.
 */
object FillSecretDeviceTool : DeviceTool {
    const val NAME = "fill_secret"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Fills one exact editable node with a locally stored credential. " +
            "Requires an opaque secret_ref selected from AVAILABLE_LOCAL_RESOURCES and a " +
            "one-time user approval. Never accepts or returns the secret value.",
        inputSchema = objectSchema(
            properties = JSONObject()
                .put(
                    "node_id",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Exact editable node id from the latest observe_ui."),
                )
                .put(
                    "secret_ref",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Opaque approved local credential reference."),
                ),
            required = listOf("node_id", "secret_ref"),
        ),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val nodeId = arguments.optString("node_id").trim()
        val secretRef = arguments.optString("secret_ref").trim()
        if (nodeId.isEmpty() || secretRef.isEmpty()) {
            return DeviceToolResult.Error(
                code = "MISSING_SECRET_ARGUMENT",
                message = "fill_secret에는 node_id와 secret_ref가 필요합니다.",
            )
        }
        if (!NODE_ID_PATTERN.matches(nodeId)) {
            return DeviceToolResult.Error(
                code = "INVALID_NODE_ID",
                message = "node_id는 최신 observe_ui가 반환한 node_숫자 형식이어야 합니다.",
            )
        }
        val snapshot = LatestUiObservation.snapshot
            ?: return DeviceToolResult.Error(
                code = "OBSERVE_UI_REQUIRED",
                message = "fill_secret 전에 observe_ui를 실행해야 합니다.",
            )
        val target = snapshot.nodes.firstOrNull { it.id == nodeId }
            ?: return DeviceToolResult.Error(
                code = "NODE_NOT_FOUND",
                message = "최근 UI Tree에 요청한 입력 노드가 없습니다.",
            )
        if (!target.enabled || !target.visibleToUser || !target.editable) {
            return DeviceToolResult.Error(
                code = "NODE_NOT_EDITABLE",
                message = "현재 보이는 활성 입력란만 보안값을 받을 수 있습니다.",
            )
        }
        val service = activeServiceOrError() ?: return accessibilityNotConnected()
        val access = LocalCredentialRepository.accessSecret(
            context = service.applicationContext,
            id = secretRef,
            foregroundPackage = snapshot.packageName,
            targetIsPassword = target.password,
        )
        if (access is SecretAccessResult.Denied) {
            return DeviceToolResult.Error(access.code, access.message)
        }
        val characters = (access as SecretAccessResult.Ready).characters
        val success = AtomicBoolean(false)
        val staleSnapshot = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    val current = service.captureSnapshot()
                    if (
                        current == null ||
                        current.packageName != snapshot.packageName ||
                        current.fingerprint.hash != snapshot.fingerprint.hash
                    ) {
                        staleSnapshot.set(true)
                    } else {
                        success.set(
                            service.setTextOnSnapshotNode(
                                target = target,
                                expectedPackage = snapshot.packageName,
                                text = characters,
                            ),
                        )
                    }
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return DeviceToolResult.Error(
                    code = "ACTION_TIMEOUT",
                    message = "보안 입력 실행 응답 시간이 초과됐습니다.",
                )
            }
        } finally {
            characters.fill('\u0000')
        }
        if (staleSnapshot.get()) {
            return DeviceToolResult.Error(
                code = "SCREEN_CHANGED",
                message = "승인 후 화면이 바뀌어 보안 입력을 거부했습니다. 다시 관찰해 주세요.",
            )
        }
        if (success.get()) {
            SensitiveUiRedaction.markFilledField(snapshot.packageName, target)
        }
        return DeviceToolResult.Action(
            action = NAME,
            success = success.get(),
            message = if (success.get()) {
                "$secretRef 값을 승인된 입력란에 로컬로 주입했습니다."
            } else {
                "승인된 입력란에 보안값을 넣지 못했습니다."
            },
        )
    }
}

object ObserveUiDeviceTool : DeviceTool {
    const val NAME = "observe_ui"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Reads the current foreground package and visible Android UI-tree nodes. " +
            "Use this before tap_node or whenever the screen may have changed.",
        inputSchema = emptyObjectSchema(),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val service = activeServiceOrError() ?: return accessibilityNotConnected()
        val result = AtomicReference<UiSnapshot?>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(service.captureSnapshot())
            latch.countDown()
        }
        if (!latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return DeviceToolResult.Error(
                code = "OBSERVATION_TIMEOUT",
                message = "UI 화면 관찰 시간이 초과됐습니다.",
            )
        }
        val snapshot = result.get()
            ?: return DeviceToolResult.Error(
                code = "UI_TREE_UNAVAILABLE",
                message = "현재 화면의 UI Tree를 가져오지 못했습니다.",
            )
        LatestUiObservation.snapshot = snapshot
        return DeviceToolResult.UiObservation(SensitiveUiRedaction.redact(snapshot))
    }
}

object GoHomeDeviceTool : DeviceTool {
    const val NAME = "go_home"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Navigates to the Android home screen. Use it when the user asks to go home.",
        inputSchema = emptyObjectSchema(),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val service = activeServiceOrError() ?: return accessibilityNotConnected()
        return runBooleanAction(NAME, "홈 화면으로 이동했습니다.") { complete ->
            complete(service.goHome())
        }
    }
}

object GoBackDeviceTool : DeviceTool {
    const val NAME = "go_back"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Performs the Android system Back action once. Use it to dismiss the " +
            "current screen, dialog, or overlay when going back is the intended next step.",
        inputSchema = emptyObjectSchema(),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val service = activeServiceOrError() ?: return accessibilityNotConnected()
        return runBooleanAction(NAME, "Android 뒤로 가기를 실행했습니다.") { complete ->
            complete(
                service.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK,
                ),
            )
        }
    }
}

object WaitDeviceTool : DeviceTool {
    const val NAME = "wait"
    const val DEFAULT_DURATION_MS = 1_200L
    const val MIN_DURATION_MS = 300L
    const val MAX_DURATION_MS = 5_000L

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Waits briefly for the current UI to settle before it is observed again. " +
            "This tool does not interact with the screen.",
        inputSchema = objectSchema(
            properties = JSONObject().put(
                "duration_ms",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", MIN_DURATION_MS)
                    .put("maximum", MAX_DURATION_MS)
                    .put("default", DEFAULT_DURATION_MS)
                    .put(
                        "description",
                        "Wait duration in milliseconds. Defaults to $DEFAULT_DURATION_MS.",
                    ),
            ),
        ),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val durationMs = arguments.optionalInteger("duration_ms", DEFAULT_DURATION_MS)
            ?: return DeviceToolResult.Error(
                code = "INVALID_DURATION",
                message = "duration_ms는 $MIN_DURATION_MS~$MAX_DURATION_MS 범위의 정수여야 합니다.",
            )
        if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) {
            return DeviceToolResult.Error(
                code = "INVALID_DURATION",
                message = "duration_ms는 $MIN_DURATION_MS~$MAX_DURATION_MS 범위의 정수여야 합니다.",
            )
        }

        return try {
            // Device Tools are invoked from an IO worker by the local agent and
            // the MCP server. Waiting here never posts delayed work to Android's
            // main looper, so accessibility/UI callbacks remain responsive.
            CountDownLatch(1).await(durationMs, TimeUnit.MILLISECONDS)
            DeviceToolResult.Action(
                action = NAME,
                success = true,
                message = "${durationMs}ms 동안 화면이 안정되기를 기다렸습니다.",
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            DeviceToolResult.Error(
                code = "WAIT_INTERRUPTED",
                message = "화면 대기가 중단됐습니다.",
            )
        }
    }
}

object TapNodeDeviceTool : DeviceTool {
    const val NAME = "tap_node"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Clicks one node from the most recent observe_ui result. " +
            "Prefer this over coordinate tap when the target node is available.",
        inputSchema = objectSchema(
            properties = JSONObject()
                .put(
                    "node_id",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Exact node id returned by the latest observe_ui."),
                )
                .put(
                    "coordinate_fallback",
                    JSONObject()
                        .put("type", "boolean")
                        .put("default", false)
                        .put(
                            "description",
                            "Internally retry the unchanged exact node at its bounds center.",
                        ),
                ),
            required = listOf("node_id"),
        ),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val nodeId = arguments.optString("node_id").trim()
        val coordinateFallback = arguments.optBoolean("coordinate_fallback", false)
        if (nodeId.isEmpty()) {
            return DeviceToolResult.Error(
                code = "MISSING_NODE_ID",
                message = "tap_node에는 node_id가 필요합니다.",
            )
        }
        val snapshot = LatestUiObservation.snapshot
            ?: return DeviceToolResult.Error(
                code = "OBSERVE_UI_REQUIRED",
                message = "tap_node 전에 observe_ui를 실행해야 합니다.",
            )
        val target = snapshot.nodes.firstOrNull { node -> node.id == nodeId }
            ?: return DeviceToolResult.Error(
                code = "NODE_NOT_FOUND",
                message = "최근 UI Tree에 $nodeId 노드가 없습니다.",
            )
        if (!target.enabled || !target.visibleToUser) {
            return DeviceToolResult.Error(
                code = "NODE_NOT_ACTIONABLE",
                message = "현재 보이지 않거나 비활성인 노드는 누를 수 없습니다.",
            )
        }
        val service = activeServiceOrError() ?: return accessibilityNotConnected()
        val success = AtomicBoolean(false)
        val staleSnapshot = AtomicBoolean(false)
        val method = AtomicReference("node_click")
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            val current = service.captureSnapshot()
            if (
                current == null ||
                current.packageName != snapshot.packageName
            ) {
                staleSnapshot.set(true)
                latch.countDown()
                return@post
            }

            val treeChanged =
                current.fingerprint.hash != snapshot.fingerprint.hash
            // Dynamic banners and carousels can change the full fingerprint
            // while the requested control remains stable. clickSnapshotNode
            // re-resolves the old target against the current native tree using
            // its exact label, view id, class, and nearby bounds.
            if (!coordinateFallback) {
                val nodeAction = service.clickSnapshotNode(target, snapshot.packageName)
                if (nodeAction.success) {
                    if (treeChanged) method.set("node_click_after_refresh")
                    success.set(true)
                    latch.countDown()
                    return@post
                }
            }

            // A label-less/custom view may be present in the accessibility
            // snapshot but reject ACTION_CLICK. Coordinate fallback is safe
            // only when the complete screen still matches the observation.
            if (
                treeChanged ||
                target.bounds.width() <= 0 ||
                target.bounds.height() <= 0
            ) {
                latch.countDown()
                return@post
            }
            service.tap(
                target.bounds.exactCenterX(),
                target.bounds.exactCenterY(),
            ) { tapped ->
                success.set(tapped)
                if (tapped) {
                    method.set(
                        if (coordinateFallback) {
                            "verified_node_coordinate_retry"
                        } else {
                            "coordinate_tap"
                        },
                    )
                }
                latch.countDown()
            }
        }
        if (!latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return DeviceToolResult.Error(
                code = "ACTION_TIMEOUT",
                message = "tap_node 실행 응답 시간이 초과됐습니다.",
            )
        }
        if (staleSnapshot.get()) {
            return DeviceToolResult.Error(
                code = "SCREEN_CHANGED",
                message = "관찰 후 화면이 바뀌어 오래된 노드 클릭을 거부했습니다.",
            )
        }
        val clicked = success.get()
        val actionMethod = method.get()
        return DeviceToolResult.Action(
            action = NAME,
            success = clicked,
            message = if (clicked) {
                "${target.text ?: target.contentDescription ?: nodeId} 노드를 눌렀습니다. " +
                    "method=$actionMethod"
            } else {
                "$nodeId 노드를 누르지 못했습니다. method=$actionMethod"
            },
        )
    }
}

object SetTextDeviceTool : DeviceTool {
    const val NAME = "set_text"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Replaces the text in the currently visible editable field. " +
            "Use it after opening an app-drawer search field or another text input.",
        inputSchema = objectSchema(
            properties = JSONObject().put(
                "text",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Exact text to enter, in the user's language."),
            ),
            required = listOf("text"),
        ),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val text = arguments.optString("text").trim()
        if (text.isEmpty()) {
            return DeviceToolResult.Error(
                code = "MISSING_TEXT",
                message = "set_text에는 입력할 text가 필요합니다.",
            )
        }
        val service = activeServiceOrError() ?: return accessibilityNotConnected()
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            success.set(service.setTextOnFirstEditable(text))
            latch.countDown()
        }
        if (!latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return DeviceToolResult.Error(
                code = "ACTION_TIMEOUT",
                message = "set_text 실행 응답 시간이 초과됐습니다.",
            )
        }
        return DeviceToolResult.Action(
            action = NAME,
            success = success.get(),
            message = if (success.get()) {
                "입력창에 \"$text\"를 입력했습니다."
            } else {
                "현재 화면에서 입력 가능한 검색창을 찾지 못했습니다."
            },
        )
    }
}

object SubmitTextDeviceTool : DeviceTool {
    const val NAME = "submit_text"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Submits the current editable field using its Android IME action " +
            "(Search, Go, or Done). Use this after set_text instead of guessing an " +
            "unlabeled icon next to the field.",
        inputSchema = emptyObjectSchema(),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val service = activeServiceOrError() ?: return accessibilityNotConnected()
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            success.set(service.submitFirstEditable())
            latch.countDown()
        }
        if (!latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return DeviceToolResult.Error(
                code = "ACTION_TIMEOUT",
                message = "submit_text 실행 응답 시간이 초과됐습니다.",
            )
        }
        return DeviceToolResult.Action(
            action = NAME,
            success = success.get(),
            message = if (success.get()) {
                "현재 입력창의 검색/완료 동작을 실행했습니다."
            } else {
                "IME 제출 동작을 지원하는 입력창을 찾지 못했습니다."
            },
        )
    }
}

object TapDeviceTool : DeviceTool {
    const val NAME = "tap"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Taps one absolute screen coordinate. Coordinates must come from the " +
            "latest screenshot or UI bounds. Origin (0,0) is the top-left of the screen.",
        inputSchema = objectSchema(
            properties = JSONObject()
                .put("x", coordinateSchema("Horizontal screen coordinate in pixels."))
                .put("y", coordinateSchema("Vertical screen coordinate in pixels.")),
            required = listOf("x", "y"),
        ),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val x = arguments.requiredCoordinate("x") ?: return invalidCoordinate("x")
        val y = arguments.requiredCoordinate("y") ?: return invalidCoordinate("y")
        val service = activeServiceOrError() ?: return accessibilityNotConnected()
        val display = service.resources.displayMetrics
        if (
            x >= display.widthPixels ||
            y >= display.heightPixels
        ) {
            return DeviceToolResult.Error(
                code = "COORDINATE_OUT_OF_BOUNDS",
                message = "탭 좌표가 현재 화면 범위를 벗어났습니다: ($x, $y), " +
                    "screen=${display.widthPixels}x${display.heightPixels}",
            )
        }
        return runBooleanAction(NAME, "화면 좌표 ($x, $y)를 탭했습니다.") { complete ->
            service.tap(x, y, complete)
        }
    }
}

object SwipeDeviceTool : DeviceTool {
    const val NAME = "swipe"
    private const val DEFAULT_DURATION_MS = 400L
    private const val MIN_DURATION_MS = 100L
    private const val MAX_DURATION_MS = 2_000L

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Swipes from one absolute screen coordinate to another. Use it to " +
            "scroll or slide content. Origin (0,0) is the top-left of the screen.",
        inputSchema = objectSchema(
            properties = JSONObject()
                .put("start_x", coordinateSchema("Swipe start X coordinate in pixels."))
                .put("start_y", coordinateSchema("Swipe start Y coordinate in pixels."))
                .put("end_x", coordinateSchema("Swipe end X coordinate in pixels."))
                .put("end_y", coordinateSchema("Swipe end Y coordinate in pixels."))
                .put(
                    "duration_ms",
                    JSONObject()
                        .put("type", "integer")
                        .put("minimum", MIN_DURATION_MS)
                        .put("maximum", MAX_DURATION_MS)
                        .put("default", DEFAULT_DURATION_MS),
                ),
            required = listOf("start_x", "start_y", "end_x", "end_y"),
        ),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val startX =
            arguments.requiredCoordinate("start_x") ?: return invalidCoordinate("start_x")
        val startY =
            arguments.requiredCoordinate("start_y") ?: return invalidCoordinate("start_y")
        val endX = arguments.requiredCoordinate("end_x") ?: return invalidCoordinate("end_x")
        val endY = arguments.requiredCoordinate("end_y") ?: return invalidCoordinate("end_y")
        val durationMs = arguments.optLong("duration_ms", DEFAULT_DURATION_MS)
        if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) {
            return DeviceToolResult.Error(
                code = "INVALID_DURATION",
                message = "duration_ms는 $MIN_DURATION_MS~$MAX_DURATION_MS 범위여야 합니다.",
            )
        }
        val service = activeServiceOrError() ?: return accessibilityNotConnected()
        val display = service.resources.displayMetrics
        if (
            startX >= display.widthPixels ||
            endX >= display.widthPixels ||
            startY >= display.heightPixels ||
            endY >= display.heightPixels
        ) {
            return DeviceToolResult.Error(
                code = "COORDINATE_OUT_OF_BOUNDS",
                message = "스와이프 좌표가 현재 화면 범위를 벗어났습니다. " +
                    "screen=${display.widthPixels}x${display.heightPixels}",
            )
        }
        return runBooleanAction(NAME, "화면을 스와이프했습니다.") { complete ->
            service.swipe(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                durationMs = durationMs,
                onComplete = complete,
            )
        }
    }
}

object FinishDeviceTool : DeviceTool {
    const val NAME = "finish"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Ends the agent loop only when the user's goal is complete or cannot be " +
            "completed safely. Include a short user-facing message.",
        inputSchema = objectSchema(
            properties = JSONObject().put(
                "message",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Concise result message in the user's language."),
            ),
            required = listOf("message"),
        ),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult =
        DeviceToolResult.Action(
            action = NAME,
            success = true,
            message = arguments.optString("message")
                .trim()
                .ifBlank { "작업을 마쳤습니다." },
        )
}

private fun runBooleanAction(
    name: String,
    successMessage: String,
    startOnMainThread: ((Boolean) -> Unit) -> Unit,
): DeviceToolResult {
    val result = AtomicBoolean(false)
    val latch = CountDownLatch(1)
    Handler(Looper.getMainLooper()).post {
        runCatching {
            startOnMainThread { success ->
                result.set(success)
                latch.countDown()
            }
        }.onFailure {
            latch.countDown()
        }
    }
    if (!latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        return DeviceToolResult.Error(
            code = "ACTION_TIMEOUT",
            message = "$name 실행 응답 시간이 초과됐습니다.",
        )
    }
    val success = result.get()
    return DeviceToolResult.Action(
        action = name,
        success = success,
        message = if (success) successMessage else "$name 실행이 취소되거나 실패했습니다.",
    )
}

private fun activeServiceOrError(): AgentAccessibilityService? =
    AgentAccessibilityService.activeService

private fun accessibilityNotConnected() = DeviceToolResult.Error(
    code = "ACCESSIBILITY_NOT_CONNECTED",
    message = "접근성 서비스가 연결되지 않았습니다.",
)

private fun invalidCoordinate(name: String) = DeviceToolResult.Error(
    code = "INVALID_COORDINATE",
    message = "$name 좌표는 0 이상의 유한한 숫자여야 합니다.",
)

private fun JSONObject.requiredCoordinate(name: String): Float? {
    if (!has(name)) return null
    val value = optDouble(name, Double.NaN)
    return value
        .takeIf { it.isFinite() && it >= 0.0 && it <= MAX_COORDINATE }
        ?.toFloat()
}

private fun JSONObject.optionalInteger(name: String, defaultValue: Long): Long? {
    if (!has(name)) return defaultValue
    return when (val value = opt(name)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
}

private fun coordinateSchema(description: String): JSONObject = JSONObject()
    .put("type", "number")
    .put("minimum", 0)
    .put("maximum", MAX_COORDINATE)
    .put("description", description)

private fun emptyObjectSchema(): JSONObject = objectSchema(JSONObject())

private fun objectSchema(
    properties: JSONObject,
    required: List<String> = emptyList(),
): JSONObject = JSONObject()
    .put("type", "object")
    .put("properties", properties)
    .put("additionalProperties", false)
    .apply {
        if (required.isNotEmpty()) {
            put("required", JSONArray(required))
        }
    }

private const val MAX_COORDINATE = 10_000.0
private const val ACTION_TIMEOUT_MS = 8_000L
private val NODE_ID_PATTERN = Regex("""^node_\d+$""")
