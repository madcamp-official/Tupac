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
import java.util.LinkedHashMap

/**
 * Bounded snapshot registry. A run binds node actions to the exact observation
 * fingerprint instead of trusting a process-global "latest" pointer that MCP,
 * remote control, or another invocation may overwrite concurrently.
 *
 * Node-targeted tools never fall back to a latest pointer: callers must present
 * the exact snapshot id they observed.
 */
internal object UiObservationStore {
    private const val MAX_SNAPSHOTS = 32
    private val snapshots = object : LinkedHashMap<String, UiSnapshot>(MAX_SNAPSHOTS, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, UiSnapshot>?,
        ): Boolean = size > MAX_SNAPSHOTS
    }

    @Synchronized
    fun remember(snapshot: UiSnapshot): String {
        val id = snapshot.fingerprint.hash
        snapshots[id] = snapshot
        return id
    }

    @Synchronized
    fun resolve(arguments: JSONObject): UiSnapshot? {
        val snapshotId = arguments.optString("snapshot_id").trim()
        return snapshotId.takeIf(String::isNotEmpty)?.let(snapshots::get)
    }
}

internal fun rememberUiObservation(snapshot: UiSnapshot): String =
    UiObservationStore.remember(snapshot)

/**
 * Prevents a value inserted by [FillSecretDeviceTool] from re-entering a
 * model prompt or trace through the next accessibility observation.
 *
 * The executor keeps the redacted caller-visible snapshot in the bounded
 * observation store so its fingerprint and node ids remain usable by exact
 * follow-up actions without retaining the inserted value.
 */
internal object SensitiveUiRedaction {
    private const val REDACTED_VALUE = "[LOCAL_VALUE_REDACTED]"
    private const val REDACTION_TTL_MS = 5 * 60 * 1_000L

    @Volatile
    private var markers: List<Marker> = emptyList()

    @Synchronized
    fun markFilledField(packageName: String, node: com.example.mobileguiagent.model.UiNode) {
        val now = System.currentTimeMillis()
        val next = Marker(
            packageName = packageName,
            nodeId = node.id,
            viewId = node.viewId.orEmpty(),
            bounds = android.graphics.Rect(node.bounds),
            expiresAtMillis = now + REDACTION_TTL_MS,
        )
        markers = (
            markers.filter { current ->
                current.expiresAtMillis >= now &&
                    current.packageName == packageName &&
                    !current.sameField(next)
            } + next
            )
    }

    @Synchronized
    fun redact(snapshot: UiSnapshot): UiSnapshot {
        val now = System.currentTimeMillis()
        val active = markers.filter { current ->
            current.expiresAtMillis >= now &&
                current.packageName == snapshot.packageName
        }
        markers = active
        if (active.isEmpty()) return snapshot
        return snapshot.copy(
            nodes = snapshot.nodes.map { node ->
                if (active.any { current -> current.matches(node) }) {
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
        fun sameField(other: Marker): Boolean =
            packageName == other.packageName &&
                (
                    (
                        viewId.isNotBlank() &&
                            other.viewId.isNotBlank() &&
                            viewId == other.viewId
                        ) ||
                        (nodeId == other.nodeId && bounds == other.bounds)
                    )

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
                .put("snapshot_id", snapshotIdSchema())
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
            required = listOf("snapshot_id", "node_id", "secret_ref"),
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
        val snapshot = UiObservationStore.resolve(arguments)
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
                        current.packageName != snapshot.packageName
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
                        // A WebView may refresh unrelated nodes between the
                        // observation and credential dispatch. The native
                        // setter revalidates the exact traversal id, package,
                        // bounds, class, view id, editability, and password
                        // role, so a changed full-screen fingerprint alone is
                        // not grounds to reject the credential. If that exact
                        // field no longer exists, require a fresh observation.
                        if (
                            !success.get() &&
                            current.fingerprint.hash != snapshot.fingerprint.hash
                        ) {
                            staleSnapshot.set(true)
                        }
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
        val safeSnapshot = SensitiveUiRedaction.redact(snapshot)
        // Redaction changes the snapshot fingerprint. Resolve subsequent
        // node-targeted calls against the same safe snapshot id returned to
        // the caller, not against a hidden raw variant.
        rememberUiObservation(safeSnapshot)
        return DeviceToolResult.UiObservation(safeSnapshot)
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
                .put("snapshot_id", snapshotIdSchema())
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
            required = listOf("snapshot_id", "node_id"),
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
        val snapshot = UiObservationStore.resolve(arguments)
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
            val nodeAction = service.clickSnapshotNode(target, snapshot.packageName)
            if (nodeAction.success && !coordinateFallback) {
                if (treeChanged) method.set("node_click_after_refresh")
                success.set(true)
                latch.countDown()
                return@post
            }

            // A label-less/custom view may reject ACTION_CLICK even though it
            // remains the same visible control. Dynamic banners can change the
            // full fingerprint, so validate the target itself against the
            // refreshed tree instead of rejecting every coordinate fallback.
            val coordinateTarget = if (!treeChanged) {
                target
            } else {
                current.nodes
                    .asSequence()
                    .filter { candidate ->
                        candidate.visibleToUser &&
                            candidate.enabled &&
                            candidate.className == target.className &&
                            target.viewId?.takeIf(String::isNotBlank)?.let { viewId ->
                                candidate.viewId == viewId
                            } == true
                    }
                    .minByOrNull { candidate ->
                        val dx = candidate.bounds.exactCenterX() - target.bounds.exactCenterX()
                        val dy = candidate.bounds.exactCenterY() - target.bounds.exactCenterY()
                        dx * dx + dy * dy
                    }
                    ?.takeIf { candidate ->
                        val centerDx = kotlin.math.abs(
                            candidate.bounds.exactCenterX() - target.bounds.exactCenterX(),
                        )
                        val centerDy = kotlin.math.abs(
                            candidate.bounds.exactCenterY() - target.bounds.exactCenterY(),
                        )
                        centerDx <= TARGET_REFRESH_MAX_CENTER_DELTA_PX &&
                            centerDy <= TARGET_REFRESH_MAX_CENTER_DELTA_PX
                    }
            }
            if (
                coordinateTarget == null ||
                coordinateTarget.bounds.width() <= 0 ||
                coordinateTarget.bounds.height() <= 0
            ) {
                latch.countDown()
                return@post
            }
            val performCoordinateTap = {
                service.tap(
                    coordinateTarget.bounds.exactCenterX(),
                    coordinateTarget.bounds.exactCenterY(),
                ) { tapped ->
                    success.set(tapped)
                    if (tapped) {
                        method.set(
                            if (treeChanged) {
                                if (nodeAction.success) {
                                    "node_click_then_verified_coordinate_retry_after_refresh"
                                } else {
                                    "verified_node_coordinate_retry_after_refresh"
                                }
                            } else if (coordinateFallback && nodeAction.success) {
                                "node_click_then_verified_coordinate_retry"
                            } else if (coordinateFallback) {
                                "verified_node_coordinate_retry"
                            } else {
                                "coordinate_tap"
                            },
                        )
                    }
                    latch.countDown()
                }
            }
            if (nodeAction.success && coordinateFallback) {
                // ACTION_CLICK can either activate a WebView control or merely
                // focus it. Retrying the coordinate immediately is unsafe for
                // toggle-like controls such as cinema seats: a real first
                // click selects the seat and the unconditional second click
                // deselects it. Give the DOM one frame to expose its state
                // change and retry only when the screen is still unchanged.
                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        val afterNodeAction = service.captureSnapshot()
                        if (
                            afterNodeAction != null &&
                            (
                                afterNodeAction.packageName != current.packageName ||
                                    afterNodeAction.fingerprint.hash != current.fingerprint.hash
                                )
                        ) {
                            success.set(true)
                            method.set("node_click_verified_change")
                            latch.countDown()
                        } else {
                            performCoordinateTap()
                        }
                    },
                    NODE_ACTION_VERIFY_DELAY_MS,
                )
            } else {
                performCoordinateTap()
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

/**
 * Opens one semantic selector and chooses an exact visible option.
 *
 * WebView select controls are often exposed as a clickable prompt instead of
 * Android's Spinner class. The selector check therefore accepts explicit
 * dropdown roles as well as visible "option/select" prompts, while still
 * rejecting ordinary editable text fields.
 */
object SelectOptionDeviceTool : DeviceTool {
    const val NAME = "select_option"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Opens one visible selector and chooses an exact option label. " +
            "Use this for sizes, colors, dropdowns, radio-style option sheets, and spinners.",
        inputSchema = objectSchema(
            properties = JSONObject()
                .put("snapshot_id", snapshotIdSchema())
                .put("node_id", JSONObject().put("type", "string"))
                .put("option", JSONObject().put("type", "string")),
            required = listOf("snapshot_id", "node_id", "option"),
        ),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val nodeId = arguments.optString("node_id").trim()
        val option = arguments.optString("option").trim()
        if (nodeId.isEmpty() || option.isEmpty()) {
            return DeviceToolResult.Error(
                code = "MISSING_OPTION_ARGUMENT",
                message = "select_option에는 node_id와 option이 필요합니다.",
            )
        }
        val snapshot = UiObservationStore.resolve(arguments)
            ?: return DeviceToolResult.Error(
                code = "OBSERVE_UI_REQUIRED",
                message = "select_option 전에 observe_ui를 실행해야 합니다.",
            )
        val selector = snapshot.nodes.firstOrNull { it.id == nodeId }
            ?: return DeviceToolResult.Error(
                code = "NODE_NOT_FOUND",
                message = "최근 UI Tree에 $nodeId 노드가 없습니다.",
            )
        if (!selector.enabled || !selector.visibleToUser || !selector.isSemanticSelector()) {
            return DeviceToolResult.Error(
                code = "NOT_A_SELECTOR",
                message = "현재 보이는 옵션 선택 컨트롤만 사용할 수 있습니다.",
            )
        }
        val service = activeServiceOrError() ?: return accessibilityNotConnected()
        val opened = TapNodeDeviceTool.execute(
            JSONObject(arguments.toString()).put("coordinate_fallback", true),
        )
        if (opened is DeviceToolResult.Error) return opened
        if (opened is DeviceToolResult.Action && !opened.success) {
            return DeviceToolResult.Error(
                code = "OPEN_SELECTOR_FAILED",
                message = "옵션 선택 컨트롤을 열지 못했습니다.",
            )
        }

        val options = waitForExactVisibleOption(service, option)
            ?: return DeviceToolResult.Error(
                code = "OPTION_NOT_FOUND",
                message = "열린 선택 화면에서 정확한 옵션을 찾지 못했습니다: $option",
            )
        rememberUiObservation(options.first)
        val selected = TapNodeDeviceTool.execute(
            JSONObject()
                .put("snapshot_id", options.first.fingerprint.hash)
                .put("node_id", options.second.id)
                .put("coordinate_fallback", true),
        )
        if (selected is DeviceToolResult.Error) return selected
        if (selected is DeviceToolResult.Action && !selected.success) {
            return DeviceToolResult.Error(
                code = "SELECT_OPTION_FAILED",
                message = "옵션 $option 항목을 누르지 못했습니다.",
            )
        }
        return DeviceToolResult.Action(
            action = NAME,
            success = true,
            message = "요청한 옵션을 선택했습니다. value=$option",
        )
    }

    private fun waitForExactVisibleOption(
        service: AgentAccessibilityService,
        option: String,
    ): Pair<UiSnapshot, com.example.mobileguiagent.model.UiNode>? {
        repeat(OPTION_OBSERVE_ATTEMPTS) {
            Thread.sleep(OPTION_OBSERVE_INTERVAL_MS)
            val snapshot = captureSnapshotBlocking(service) ?: return@repeat
            val match = snapshot.nodes.firstOrNull { node ->
                node.visibleToUser &&
                    node.enabled &&
                    listOfNotNull(node.text, node.contentDescription, node.hint)
                        .any { it.trim().equals(option, ignoreCase = true) }
            }
            if (match != null) return snapshot to match
        }
        return null
    }

    private fun com.example.mobileguiagent.model.UiNode.isSemanticSelector(): Boolean {
        val widget = className.orEmpty().lowercase()
        val role = roleDescription.orEmpty().lowercase()
        val label = listOfNotNull(text, contentDescription, hint)
            .joinToString(" ")
            .lowercase()
        return widget.contains("spinner") ||
            widget.contains("autocompletetextview") ||
            (widget.contains("edittext") && clickable && !editable) ||
            role.contains("dropdown") ||
            role.contains("drop-down") ||
            role.contains("combo") ||
            role.contains("menu popup") ||
            label.contains("dropdown") ||
            label.contains("드롭다운") ||
            (clickable && (label.contains("옵션") || label.contains("선택")))
    }

    private const val OPTION_OBSERVE_ATTEMPTS = 8
    private const val OPTION_OBSERVE_INTERVAL_MS = 200L
}

object SetTextDeviceTool : DeviceTool {
    const val NAME = "set_text"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Replaces text in one exact editable node from an observation. " +
            "It never falls back to a different focused or first input field.",
        inputSchema = objectSchema(
            properties = JSONObject()
                .put("snapshot_id", snapshotIdSchema())
                .put(
                    "node_id",
                    JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "Exact editable node id from the same observation.",
                        ),
                )
                .put(
                    "text",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Exact text to enter, in the user's language."),
                ),
            required = listOf("snapshot_id", "node_id", "text"),
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
        val nodeId = arguments.optString("node_id").trim()
        if (!NODE_ID_PATTERN.matches(nodeId)) {
            return DeviceToolResult.Error(
                code = "INVALID_NODE_ID",
                message = "set_text에는 최신 관찰의 node_숫자 형식 node_id가 필요합니다.",
            )
        }
        val snapshot = UiObservationStore.resolve(arguments)
            ?: return DeviceToolResult.Error(
                code = "OBSERVE_UI_REQUIRED",
                message = "set_text 전에 observe_ui를 실행하고 snapshot_id를 전달해야 합니다.",
            )
        val target = snapshot.nodes.firstOrNull { node -> node.id == nodeId }
            ?: return DeviceToolResult.Error(
                code = "NODE_NOT_FOUND",
                message = "해당 snapshot에 요청한 입력 노드가 없습니다.",
            )
        if (!target.visibleToUser || !target.enabled || !target.editable) {
            return DeviceToolResult.Error(
                code = "NODE_NOT_EDITABLE",
                message = "현재 보이는 활성 입력 노드만 텍스트를 받을 수 있습니다.",
            )
        }
        val service = activeServiceOrError() ?: return accessibilityNotConnected()
        val success = AtomicBoolean(false)
        val staleSnapshot = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
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
                        text = text,
                    ),
                )
            }
            latch.countDown()
        }
        if (!latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return DeviceToolResult.Error(
                code = "ACTION_TIMEOUT",
                message = "set_text 실행 응답 시간이 초과됐습니다.",
            )
        }
        if (staleSnapshot.get()) {
            return DeviceToolResult.Error(
                code = "SCREEN_CHANGED",
                message = "관찰 후 화면이 바뀌어 텍스트 입력을 거부했습니다.",
            )
        }
        return DeviceToolResult.Action(
            action = NAME,
            success = success.get(),
            message = if (success.get()) {
                "입력창에 \"$text\"를 입력했습니다."
            } else {
                "$nodeId 입력 노드에 텍스트를 넣지 못했습니다."
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
            "latest screenshot or UI bounds. Origin (0,0) is the top-left of the screen. " +
            "When snapshot_id is supplied, the tap is rejected if that observed screen changed.",
        inputSchema = objectSchema(
            properties = JSONObject()
                .put("snapshot_id", snapshotIdSchema())
                .put("x", coordinateSchema("Horizontal screen coordinate in pixels."))
                .put("y", coordinateSchema("Vertical screen coordinate in pixels.")),
            required = listOf("x", "y"),
        ),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val x = arguments.requiredCoordinate("x") ?: return invalidCoordinate("x")
        val y = arguments.requiredCoordinate("y") ?: return invalidCoordinate("y")
        val expectedSnapshot = if (arguments.has("snapshot_id")) {
            UiObservationStore.resolve(arguments)
                ?: return DeviceToolResult.Error(
                    code = "OBSERVE_UI_REQUIRED",
                    message = "좌표 탭에 전달한 snapshot_id를 찾을 수 없습니다. 다시 관찰해 주세요.",
                )
        } else {
            null
        }
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
        if (expectedSnapshot != null) {
            return runSnapshotBoundBooleanAction(
                name = NAME,
                successMessage = "화면 좌표 ($x, $y)를 탭했습니다.",
                expectedSnapshot = expectedSnapshot,
                service = service,
            ) { complete ->
                service.tap(x, y, complete)
            }
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

/**
 * Validates and dispatches a coordinate gesture in one main-looper turn.
 *
 * Keeping the capture and gesture submission together closes the stale-action
 * window where a planner could observe a loading screen, spend seconds
 * deciding, and then tap the fully loaded replacement screen at the old
 * coordinate.
 */
private fun runSnapshotBoundBooleanAction(
    name: String,
    successMessage: String,
    expectedSnapshot: UiSnapshot,
    service: AgentAccessibilityService,
    startOnMainThread: ((Boolean) -> Unit) -> Unit,
): DeviceToolResult {
    val result = AtomicBoolean(false)
    val staleSnapshot = AtomicBoolean(false)
    val latch = CountDownLatch(1)
    Handler(Looper.getMainLooper()).post {
        runCatching {
            val current = service.captureSnapshot()
            if (!coordinateSnapshotIsFresh(expectedSnapshot, current)) {
                staleSnapshot.set(true)
                latch.countDown()
                return@post
            }
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
    if (staleSnapshot.get()) {
        return DeviceToolResult.Error(
            code = "SCREEN_CHANGED",
            message = "관찰 후 화면이 바뀌어 오래된 좌표 탭을 거부했습니다. 다시 관찰해 주세요.",
        )
    }
    val success = result.get()
    return DeviceToolResult.Action(
        action = name,
        success = success,
        message = if (success) successMessage else "$name 실행이 취소되거나 실패했습니다.",
    )
}

internal fun coordinateSnapshotIsFresh(
    expectedSnapshot: UiSnapshot,
    currentSnapshot: UiSnapshot?,
): Boolean {
    if (
        currentSnapshot == null ||
        currentSnapshot.packageName != expectedSnapshot.packageName
    ) {
        return false
    }
    if (currentSnapshot.fingerprint.hash == expectedSnapshot.fingerprint.hash) {
        return true
    }
    // observe_ui stores a caller-visible redacted snapshot after local secret
    // filling, while MCP/remote observers may store the raw snapshot. Accept
    // either exact representation of the same current screen.
    val safeCurrent = SensitiveUiRedaction.redact(currentSnapshot)
    return safeCurrent.fingerprint.hash == expectedSnapshot.fingerprint.hash
}

private fun activeServiceOrError(): AgentAccessibilityService? =
    AgentAccessibilityService.activeService

private fun captureSnapshotBlocking(
    service: AgentAccessibilityService,
): UiSnapshot? {
    val result = AtomicReference<UiSnapshot?>()
    val latch = CountDownLatch(1)
    Handler(Looper.getMainLooper()).post {
        result.set(service.captureSnapshot())
        latch.countDown()
    }
    return if (latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        result.get()
    } else {
        null
    }
}

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

internal fun snapshotIdSchema(): JSONObject = JSONObject()
    .put("type", "string")
    .put(
        "description",
        "Exact snapshot_id returned by the observation that supplied this node.",
    )

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
private const val NODE_ACTION_VERIFY_DELAY_MS = 200L
private const val TARGET_REFRESH_MAX_CENTER_DELTA_PX = 48f
private val NODE_ID_PATTERN = Regex("""^node_\d+$""")
