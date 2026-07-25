package com.example.mobileguiagent.mcp

import android.os.Handler
import android.os.Looper
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.model.NodeActionResult
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.repository.AgentRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal stateless MCP Streamable HTTP server for the Android PoC.
 *
 * It implements the MCP initialization, ping, tools/list, and tools/call
 * methods needed by Codex. GET /mcp deliberately returns 405 because this
 * server does not emit server-initiated SSE notifications.
 */
class PocketMcpHttpServer(
    private val port: Int,
    private val authToken: String,
    private val onRequest: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val requestExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val lastSnapshot = AtomicReference<UiSnapshot?>()
    private val mcpDeviceToolAdapter = McpDeviceToolAdapter(DeviceToolRegistry())
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverSocket = ServerSocket(port).apply {
            reuseAddress = true
        }
        acceptExecutor.execute {
            while (running.get()) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    requestExecutor.execute { handleConnection(socket) }
                } catch (_: Throwable) {
                    if (running.get()) {
                        McpServerRepository.onError("MCP 연결 수락에 실패했습니다.")
                    }
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptExecutor.shutdownNow()
        requestExecutor.shutdownNow()
    }

    fun networkEndpoints(): List<String> {
        val addresses = Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network ->
                Collections.list(network.inetAddresses)
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress }
                    .map { address -> network.name to address.hostAddress.orEmpty() }
            }
            .sortedBy { (name, _) ->
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    else -> 2
                }
            }
            .map { (_, address) -> "http://$address:$port/mcp" }
            .distinct()
            .toList()
        return addresses.ifEmpty { listOf("http://127.0.0.1:$port/mcp") }
    }

    private fun handleConnection(socket: Socket) {
        socket.use {
            it.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(it.getInputStream())
            val output = BufferedOutputStream(it.getOutputStream())
            try {
                val requestLine = readHttpLine(input) ?: return
                val requestParts = requestLine.split(' ')
                if (requestParts.size < 2) {
                    writeJsonResponse(output, 400, errorBody("잘못된 HTTP 요청입니다."))
                    return
                }
                val method = requestParts[0].uppercase()
                val requestTarget = requestParts[1]
                val path = requestTarget.substringBefore('?')
                val queryToken = requestTarget
                    .substringAfter("pairing_token=", "")
                    .substringBefore('&')
                val headers = readHeaders(input)

                if (path == "/health" && method == "GET") {
                    writeJsonResponse(
                        output,
                        200,
                        JSONObject()
                            .put("status", "ok")
                            .put("server", SERVER_NAME)
                            .put("mcp_endpoint", "/mcp"),
                    )
                    return
                }
                if (path != "/mcp") {
                    writeJsonResponse(output, 404, errorBody("경로를 찾을 수 없습니다."))
                    return
                }
                if (
                    headers["authorization"] != "Bearer $authToken" &&
                    queryToken != authToken
                ) {
                    writeJsonResponse(output, 401, errorBody("유효한 페어링 토큰이 필요합니다."))
                    return
                }
                if (headers.containsKey("origin")) {
                    writeJsonResponse(output, 403, errorBody("브라우저 Origin 요청은 허용하지 않습니다."))
                    return
                }
                if (method == "GET" || method == "DELETE") {
                    writeEmptyResponse(output, 405)
                    return
                }
                if (method != "POST") {
                    writeEmptyResponse(output, 405)
                    return
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength <= 0 || contentLength > MAX_BODY_BYTES) {
                    writeJsonResponse(output, 400, errorBody("요청 본문 크기가 올바르지 않습니다."))
                    return
                }
                val bodyBytes = input.readExactly(contentLength)
                val request = JSONObject(String(bodyBytes, StandardCharsets.UTF_8))
                val rpcMethod = request.optString("method")
                onRequest(rpcMethod)

                if (!request.has("id")) {
                    writeEmptyResponse(output, 202)
                    return
                }
                val response = dispatchRequest(request)
                writeJsonResponse(output, 200, response)
            } catch (error: Throwable) {
                runCatching {
                    writeJsonResponse(
                        output,
                        500,
                        errorBody(error.message ?: error::class.java.simpleName),
                    )
                }
            }
        }
    }

    private fun dispatchRequest(request: JSONObject): JSONObject {
        val id = request.opt("id")
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> rpcResult(id, initializeResult(params))
            "ping" -> rpcResult(id, JSONObject())
            "tools/list" -> rpcResult(id, toolsListResult())
            "tools/call" -> rpcResult(id, callTool(params))
            else -> rpcError(id, -32601, "지원하지 않는 MCP 메서드입니다: $method")
        }
    }

    private fun initializeResult(params: JSONObject): JSONObject {
        val requestedVersion = params.optString("protocolVersion")
        val protocolVersion = requestedVersion.takeIf(String::isNotBlank) ?: MCP_VERSION
        return JSONObject()
            .put("protocolVersion", protocolVersion)
            .put(
                "capabilities",
                JSONObject().put(
                    "tools",
                    JSONObject().put("listChanged", false),
                ),
            )
            .put(
                "serverInfo",
                JSONObject()
                    .put("name", SERVER_NAME)
                    .put("version", SERVER_VERSION),
            )
            .put(
                "instructions",
                "Authenticated Android UI server. Call device_status, then device_observe. " +
                    "Only click node IDs from the newest snapshot.",
            )
    }

    private fun toolsListResult(): JSONObject = JSONObject().put(
        "tools",
        JSONArray()
            .put(
                JSONObject()
                    .put("name", "device_status")
                    .put(
                        "description",
                        "Returns MCP server, AccessibilityService, and foreground app status.",
                    )
                    .put("inputSchema", objectSchema(JSONObject())),
            )
            .put(
                JSONObject()
                    .put("name", "device_observe")
                    .put(
                        "description",
                        "Reads the current Android accessibility UI tree. This tool has no side effects.",
                    )
                    .put(
                        "inputSchema",
                        objectSchema(
                            JSONObject().put(
                                "max_nodes",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1)
                                    .put("maximum", MAX_RETURNED_NODES)
                                    .put("default", DEFAULT_RETURNED_NODES),
                            ),
                        ),
                    ),
            ),
    ).also { result ->
        result.getJSONArray("tools").put(
            JSONObject()
                .put("name", "device_open_settings")
                .put(
                    "description",
                    "Opens the Android system Settings app, then reports the observed foreground screen.",
                )
                .put("inputSchema", objectSchema(JSONObject())),
        )
    }.also { result ->
        result.getJSONArray("tools").put(
            JSONObject()
                .put("name", "device_click_node")
                .put(
                    "description",
                    "Clicks one node from the most recent device_observe snapshot. " +
                        "Rejects stale snapshot IDs and verifies whether the screen changed.",
                )
                .put(
                    "inputSchema",
                    objectSchema(
                        JSONObject()
                            .put(
                                "snapshot_id",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "Exact snapshot_id returned by device_observe."),
                            )
                            .put(
                                "node_id",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "Node id from the same snapshot."),
                            ),
                    ).put(
                        "required",
                        JSONArray()
                            .put("snapshot_id")
                            .put("node_id"),
                    ),
                ),
        )
    }.also { result ->
        // 어댑터가 담당하는 device tool(screenshot/back/scroll/type_text…)을 한 번에 노출.
        val tools = result.getJSONArray("tools")
        mcpDeviceToolAdapter.definitions().forEach { definition -> tools.put(definition) }
    }

    private fun objectSchema(properties: JSONObject): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("additionalProperties", false)

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        return when (name) {
            "device_status" -> toolResult(deviceStatus())
            "device_observe" -> {
                val maxNodes = arguments.optInt(
                    "max_nodes",
                    DEFAULT_RETURNED_NODES,
                ).coerceIn(1, MAX_RETURNED_NODES)
                val snapshot = captureSnapshotOnMainThread()
                if (snapshot == null) {
                    toolResult(
                        JSONObject()
                            .put("success", false)
                            .put("error", "ACCESSIBILITY_NOT_CONNECTED_OR_NO_ACTIVE_WINDOW"),
                        isError = true,
                    )
                } else {
                    lastSnapshot.set(snapshot)
                    toolResult(snapshotJson(snapshot, maxNodes))
                }
            }
            "device_open_settings" -> openSettings()
            "device_click_node" -> clickNode(arguments)
            else -> if (mcpDeviceToolAdapter.handles(name)) {
                mcpDeviceToolAdapter.call(name, arguments)
            } else {
                toolResult(
                    JSONObject()
                        .put("success", false)
                        .put("error", "UNKNOWN_TOOL")
                        .put("tool", name),
                    isError = true,
                )
            }
        }
    }

    private fun openSettings(): JSONObject {
        val before = captureSnapshotOnMainThread()
        val service = AgentAccessibilityService.activeService
            ?: return toolError(
                "ACCESSIBILITY_NOT_CONNECTED",
                "접근성 서비스가 연결되지 않았습니다.",
            )
        val opened = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            opened.set(service.openAndroidSettings())
            latch.countDown()
        }
        latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!opened.get()) {
            return toolError("OPEN_SETTINGS_FAILED", "Android 설정 앱을 열지 못했습니다.")
        }

        val after = before?.let(::waitForScreenChange) ?: captureSnapshotOnMainThread()
        if (after != null) lastSnapshot.set(after)
        return toolResult(
            JSONObject()
                .put("success", true)
                .put("before_package", before?.packageName ?: JSONObject.NULL)
                .put("after_package", after?.packageName ?: JSONObject.NULL)
                .put("after_snapshot_id", after?.fingerprint?.hash ?: JSONObject.NULL),
        )
    }

    private fun clickNode(arguments: JSONObject): JSONObject {
        val snapshotId = arguments.optString("snapshot_id")
        val nodeId = arguments.optString("node_id")
        val observed = lastSnapshot.get()
            ?: return toolError("NO_OBSERVATION", "device_observe를 먼저 호출하세요.")
        if (snapshotId.isBlank() || snapshotId != observed.fingerprint.hash) {
            return toolError("STALE_SNAPSHOT", "가장 최근 snapshot_id가 아닙니다.")
        }

        val current = captureSnapshotOnMainThread()
            ?: return toolError("NO_ACTIVE_WINDOW", "현재 UI 트리를 읽을 수 없습니다.")
        if (
            current.packageName != observed.packageName ||
            current.fingerprint.hash != snapshotId
        ) {
            lastSnapshot.set(current)
            return toolError("SCREEN_CHANGED", "관찰 후 화면이 바뀌어 클릭을 거부했습니다.")
        }

        val target = observed.nodes.firstOrNull { it.id == nodeId }
            ?: return toolError("NODE_NOT_FOUND", "snapshot에 해당 node_id가 없습니다.")
        if (!target.enabled) {
            return toolError("NODE_DISABLED", "비활성 노드는 클릭할 수 없습니다.")
        }

        val action = clickSnapshotNodeOnMainThread(target, observed.packageName)
            ?: return toolError("ACCESSIBILITY_NOT_CONNECTED", "접근성 서비스가 연결되지 않았습니다.")

        // 이중 전략(구현가이드 3장 노드 주소 지정): 라벨 기반 클릭(ACTION_CLICK)이
        // 실패하면 — 라벨 없는 노드 등 — snapshot에 있는 bounds 중앙을 좌표 탭한다.
        // 화면이 안 바뀐 건 위에서 이미 검증했으므로 이 좌표를 신뢰할 수 있다.
        var success = action.success
        var method = "node_click"
        if (!success) {
            val tapped = tapOnMainThread(
                target.bounds.exactCenterX(),
                target.bounds.exactCenterY(),
            )
            if (tapped) {
                success = true
                method = "coordinate_tap"
            }
        }

        val after = waitForScreenChange(current)
        if (after != null) lastSnapshot.set(after)
        val changed = after?.fingerprint?.hash != current.fingerprint.hash

        return toolResult(
            JSONObject()
                .put("success", success)
                .put("node_id", nodeId)
                .put(
                    "label",
                    target.text ?: target.contentDescription ?: JSONObject.NULL,
                )
                .put("matched_text", action.matchedText ?: JSONObject.NULL)
                .put("used_clickable_ancestor", action.usedClickableAncestor)
                .put("method", method)
                .put("screen_changed", changed)
                .put("before_package", current.packageName)
                .put("after_package", after?.packageName ?: current.packageName)
                .put(
                    "after_snapshot_id",
                    after?.fingerprint?.hash ?: current.fingerprint.hash,
                ),
            isError = !success,
        )
    }

    private fun tapOnMainThread(x: Float, y: Float): Boolean {
        val service = AgentAccessibilityService.activeService ?: return false
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            service.tap(x, y) { completed ->
                result.set(completed)
                latch.countDown()
            }
        }
        latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun toolError(code: String, message: String): JSONObject = toolResult(
        JSONObject()
            .put("success", false)
            .put("error", code)
            .put("message", message),
        isError = true,
    )

    private fun clickSnapshotNodeOnMainThread(
        target: UiNode,
        packageName: String,
    ): NodeActionResult? {
        val result = AtomicReference<NodeActionResult?>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(
                AgentAccessibilityService.activeService?.clickSnapshotNode(
                    target = target,
                    expectedPackage = packageName,
                ),
            )
            latch.countDown()
        }
        latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun waitForScreenChange(before: UiSnapshot): UiSnapshot? {
        val deadline = System.currentTimeMillis() + ACTION_VERIFY_TIMEOUT_MS
        var latest: UiSnapshot? = null
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(ACTION_VERIFY_POLL_MS)
            latest = captureSnapshotOnMainThread()
            if (
                latest != null &&
                (
                    latest.packageName != before.packageName ||
                        latest.fingerprint.hash != before.fingerprint.hash
                    )
            ) {
                return latest
            }
        }
        return latest
    }

    private fun deviceStatus(): JSONObject {
        val state = AgentRepository.state.value
        return JSONObject()
            .put("success", true)
            .put("server", SERVER_NAME)
            .put("server_version", SERVER_VERSION)
            .put("accessibility_connected", AgentAccessibilityService.activeService != null)
            .put("foreground_package", state.foregroundPackage)
            .put("agent_running", state.running)
            .put("timestamp_ms", System.currentTimeMillis())
    }

    /**
     * LLM에 보낼 노드만 남기는 필터.
     *
     * 아래 중 하나라도 참이면 "의미 있는 노드"로 보고 유지한다:
     *  - 직접 동작 가능: clickable / editable / scrollable
     *  - 정보가 있음: text(보이는 글자) 또는 content_description(아이콘 등 접근성 라벨)
     *
     * 걸러지는 건 라벨도 동작도 없는 순수 레이아웃 컨테이너·장식 뷰뿐이다.
     * 놓침(recall) 방지를 우선해 라벨 없는 clickable도 남긴다.
     *
     * 추가로 visibleToUser=false(가려졌거나 화면 밖, 예: 열린 폴더 뒤 workspace,
     * 스크롤 밖 리스트 항목, 옆 홈페이지 peek)는 제외한다. 화면에 실제로 없는 걸
     * LLM에 보여주면 착각하므로.
     */
    private fun isMeaningfulNode(node: UiNode): Boolean =
        node.visibleToUser &&
            (
                node.clickable ||
                    node.editable ||
                    node.scrollable ||
                    !node.text.isNullOrBlank() ||
                    !node.contentDescription.isNullOrBlank()
                )

    private fun snapshotJson(snapshot: UiSnapshot, maxNodes: Int): JSONObject {
        // 필터는 반환용 목록에만 적용. 저장 원본(lastSnapshot)과 snapshot_id(fingerprint)는
        // 그대로라 click_node 정합성 검사는 영향받지 않는다. node.id도 원래 값을 유지한다.
        val meaningful = snapshot.nodes.filter(::isMeaningfulNode)
        val nodes = JSONArray()
        meaningful.take(maxNodes).forEach { node ->
            nodes.put(
                JSONObject()
                    .put("id", node.id)
                    .put("text", node.text ?: JSONObject.NULL)
                    .put("content_description", node.contentDescription ?: JSONObject.NULL)
                    .put("class_name", node.className ?: JSONObject.NULL)
                    .put("view_id", node.viewId ?: JSONObject.NULL)
                    .put("clickable", node.clickable)
                    .put("editable", node.editable)
                    .put("scrollable", node.scrollable)
                    .put("enabled", node.enabled)
                    .put("checked", node.checked ?: JSONObject.NULL)
                    .put("depth", node.depth)
                    .put(
                        "bounds",
                        JSONObject()
                            .put("left", node.bounds.left)
                            .put("top", node.bounds.top)
                            .put("right", node.bounds.right)
                            .put("bottom", node.bounds.bottom),
                    ),
            )
        }
        return JSONObject()
            .put("success", true)
            .put("snapshot_id", snapshot.fingerprint.hash)
            .put("captured_at_ms", snapshot.capturedAtMillis)
            .put("package_name", snapshot.packageName)
            .put("node_count", snapshot.nodes.size)
            .put("meaningful_node_count", meaningful.size)
            .put("returned_node_count", nodes.length())
            .put("truncated", meaningful.size > nodes.length())
            .put("nodes", nodes)
    }

    private fun captureSnapshotOnMainThread(): UiSnapshot? {
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

    private fun toolResult(payload: JSONObject, isError: Boolean = false): JSONObject =
        JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", payload.toString()),
                ),
            )
            .put("isError", isError)

    private fun rpcResult(id: Any?, result: JSONObject): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message),
        )

    private fun errorBody(message: String): JSONObject = JSONObject()
        .put("error", message)

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readHttpLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        return headers
    }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value == -1) {
                return if (bytes.isEmpty()) {
                    null
                } else {
                    bytes.toByteArray().toString(StandardCharsets.UTF_8)
                }
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        return bytes.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private fun BufferedInputStream.readExactly(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(bytes, offset, length - offset)
            if (read < 0) error("요청 본문이 중간에 끝났습니다.")
            offset += read
        }
        return bytes
    }

    private fun writeJsonResponse(
        output: BufferedOutputStream,
        status: Int,
        body: JSONObject,
    ) {
        val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
        writeResponseHeaders(output, status, "application/json", payload.size)
        output.write(payload)
        output.flush()
    }

    private fun writeEmptyResponse(output: BufferedOutputStream, status: Int) {
        writeResponseHeaders(output, status, null, 0)
        output.flush()
    }

    private fun writeResponseHeaders(
        output: BufferedOutputStream,
        status: Int,
        contentType: String?,
        contentLength: Int,
    ) {
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            403 -> "Forbidden"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
    }

    companion object {
        private const val SERVER_NAME = "PocketMCP Android"
        private const val SERVER_VERSION = "0.1.0"
        private const val MCP_VERSION = "2025-11-25"
        private const val DEFAULT_RETURNED_NODES = 120
        private const val MAX_RETURNED_NODES = 500
        private const val MAX_BODY_BYTES = 1_048_576
        private const val MAX_HEADER_LINE_BYTES = 16_384
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val MAIN_THREAD_TIMEOUT_MS = 3_000L
        private const val ACTION_VERIFY_TIMEOUT_MS = 3_000L
        private const val ACTION_VERIFY_POLL_MS = 150L
    }
}
