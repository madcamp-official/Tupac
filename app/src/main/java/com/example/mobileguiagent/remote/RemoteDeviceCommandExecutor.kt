package com.example.mobileguiagent.remote

import android.os.Handler
import android.os.Looper
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.rememberUiObservation
import com.example.mobileguiagent.mcp.McpDeviceToolAdapter
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.model.isMeaningfulForAgent
import com.example.mobileguiagent.secret.SecretVault
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RemoteDeviceCommandExecutor {
    private val adapter = McpDeviceToolAdapter(DeviceToolRegistry())
    private val lastObservedSnapshot = AtomicReference<UiSnapshot?>()

    fun execute(command: JSONObject): JSONObject {
        val commandId = command.optString("id")
        val toolName = command.optString("toolName")
        val arguments = command.optJSONObject("arguments") ?: JSONObject()
        val grantedScopes = command.optJSONArray("grantedScopes")
            ?.strings()
            ?.toSet()
            ?: emptySet()
        val expiresAt = runCatching {
            java.time.Instant.parse(command.optString("expiresAt")).toEpochMilli()
        }.getOrNull()
            ?: return failure(commandId, "INVALID_EXPIRY", "명령 만료 시간이 올바르지 않습니다.")
        if (System.currentTimeMillis() >= expiresAt) {
            return failure(commandId, "COMMAND_EXPIRED", "이미 만료된 명령입니다.", status = "expired")
        }

        val spec = RemoteToolCatalog.find(toolName)
            ?: return failure(
                commandId,
                "REMOTE_TOOL_NOT_ALLOWED",
                "원격 실행이 허용되지 않은 도구입니다.",
                status = "denied",
            )
        val current = if (spec.needsCurrentSnapshot) captureSnapshot() else null
        // Register the exact snapshot under its fingerprint. Snapshot-targeted
        // core tools still require the caller-provided snapshot_id; there is
        // no implicit "latest observation" fallback.
        current?.let(::rememberUiObservation)
        val credentialOwner = if (
            toolName == "device_fill_field" ||
            toolName == "device_click_node"
        ) {
            credentialOwner(current)
        } else {
            null
        }
        val credentialField = arguments.optString("field")
        val loginSubmissionAuthorized =
            toolName == "device_click_node" &&
                isLoginSubmissionRequest(arguments) &&
                credentialOwner != null &&
                hasStoredLoginCredential(credentialOwner)
        val decision = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = toolName,
                arguments = arguments,
                grantedScopes = grantedScopes,
                currentSnapshot = current,
                observedSnapshot = lastObservedSnapshot.get(),
                locallyConfirmed = when (toolName) {
                    "device_fill_field" ->
                        credentialOwner != null &&
                            hasStoredAccountField(credentialOwner, credentialField)
                    "device_click_node" -> loginSubmissionAuthorized
                    else -> false
                },
            ),
        )
        if (decision is RemotePolicyDecision.Deny) {
            return failure(
                commandId,
                decision.code,
                decision.message,
                status = "denied",
            )
        }

        val result = when (toolName) {
            "device_status" -> deviceStatus()
            "device_observe" -> {
                val snapshot = current
                    ?: return failure(commandId, "NO_ACTIVE_WINDOW", "현재 화면을 읽을 수 없습니다.")
                lastObservedSnapshot.set(snapshot)
                rememberUiObservation(snapshot)
                val outgoing =
                    if (decision is RemotePolicyDecision.AllowRedacted) {
                        RemoteObservationRedactor.redact(snapshot)
                    } else {
                        snapshot
                    }
                observation(
                    outgoing,
                    arguments.optInt("max_nodes", DEFAULT_MAX_NODES)
                        .coerceIn(1, MAX_NODES),
                    authorizationSnapshotId = snapshot.fingerprint.hash,
                )
            }
            "device_type_node" -> typeNode(arguments)
            "device_fill_field" -> fillField(arguments, current, credentialOwner)
            "device_find_node" -> findNode(arguments, current)
            "device_set_checked" -> setChecked(arguments, current)
            "device_select_option" -> selectOption(arguments, current)
            "device_launch_app",
            "device_open_uri",
            "device_scroll",
            "device_submit_text",
            "device_home",
            "device_back",
            -> adapter.call(toolName, arguments)
            "device_click_node" -> clickNode(arguments, current)
            else -> return failure(
                commandId,
                "POC_TOOL_NOT_ENABLED",
                "이 단계에서는 아직 활성화되지 않은 도구입니다.",
                status = "denied",
            )
        }
        if (result.optBoolean("isError")) {
            val details = toolErrorDetails(result)
            return failure(commandId, details.first, details.second)
        }
        return JSONObject()
            .put("type", "result")
            .put("commandId", commandId)
            .put("status", "succeeded")
            .put("result", result)
    }

    private fun clickNode(arguments: JSONObject, current: UiSnapshot?): JSONObject {
        val snapshot = current
            ?: return toolError("NO_ACTIVE_WINDOW", "현재 화면을 읽을 수 없습니다.")
        val target = snapshot.nodes.firstOrNull { it.id == arguments.optString("node_id") }
            ?: return toolError("NODE_NOT_FOUND", "snapshot에 해당 node_id가 없습니다.")
        val forwarded = JSONObject(arguments.toString())
        if (isWebViewButton(target, snapshot)) {
            // WebView buttons can return ACTION_CLICK=true after merely taking
            // accessibility focus. A verified coordinate tap activates the
            // actual DOM click target; Megabox seat buttons exhibit this.
            forwarded.put("coordinate_fallback", true)
        }
        return adapter.call("device_click_node", forwarded)
    }

    private fun isWebViewButton(target: UiNode, snapshot: UiSnapshot): Boolean {
        if (target.className != "android.widget.Button" || !target.clickable) return false
        val byId = snapshot.nodes.associateBy(UiNode::id)
        var parentId = target.parentId
        while (parentId != null) {
            val parent = byId[parentId] ?: return false
            if (parent.className == "android.webkit.WebView") return true
            parentId = parent.parentId
        }
        return false
    }

    private fun findNode(arguments: JSONObject, initial: UiSnapshot?): JSONObject {
        val query = arguments.optString("text").trim()
        if (query.isEmpty() || query.length > 200) {
            return toolError("INVALID_QUERY", "text는 1~200자의 문자열이어야 합니다.")
        }
        val shouldScroll = arguments.optBoolean("scroll", false)
        val maxScrolls = arguments.optInt("max_scrolls", 3).coerceIn(0, 5)
        var performed = 0
        var snapshot = initial ?: captureSnapshot()
            ?: return toolError("NO_ACTIVE_WINDOW", "현재 화면을 읽을 수 없습니다.")
        while (true) {
            val match = findTextMatch(snapshot, query)
            if (match != null) {
                lastObservedSnapshot.set(snapshot)
                rememberUiObservation(snapshot)
                return toolTextResult(
                    JSONObject()
                        .put("success", true)
                        .put("found", true)
                        .put("snapshot_id", snapshot.fingerprint.hash)
                        .put("node_id", match.id)
                        .put("text", match.text ?: match.contentDescription ?: match.hint)
                        .put("scrolls_performed", performed),
                )
            }
            if (!shouldScroll || performed >= maxScrolls) break
            val container = snapshot.nodes
                .filter { it.visibleToUser && it.enabled && it.scrollable }
                .maxByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }
                ?: return toolError("NO_SCROLL_CONTAINER", "화면에서 스크롤 가능한 영역을 찾지 못했습니다.")
            rememberUiObservation(snapshot)
            val scrolled = adapter.call(
                "device_scroll",
                JSONObject()
                    .put("snapshot_id", snapshot.fingerprint.hash)
                    .put("direction", "down")
                    .put("node_id", container.id),
            )
            if (scrolled.optBoolean("isError")) return scrolled
            performed += 1
            Thread.sleep(FIND_NODE_SETTLE_MS)
            snapshot = captureSnapshot()
                ?: return toolError("NO_ACTIVE_WINDOW", "스크롤 후 화면을 읽을 수 없습니다.")
        }
        lastObservedSnapshot.set(snapshot)
        rememberUiObservation(snapshot)
        return toolTextResult(
            JSONObject()
                .put("success", true)
                .put("found", false)
                .put("snapshot_id", snapshot.fingerprint.hash)
                .put("scrolls_performed", performed),
        )
    }

    private fun setChecked(arguments: JSONObject, current: UiSnapshot?): JSONObject {
        if (!arguments.has("checked")) {
            return toolError("MISSING_CHECKED", "checked 값이 필요합니다.")
        }
        val target = validatedCurrentTarget(arguments, current) ?: return lastValidationError!!
        val wanted = arguments.optBoolean("checked")
        val before = target.checked
            ?: return toolError("NOT_CHECKABLE", "체크 상태를 제공하는 노드가 아닙니다.")
        if (before == wanted) {
            return toolTextResult(
                JSONObject().put("success", true).put("changed", false)
                    .put("node_id", target.id).put("checked", wanted),
            )
        }
        val action = clickTarget(target, current!!.packageName)
        if (!action) return toolError("SET_CHECKED_FAILED", "토글을 누르지 못했습니다.")
        Thread.sleep(ACTION_SETTLE_MS)
        val after = captureSnapshot()
            ?: return toolError("NO_ACTIVE_WINDOW", "동작 후 화면을 확인할 수 없습니다.")
        lastObservedSnapshot.set(after)
        rememberUiObservation(after)
        val checked = after.nodes.firstOrNull { sameNode(target, it) }?.checked
        if (checked != wanted) {
            return toolError("CHECK_STATE_NOT_REACHED", "요청한 체크 상태가 적용되지 않았습니다.")
        }
        return toolTextResult(
            JSONObject().put("success", true).put("changed", true)
                .put("node_id", target.id).put("checked", checked)
                .put("after_snapshot_id", after.fingerprint.hash),
        )
    }

    private fun selectOption(arguments: JSONObject, current: UiSnapshot?): JSONObject {
        val option = arguments.optString("option").trim()
        if (option.isEmpty() || option.length > 200) {
            return toolError("INVALID_OPTION", "option은 1~200자의 문자열이어야 합니다.")
        }
        val target = validatedCurrentTarget(arguments, current) ?: return lastValidationError!!
        if (!isSelectorNode(target)) {
            return toolError(
                "NOT_A_SELECTOR",
                "Spinner 또는 드롭다운으로 확인된 노드만 option을 선택할 수 있습니다.",
            )
        }
        if (!clickTarget(target, current!!.packageName)) {
            return toolError("OPEN_SELECTOR_FAILED", "선택 컨트롤을 열지 못했습니다.")
        }
        Thread.sleep(ACTION_SETTLE_MS)
        val options = captureSnapshot()
            ?: return toolError("NO_ACTIVE_WINDOW", "선택 항목 화면을 읽을 수 없습니다.")
        val optionNode = findTextMatch(options, option, exactOnly = true)
            ?.takeIf { it.enabled && it.visibleToUser }
            ?: return toolError("OPTION_NOT_FOUND", "정확히 일치하는 선택 항목을 찾지 못했습니다: $option")
        val optionDecision = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_click_node",
                arguments = JSONObject()
                    .put("snapshot_id", options.fingerprint.hash)
                    .put("node_id", optionNode.id),
                grantedScopes = setOf(RemoteToolScope.CONTROL),
                currentSnapshot = options,
                observedSnapshot = options,
            ),
        )
        if (optionDecision is RemotePolicyDecision.Deny) {
            return toolError(optionDecision.code, optionDecision.message)
        }
        if (!clickTarget(optionNode, options.packageName)) {
            return toolError("SELECT_OPTION_FAILED", "선택 항목을 누르지 못했습니다.")
        }
        Thread.sleep(ACTION_SETTLE_MS)
        val after = captureSnapshot() ?: options
        lastObservedSnapshot.set(after)
        rememberUiObservation(after)
        return toolTextResult(
            JSONObject().put("success", true).put("node_id", target.id)
                .put("option", option).put("after_snapshot_id", after.fingerprint.hash),
        )
    }

    @Volatile
    private var lastValidationError: JSONObject? = null

    private fun validatedCurrentTarget(arguments: JSONObject, current: UiSnapshot?): UiNode? {
        val observed = lastObservedSnapshot.get()
        if (observed == null) {
            lastValidationError = toolError("NO_OBSERVATION", "device_observe를 먼저 호출하세요.")
            return null
        }
        if (arguments.optString("snapshot_id") != observed.fingerprint.hash) {
            lastValidationError = toolError("STALE_SNAPSHOT", "가장 최근 snapshot_id가 아닙니다.")
            return null
        }
        val target = observed.nodes.firstOrNull { it.id == arguments.optString("node_id") }
        if (target == null) {
            lastValidationError = toolError("NODE_NOT_FOUND", "snapshot에 해당 node_id가 없습니다.")
            return null
        }
        if (current == null || current.packageName != observed.packageName || !sameNode(target, current.nodes.firstOrNull { it.id == target.id })) {
            lastValidationError = toolError("SCREEN_CHANGED", "대상 노드가 관찰 이후 변경됐습니다.")
            return null
        }
        return target
    }

    private fun sameNode(expected: UiNode, actual: UiNode?): Boolean =
        actual != null && expected.bounds == actual.bounds &&
            expected.viewId == actual.viewId &&
            expected.text == actual.text &&
            expected.contentDescription == actual.contentDescription

    private fun isSelectorNode(node: UiNode): Boolean {
        val className = node.className.orEmpty().lowercase()
        val label = listOfNotNull(node.text, node.contentDescription, node.hint)
            .joinToString(" ")
            .lowercase()
        val role = node.roleDescription.orEmpty().lowercase()
        return className.contains("spinner") ||
            className.contains("autocompletetextview") ||
            role.contains("drop-down") ||
            role.contains("dropdown") ||
            role.contains("combo") ||
            role.contains("드롭다운") ||
            role.contains("menu popup") ||
            role.contains("menu pop-up") ||
            role.contains("메뉴 팝업") ||
            label.contains("드롭다운") ||
            label.contains("dropdown")
    }

    private fun findTextMatch(
        snapshot: UiSnapshot,
        query: String,
        exactOnly: Boolean = false,
    ): UiNode? {
        val wanted = query.trim().lowercase()
        return snapshot.nodes
            .asSequence()
            .filter { it.visibleToUser }
            .mapNotNull { node ->
                val labels = listOfNotNull(node.text, node.contentDescription, node.hint)
                val exact = labels.any { it.trim().lowercase() == wanted }
                val contains = labels.any { it.lowercase().contains(wanted) }
                when {
                    exact -> node to 2
                    !exactOnly && contains -> node to 1
                    else -> null
                }
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun runOnMainThread(block: () -> Boolean): Boolean {
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(block())
            latch.countDown()
        }
        return latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS) && result.get()
    }

    private fun clickTarget(target: UiNode, packageName: String): Boolean {
        val service = AgentAccessibilityService.activeService ?: return false
        if (runOnMainThread { service.clickSnapshotNode(target, packageName).success }) return true
        if (!target.visibleToUser || target.bounds.isEmpty) return false
        val tapped = adapter.call(
            "device_tap",
            JSONObject()
                .put("x", target.bounds.exactCenterX())
                .put("y", target.bounds.exactCenterY()),
        )
        return !tapped.optBoolean("isError")
    }

    private fun toolTextResult(body: JSONObject): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", body.toString())))
        .put("isError", false)

    private fun fillField(
        arguments: JSONObject,
        current: UiSnapshot?,
        credentialOwner: String?,
    ): JSONObject {
        val field = arguments.optString("field")
        if (!SecretVault.ACCOUNT_FIELDS.containsKey(field)) {
            return toolError(
                "UNKNOWN_FIELD",
                "원격 로그인 자동 입력은 username 또는 password만 허용합니다.",
            )
        }
        val owner = credentialOwner
            ?: return toolError(
                "CREDENTIAL_OWNER_UNKNOWN",
                "현재 화면에 연결된 앱 계정을 안전하게 확인하지 못했습니다.",
            )
        if (!hasStoredAccountField(owner, field)) {
            return toolError(
                "FIELD_NOT_SET",
                "이 앱의 $field 값이 휴대폰에 등록돼 있지 않습니다.",
            )
        }

        val snapshotId = arguments.optString("snapshot_id")
        val nodeId = arguments.optString("node_id")
        val observed = lastObservedSnapshot.get()
            ?: return toolError("NO_OBSERVATION", "device_observe를 먼저 호출하세요.")
        if (snapshotId.isBlank() || snapshotId != observed.fingerprint.hash) {
            return toolError("STALE_SNAPSHOT", "가장 최근 snapshot_id가 아닙니다.")
        }
        if (current == null || current.packageName != observed.packageName) {
            return toolError("SCREEN_CHANGED", "관찰한 앱과 현재 앱이 다릅니다.")
        }
        val target = observed.nodes.firstOrNull { node -> node.id == nodeId }
            ?: return toolError("NODE_NOT_FOUND", "snapshot에 해당 node_id가 없습니다.")
        if (!target.editable || !target.enabled) {
            return toolError("NOT_EDITABLE", "사용 가능한 입력창이 아닙니다: $nodeId")
        }
        val currentTarget = current.nodes.firstOrNull { node ->
            node.id == nodeId &&
                node.editable &&
                node.enabled &&
                node.bounds == target.bounds &&
                node.viewId == target.viewId
        } ?: return toolError("SCREEN_CHANGED", "대상 입력창이 관찰 이후 변경됐습니다.")

        val label = listOfNotNull(
            currentTarget.text,
            currentTarget.hint,
            currentTarget.contentDescription,
            currentTarget.viewId,
        ).joinToString(" ").lowercase()
        val looksLikePassword =
            currentTarget.password ||
                listOf("비밀번호", "password", "passwd", "passcode")
                    .any(label::contains)
        if (field == "password" && !looksLikePassword) {
            return toolError(
                "SECRET_FIELD_MISMATCH",
                "비밀번호는 확인된 비밀번호 입력창에만 넣을 수 있습니다.",
            )
        }
        if (field == "username" && currentTarget.password) {
            return toolError(
                "SECRET_FIELD_MISMATCH",
                "아이디를 비밀번호 입력창에 넣을 수 없습니다.",
            )
        }

        val service = AgentAccessibilityService.activeService
            ?: return toolError(
                "ACCESSIBILITY_NOT_CONNECTED",
                "접근성 서비스가 연결되지 않았습니다.",
            )
        val succeeded = java.util.concurrent.atomic.AtomicBoolean(false)
        val missing = AtomicReference(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            val value = SecretVault.reveal(service, field, owner)
            if (value == null) {
                missing.set(true)
            } else {
                succeeded.set(
                    service.setTextOnSnapshotNode(
                        target,
                        observed.packageName,
                        value,
                    ),
                )
            }
            latch.countDown()
        }
        if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return toolError("FILL_TIMEOUT", "자동 입력 응답 시간이 초과됐습니다.")
        }
        if (missing.get()) {
            return toolError(
                "FIELD_NOT_SET",
                "이 앱의 $field 값이 휴대폰에 등록돼 있지 않습니다.",
            )
        }
        if (!succeeded.get()) {
            return toolError("FILL_FAILED", "$nodeId 입력창을 채우지 못했습니다.")
        }
        return JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put(
                            "text",
                            JSONObject()
                                .put("success", true)
                                .put("field", field)
                                .put("node_id", nodeId)
                                .put("message", "$field 값을 휴대폰 안에서 입력했습니다.")
                                .toString(),
                        ),
                ),
            )
            .put("isError", false)
    }

    private fun typeNode(arguments: JSONObject): JSONObject {
        val snapshotId = arguments.optString("snapshot_id")
        val nodeId = arguments.optString("node_id")
        val text = arguments.optString("text")
        val observed = lastObservedSnapshot.get()
            ?: return toolError("NO_OBSERVATION", "device_observe를 먼저 호출하세요.")
        if (snapshotId.isBlank() || snapshotId != observed.fingerprint.hash) {
            return toolError("STALE_SNAPSHOT", "가장 최근 snapshot_id가 아닙니다.")
        }
        val target = observed.nodes.firstOrNull { node -> node.id == nodeId }
            ?: return toolError("NODE_NOT_FOUND", "snapshot에 해당 node_id가 없습니다.")
        if (!target.editable) {
            return toolError("NOT_EDITABLE", "입력창이 아닌 노드입니다: $nodeId")
        }
        val service = AgentAccessibilityService.activeService
            ?: return toolError(
                "ACCESSIBILITY_NOT_CONNECTED",
                "접근성 서비스가 연결되지 않았습니다.",
            )
        val succeeded = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            succeeded.set(service.setTextOnSnapshotNode(target, observed.packageName, text))
            latch.countDown()
        }
        if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return toolError("TYPE_TIMEOUT", "텍스트 입력 응답 시간이 초과됐습니다.")
        }
        if (!succeeded.get()) {
            return toolError("TYPE_FAILED", "$nodeId 에 글자를 넣지 못했습니다.")
        }
        return JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put(
                            "text",
                            JSONObject()
                                .put("success", true)
                                .put("node_id", nodeId)
                                .toString(),
                        ),
                ),
            )
            .put("isError", false)
    }

    private fun toolError(code: String, message: String): JSONObject = JSONObject()
        .put(
            "content",
            JSONArray().put(
                JSONObject()
                    .put("type", "text")
                    .put(
                        "text",
                        JSONObject()
                            .put("success", false)
                            .put("error", code)
                            .put("message", message)
                            .toString(),
                    ),
            ),
        )
        .put("isError", true)

    private fun deviceStatus(): JSONObject {
        val snapshot = captureSnapshot()
        return JSONObject()
            .put("success", true)
            .put("accessibility_connected", AgentAccessibilityService.activeService != null)
            .put("foreground_package", snapshot?.packageName.orEmpty())
            .put("timestamp_ms", System.currentTimeMillis())
    }

    private fun observation(
        snapshot: UiSnapshot,
        maxNodes: Int,
        authorizationSnapshotId: String,
    ): JSONObject {
        val meaningful = snapshot.nodes.filter(UiNode::isMeaningfulForAgent)
        val nodes = JSONArray()
        meaningful.take(maxNodes).forEach { node ->
            nodes.put(
                JSONObject()
                    .put("id", node.id)
                    .put("text", node.text ?: JSONObject.NULL)
                    .put("content_description", node.contentDescription ?: JSONObject.NULL)
                    .put("hint", node.hint ?: JSONObject.NULL)
                    .put("class_name", node.className ?: JSONObject.NULL)
                    .put("role_description", node.roleDescription ?: JSONObject.NULL)
                    .put("view_id", node.viewId ?: JSONObject.NULL)
                    .put("clickable", node.clickable)
                    .put("editable", node.editable)
                    .put("password", node.password)
                    .put("scrollable", node.scrollable)
                    .put("enabled", node.enabled)
                    .put("checked", node.checked ?: JSONObject.NULL)
                    .put("selected", node.selected)
                    .put("focused", node.focused)
                    .put("visible_to_user", node.visibleToUser)
                    .put("depth", node.depth),
            )
        }
        return JSONObject()
            .put("success", true)
            // The UI content may be redacted into a different fingerprint,
            // but node actions must authorize against the exact raw snapshot
            // retained only on the phone.
            .put("snapshot_id", authorizationSnapshotId)
            .put("captured_at_ms", snapshot.capturedAtMillis)
            .put("package_name", snapshot.packageName)
            .put("node_count", snapshot.nodes.size)
            .put("returned_node_count", nodes.length())
            .put("truncated", meaningful.size > nodes.length())
            .put("nodes", nodes)
    }

    private fun captureSnapshot(): UiSnapshot? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return AgentAccessibilityService.activeService?.captureSnapshot()
        }
        val result = AtomicReference<UiSnapshot?>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(AgentAccessibilityService.activeService?.captureSnapshot())
            latch.countDown()
        }
        latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun credentialOwner(snapshot: UiSnapshot?): String? {
        val packageName = snapshot?.packageName?.takeIf(String::isNotBlank) ?: return null
        val service = AgentAccessibilityService.activeService ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return service.browserHost()
                ?.let { host -> SecretVault.serviceForHost(service, host) }
                ?: packageName
        }
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(
                service.browserHost()
                    ?.let { host -> SecretVault.serviceForHost(service, host) }
                    ?: packageName,
            )
            latch.countDown()
        }
        if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        return result.get()
    }

    private fun isLoginSubmissionRequest(arguments: JSONObject): Boolean {
        val observed = lastObservedSnapshot.get() ?: return false
        if (arguments.optString("snapshot_id") != observed.fingerprint.hash) return false
        val nodeId = arguments.optString("node_id")
        val target = observed.nodes.firstOrNull { node -> node.id == nodeId } ?: return false
        if (!target.clickable || !target.enabled) return false
        val text = target.text.orEmpty().trim().lowercase()
        val viewId = target.viewId.orEmpty().lowercase()
        return text == "로그인" ||
            text == "login" ||
            viewId.endsWith("loginbtn") ||
            viewId.endsWith("login_button")
    }

    private fun hasStoredAccountField(owner: String, field: String): Boolean {
        if (field !in SecretVault.ACCOUNT_FIELDS) return false
        val context = AgentAccessibilityService.activeService ?: return false
        return field in SecretVault.storedAccountFields(context, owner)
    }

    private fun hasStoredLoginCredential(owner: String): Boolean {
        val context = AgentAccessibilityService.activeService ?: return false
        val stored = SecretVault.storedAccountFields(context, owner)
        return "username" in stored && "password" in stored
    }

    private fun toolErrorDetails(result: JSONObject): Pair<String, String> {
        val text = result
            .optJSONArray("content")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
        val payload = runCatching { JSONObject(text) }.getOrNull()
        return Pair(
            payload?.optString("error")?.takeIf(String::isNotBlank)
                ?: "DEVICE_COMMAND_FAILED",
            payload?.optString("message")?.takeIf(String::isNotBlank)
                ?: "휴대폰에서 명령 실행에 실패했습니다.",
        )
    }

    private fun failure(
        commandId: String,
        code: String,
        message: String,
        status: String = "failed",
    ): JSONObject = JSONObject()
        .put("type", "result")
        .put("commandId", commandId)
        .put("status", status)
        .put(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message)
                .put("retryable", false),
        )

    private fun JSONArray.strings(): List<String> =
        (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }

    private companion object {
        const val DEFAULT_MAX_NODES = 80
        const val MAX_NODES = 200
        const val MAIN_THREAD_TIMEOUT_MS = 3_000L
        const val FIND_NODE_SETTLE_MS = 400L
        const val ACTION_SETTLE_MS = 350L
    }
}
