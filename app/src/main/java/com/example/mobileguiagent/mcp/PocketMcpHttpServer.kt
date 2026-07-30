package com.example.mobileguiagent.mcp

import android.os.Handler
import android.os.Looper
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.rememberUiObservation
import com.example.mobileguiagent.model.NodeActionResult
import com.example.mobileguiagent.model.LocalChatRepository
import com.example.mobileguiagent.secret.SecretVault
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.model.isMeaningfulForAgent
import com.example.mobileguiagent.remote.RemoteCommandPolicy
import com.example.mobileguiagent.remote.RemoteObservationRedactor
import com.example.mobileguiagent.remote.RemotePolicyDecision
import com.example.mobileguiagent.remote.RemotePolicyRequest
import com.example.mobileguiagent.remote.RemoteToolCatalog
import com.example.mobileguiagent.remote.RemoteToolScope
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
 * Minimal stateless MCP Streamable HTTP server for Android device control.
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

    internal fun toolsListResult(): JSONObject = JSONObject().put(
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
        result.getJSONArray("tools").put(
            JSONObject()
                .put("name", "device_fill_field")
                .put(
                    "description",
                    "Fills one input from the newest device_observe snapshot with a value " +
                        "stored on the device. Takes the field name only; the value never " +
                        "leaves the phone and is not returned.",
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
                                    .put("description", "Input node id from the same snapshot."),
                            )
                            .put(
                                "field",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray(SecretVault.FIELDS.keys.toList()))
                                    .put("description", fieldHints()),
                            ),
                    ).put(
                        "required",
                        JSONArray().put("snapshot_id").put("node_id").put("field"),
                    ),
                ),
        )
    }.also { result ->
        result.getJSONArray("tools").put(
            JSONObject()
                .put("name", "device_type_node")
                .put(
                    "description",
                    "Types text into one input from the newest device_observe snapshot. " +
                        "Unlike device_type_text this does not rely on focus.",
                )
                .put(
                    "inputSchema",
                    objectSchema(
                        JSONObject()
                            .put(
                                "snapshot_id",
                                JSONObject().put("type", "string")
                                    .put("description", "Exact snapshot_id from device_observe."),
                            )
                            .put(
                                "node_id",
                                JSONObject().put("type", "string")
                                    .put("description", "Input node id from the same snapshot."),
                            )
                            .put(
                                "text",
                                JSONObject().put("type", "string")
                                    .put("description", "Text to put in that input."),
                            ),
                    ).put(
                        "required",
                        JSONArray().put("snapshot_id").put("node_id").put("text"),
                    ),
                ),
        )
    }.also { result ->
        result.getJSONArray("tools").put(
            JSONObject()
                .put("name", "device_set_progress")
                .put(
                    "description",
                    "Moves a slider (brightness, volume, seek bar) to an exact value. " +
                        "Only nodes that device_observe reported with a \"range\" can be set; " +
                        "tapping such a node cannot choose a value.",
                )
                .put(
                    "inputSchema",
                    objectSchema(
                        JSONObject()
                            .put(
                                "snapshot_id",
                                JSONObject().put("type", "string")
                                    .put("description", "Exact snapshot_id from device_observe."),
                            )
                            .put(
                                "node_id",
                                JSONObject().put("type", "string")
                                    .put("description", "Slider node id from the same snapshot."),
                            )
                            .put(
                                "value",
                                JSONObject().put("type", "number")
                                    .put(
                                        "description",
                                        "Target value inside the node's own min..max range.",
                                    ),
                            ),
                    ).put(
                        "required",
                        JSONArray().put("snapshot_id").put("node_id").put("value"),
                    ),
                ),
        )
    }.also { result ->
        result.getJSONArray("tools").put(
            JSONObject()
                .put("name", "device_find_node")
                .put(
                    "description",
                    "Finds visible UI text and can scroll a bounded number of times. " +
                        "Returns a fresh snapshot_id and node_id without clicking.",
                )
                .put(
                    "inputSchema",
                    objectSchema(
                        JSONObject()
                            .put("text", JSONObject().put("type", "string").put("minLength", 1))
                            .put("scroll", JSONObject().put("type", "boolean").put("default", false))
                            .put(
                                "max_scrolls",
                                JSONObject().put("type", "integer").put("minimum", 0)
                                    .put("maximum", 5).put("default", 3),
                            ),
                    ).put("required", JSONArray().put("text")),
                ),
        )
    }.also { result ->
        result.getJSONArray("tools").put(
            JSONObject()
                .put("name", "device_set_checked")
                .put(
                    "description",
                    "Idempotently sets a checkbox, switch, or toggle to the requested state.",
                )
                .put(
                    "inputSchema",
                    objectSchema(
                        JSONObject()
                            .put("snapshot_id", JSONObject().put("type", "string"))
                            .put("node_id", JSONObject().put("type", "string"))
                            .put("checked", JSONObject().put("type", "boolean")),
                    ).put(
                        "required",
                        JSONArray().put("snapshot_id").put("node_id").put("checked"),
                    ),
                ),
        )
    }.also { result ->
        result.getJSONArray("tools").put(
            JSONObject()
                .put("name", "device_select_option")
                .put(
                    "description",
                    "Opens a selector from the newest snapshot and chooses an exact visible option.",
                )
                .put(
                    "inputSchema",
                    objectSchema(
                        JSONObject()
                            .put("snapshot_id", JSONObject().put("type", "string"))
                            .put("node_id", JSONObject().put("type", "string"))
                            .put("option", JSONObject().put("type", "string").put("minLength", 1)),
                    ).put(
                        "required",
                        JSONArray().put("snapshot_id").put("node_id").put("option"),
                    ),
                ),
        )
    }.also { result ->
        // 어댑터가 담당하는 device tool(screenshot/back/scroll/type_text…)을 한 번에 노출.
        // device_click_node는 위에서 snapshot_id를 강제하고 stale-screen 검증을
        // 제공하는 Pocket 전용 정의로 이미 노출했다. RemoteDeviceCommandExecutor가
        // 사용하는 adapter 매핑은 유지하되, tools/list에 raw 정의를 중복시키지 않는다.
        val tools = result.getJSONArray("tools")
        mcpDeviceToolAdapter.definitions()
            .filterNot { definition ->
                definition.optString("name") ==
                    McpDeviceToolAdapter.EXTERNAL_CLICK_NODE_NAME
            }
            .forEach { definition -> tools.put(definition) }
    }

    private fun objectSchema(properties: JSONObject): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("additionalProperties", false)

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val spec = RemoteToolCatalog.find(name)
            ?: return toolError(
                "REMOTE_TOOL_NOT_ALLOWED",
                "원격 실행이 허용되지 않은 도구입니다: $name",
            )
        val policySnapshot = if (spec.needsCurrentSnapshot) {
            captureSnapshotOnMainThread()
        } else {
            null
        }
        // Make this exact snapshot addressable by the adapter. Calls still
        // have to carry its snapshot_id; node tools never consume a global
        // latest-screen fallback.
        policySnapshot?.let(::rememberUiObservation)
        val policyDecision = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = name,
                arguments = arguments,
                grantedScopes = RemoteToolScope.ALL,
                currentSnapshot = policySnapshot,
                observedSnapshot = lastSnapshot.get(),
                // The LAN MCP server has no trusted confirmation UI. Sensitive
                // commands remain blocked until the remote device flow supplies
                // a short-lived local approval.
                locallyConfirmed = false,
            ),
        )
        if (policyDecision is RemotePolicyDecision.Deny) {
            return toolError(policyDecision.code, policyDecision.message)
        }
        return when (name) {
            "device_status" -> toolResult(deviceStatus())
            "device_observe" -> {
                val maxNodes = arguments.optInt(
                    "max_nodes",
                    DEFAULT_RETURNED_NODES,
                ).coerceIn(1, MAX_RETURNED_NODES)
                val snapshot = policySnapshot ?: captureSnapshotOnMainThread()
                if (snapshot == null) {
                    toolResult(
                        JSONObject()
                            .put("success", false)
                            .put("error", "ACCESSIBILITY_NOT_CONNECTED_OR_NO_ACTIVE_WINDOW"),
                        isError = true,
                    )
                } else {
                    lastSnapshot.set(snapshot)
                    val outgoingSnapshot =
                        if (policyDecision is RemotePolicyDecision.AllowRedacted) {
                            RemoteObservationRedactor.redact(snapshot)
                        } else {
                            snapshot
                        }
                    toolResult(snapshotJson(outgoingSnapshot, maxNodes))
                }
            }
            "device_open_settings" -> openSettings()
            "device_click_node" -> clickNode(arguments)
            "device_fill_field" -> fillField(arguments)
            "device_type_node" -> typeNode(arguments)
            "device_set_progress" -> setProgress(arguments)
            "device_find_node" -> findNode(arguments, policySnapshot)
            "device_set_checked" -> setChecked(arguments)
            "device_select_option" -> selectOption(arguments)
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

    /**
     * 금고 값을 꺼내 돌려준다.
     *
     * fill_field는 값을 밖으로 내보내지 않지만 이건 내보낸다. 흐름이 그렇게 정해져
     * 있기 때문이다 — 클라우드 모델이 "이 화면엔 아이디·비밀번호가 필요하다"까지만
     * 판단하고, 값을 꺼내 기기 안 모델에게 넘기는 건 code가 한다.
     *
     * 값이 나가는 만큼 조건은 그대로 지킨다. 계정 필드는 지금 화면에 떠 있는 앱
     * 것만 준다. 부르는 쪽이 다른 앱 계정을 지목할 수 없다.
     */
    /** 지금 화면의 계정 주인. shown은 못 찾았을 때 사람에게 보여줄 이름이다. */
    private class AccountOwner(val service: String?, val shown: String)

    /**
     * 계정을 어느 앱 것으로 볼지 정한다. 반드시 메인 스레드에서 부른다.
     *
     * 앱이 제 화면에서 직접 로그인받으면 그 앱이다. 그런데 로그인을 웹으로 넘기는
     * 앱이 많다 — 쿠팡은 크롬 커스텀탭으로 login.coupang.com을 연다. 눈앞의
     * 패키지만 보면 com.android.chrome이라, 쿠팡 계정을 등록해둬도 찾지 못한다.
     * 그래서 브라우저일 때는 주소창을 읽어 등록해둔 앱과 맞춰본다.
     */
    private fun accountOwner(service: AgentAccessibilityService): AccountOwner {
        service.browserHost()?.let { host ->
            return AccountOwner(SecretVault.serviceForHost(service, host), host)
        }
        val appPackage = service.rootInActiveWindow?.packageName?.toString()
        return AccountOwner(appPackage, appPackage ?: "알 수 없는 화면")
    }

    /** 스냅샷에서 고른 입력창에 글자를 넣는다. 포커스에 기대지 않는다. */
    private fun typeNode(arguments: JSONObject): JSONObject {
        val snapshotId = arguments.optString("snapshot_id")
        val nodeId = arguments.optString("node_id")
        val text = arguments.optString("text")
        val observed = lastSnapshot.get()
            ?: return toolError("NO_OBSERVATION", "device_observe를 먼저 호출하세요.")
        if (snapshotId.isBlank() || snapshotId != observed.fingerprint.hash) {
            return toolError("STALE_SNAPSHOT", "가장 최근 snapshot_id가 아닙니다.")
        }
        val target = observed.nodes.firstOrNull { it.id == nodeId }
            ?: return toolError("NODE_NOT_FOUND", "snapshot에 해당 node_id가 없습니다.")
        if (!target.editable) {
            return toolError("NOT_EDITABLE", "입력창이 아닌 노드입니다: $nodeId")
        }
        val service = AgentAccessibilityService.activeService
            ?: return toolError("ACCESSIBILITY_NOT_CONNECTED", "접근성 서비스가 연결되지 않았습니다.")

        val done = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            done.set(service.setTextOnSnapshotNode(target, observed.packageName, text))
            latch.countDown()
        }
        latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!done.get()) return toolError("TYPE_FAILED", "$nodeId 에 글자를 넣지 못했습니다.")

        captureSnapshotOnMainThread()?.let(lastSnapshot::set)
        // 값은 응답에 싣지 않는다. 어디에 넣었는지만.
        return toolResult(JSONObject().put("success", true).put("node_id", nodeId))
    }

    /**
     * 스냅샷에서 고른 슬라이더를 그 값으로 옮긴다.
     *
     * 범위를 넘는 값은 거절하지 않고 잘라 맞춘다. 모델이 "절반"을 50으로 옮겨
     * 적었는데 그 슬라이더가 0~10이면 거절해봐야 다시 물어볼 뿐이고, 최댓값으로
     * 두는 게 의도에 더 가깝다. 대신 실제로 넣은 값을 응답에 담아 알려준다.
     */
    private fun setProgress(arguments: JSONObject): JSONObject {
        val snapshotId = arguments.optString("snapshot_id")
        val nodeId = arguments.optString("node_id")
        if (!arguments.has("value")) {
            return toolError("MISSING_VALUE", "value가 필요합니다.")
        }
        val observed = lastSnapshot.get()
            ?: return toolError("NO_OBSERVATION", "device_observe를 먼저 호출하세요.")
        if (snapshotId.isBlank() || snapshotId != observed.fingerprint.hash) {
            return toolError("STALE_SNAPSHOT", "가장 최근 snapshot_id가 아닙니다.")
        }
        val target = observed.nodes.firstOrNull { it.id == nodeId }
            ?: return toolError("NODE_NOT_FOUND", "snapshot에 해당 node_id가 없습니다.")
        val range = target.range
            ?: return toolError(
                "NOT_A_SLIDER",
                "슬라이더가 아닌 노드입니다: $nodeId. 값을 가진 노드에만 쓸 수 있습니다.",
            )
        val service = AgentAccessibilityService.activeService
            ?: return toolError("ACCESSIBILITY_NOT_CONNECTED", "접근성 서비스가 연결되지 않았습니다.")

        val wanted = arguments.optDouble("value").toFloat()
        if (wanted.isNaN()) {
            return toolError("BAD_VALUE", "value는 숫자여야 합니다.")
        }
        val clamped = wanted.coerceIn(range.min, range.max)

        val done = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            done.set(service.setProgressOnSnapshotNode(target, observed.packageName, clamped))
            latch.countDown()
        }
        latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!done.get()) {
            return toolError(
                "SET_PROGRESS_FAILED",
                "$nodeId 값을 바꾸지 못했습니다. 앱이 이 슬라이더의 값 설정을 지원하지 " +
                    "않으면 화면에서 직접 끌어야 합니다.",
            )
        }

        captureSnapshotOnMainThread()?.let(lastSnapshot::set)
        val note = if (clamped != wanted) " (${range.min}~${range.max} 범위로 맞춤)" else ""
        return toolResult(
            JSONObject()
                .put("success", true)
                .put("node_id", nodeId)
                .put("value", clamped)
                .put("message", "$nodeId 를 $clamped 로 옮겼습니다$note."),
        )
    }

    private fun findNode(arguments: JSONObject, initial: UiSnapshot?): JSONObject {
        val query = arguments.optString("text").trim()
        if (query.isEmpty() || query.length > 200) {
            return toolError("INVALID_QUERY", "text는 1~200자의 문자열이어야 합니다.")
        }
        val shouldScroll = arguments.optBoolean("scroll", false)
        val maxScrolls = arguments.optInt("max_scrolls", 3).coerceIn(0, 5)
        var performed = 0
        var snapshot = initial ?: captureSnapshotOnMainThread()
            ?: return toolError("NO_ACTIVE_WINDOW", "현재 화면을 읽을 수 없습니다.")
        while (true) {
            val match = findTextMatch(snapshot, query)
            if (match != null) {
                lastSnapshot.set(snapshot)
                rememberUiObservation(snapshot)
                return toolResult(
                    JSONObject().put("success", true).put("found", true)
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
            val result = mcpDeviceToolAdapter.call(
                "device_scroll",
                JSONObject()
                    .put("snapshot_id", snapshot.fingerprint.hash)
                    .put("direction", "down")
                    .put("node_id", container.id),
            )
            if (result.optBoolean("isError")) return result
            performed += 1
            Thread.sleep(FIND_NODE_SETTLE_MS)
            snapshot = captureSnapshotOnMainThread()
                ?: return toolError("NO_ACTIVE_WINDOW", "스크롤 후 화면을 읽을 수 없습니다.")
        }
        lastSnapshot.set(snapshot)
        rememberUiObservation(snapshot)
        return toolResult(
            JSONObject().put("success", true).put("found", false)
                .put("snapshot_id", snapshot.fingerprint.hash)
                .put("scrolls_performed", performed),
        )
    }

    private fun setChecked(arguments: JSONObject): JSONObject {
        if (!arguments.has("checked")) return toolError("MISSING_CHECKED", "checked 값이 필요합니다.")
        val observed = lastSnapshot.get()
            ?: return toolError("NO_OBSERVATION", "device_observe를 먼저 호출하세요.")
        if (arguments.optString("snapshot_id") != observed.fingerprint.hash) {
            return toolError("STALE_SNAPSHOT", "가장 최근 snapshot_id가 아닙니다.")
        }
        val target = observed.nodes.firstOrNull { it.id == arguments.optString("node_id") }
            ?: return toolError("NODE_NOT_FOUND", "snapshot에 해당 node_id가 없습니다.")
        val wanted = arguments.optBoolean("checked")
        val before = target.checked
            ?: return toolError("NOT_CHECKABLE", "체크 상태를 제공하는 노드가 아닙니다.")
        if (before == wanted) {
            return toolResult(
                JSONObject().put("success", true).put("changed", false)
                    .put("node_id", target.id).put("checked", wanted),
            )
        }
        val clicked = clickNode(arguments)
        if (clicked.optBoolean("isError")) return clicked
        Thread.sleep(ACTION_SETTLE_MS)
        val after = captureSnapshotOnMainThread()
            ?: return toolError("NO_ACTIVE_WINDOW", "동작 후 화면을 확인할 수 없습니다.")
        lastSnapshot.set(after)
        rememberUiObservation(after)
        val checked = after.nodes.firstOrNull { sameNode(target, it) }?.checked
        if (checked != wanted) {
            return toolError("CHECK_STATE_NOT_REACHED", "요청한 체크 상태가 적용되지 않았습니다.")
        }
        return toolResult(
            JSONObject().put("success", true).put("changed", true)
                .put("node_id", target.id).put("checked", checked)
                .put("after_snapshot_id", after.fingerprint.hash),
        )
    }

    private fun selectOption(arguments: JSONObject): JSONObject {
        val option = arguments.optString("option").trim()
        if (option.isEmpty() || option.length > 200) {
            return toolError("INVALID_OPTION", "option은 1~200자의 문자열이어야 합니다.")
        }
        val observed = lastSnapshot.get()
            ?: return toolError("NO_OBSERVATION", "device_observe를 먼저 호출하세요.")
        if (arguments.optString("snapshot_id") != observed.fingerprint.hash) {
            return toolError("STALE_SNAPSHOT", "가장 최근 snapshot_id가 아닙니다.")
        }
        val selector = observed.nodes.firstOrNull { it.id == arguments.optString("node_id") }
            ?: return toolError("NODE_NOT_FOUND", "snapshot에 해당 node_id가 없습니다.")
        if (!isSelectorNode(selector)) {
            return toolError(
                "NOT_A_SELECTOR",
                "Spinner 또는 드롭다운으로 확인된 노드만 option을 선택할 수 있습니다.",
            )
        }
        val opened = clickNode(arguments)
        if (opened.optBoolean("isError")) return opened
        Thread.sleep(ACTION_SETTLE_MS)
        val options = captureSnapshotOnMainThread()
            ?: return toolError("NO_ACTIVE_WINDOW", "선택 항목 화면을 읽을 수 없습니다.")
        val optionNode = findTextMatch(options, option, exactOnly = true)
            ?.takeIf { it.visibleToUser && it.enabled }
            ?: return toolError("OPTION_NOT_FOUND", "정확히 일치하는 선택 항목을 찾지 못했습니다: $option")
        val optionDecision = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_click_node",
                arguments = JSONObject()
                    .put("snapshot_id", options.fingerprint.hash)
                    .put("node_id", optionNode.id),
                grantedScopes = RemoteToolScope.ALL,
                currentSnapshot = options,
                observedSnapshot = options,
                locallyConfirmed = false,
            ),
        )
        if (optionDecision is RemotePolicyDecision.Deny) {
            return toolError(optionDecision.code, optionDecision.message)
        }
        lastSnapshot.set(options)
        rememberUiObservation(options)
        val selected = clickNode(
            JSONObject()
                .put("snapshot_id", options.fingerprint.hash)
                .put("node_id", optionNode.id),
        )
        if (selected.optBoolean("isError")) return selected
        val after = captureSnapshotOnMainThread() ?: options
        lastSnapshot.set(after)
        rememberUiObservation(after)
        return toolResult(
            JSONObject().put("success", true)
                .put("node_id", arguments.optString("node_id"))
                .put("option", option)
                .put("after_snapshot_id", after.fingerprint.hash),
        )
    }

    private fun findTextMatch(
        snapshot: UiSnapshot,
        query: String,
        exactOnly: Boolean = false,
    ): UiNode? {
        val wanted = query.trim().lowercase()
        return snapshot.nodes.asSequence()
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

    private fun sameNode(expected: UiNode, actual: UiNode): Boolean =
        expected.bounds == actual.bounds &&
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

    private fun fieldHints(): String =
        SecretVault.FIELDS.entries.joinToString(", ") { (key, hint) -> "$key($hint)" }

    /**
     * 금고 값으로 입력창 하나를 채운다.
     *
     * device_click_node와 같은 방식으로 스냅샷의 노드를 지목받는다. 포커스에
     * 기대면 크롬 웹 폼처럼 포커스가 안 잡히는 화면에서 모든 값이 첫 칸에
     * 덮어써진다(실측). 어느 칸에 넣는지는 분명해야 한다 — 개인정보다.
     *
     * 응답에 값을 싣지 않는다. 무엇을 넣었는지만 알려준다.
     */
    private fun fillField(arguments: JSONObject): JSONObject {
        val field = arguments.optString("field")
        if (!SecretVault.FIELDS.containsKey(field)) {
            return toolError(
                "UNKNOWN_FIELD",
                "모르는 필드입니다: $field. 가능한 값: ${SecretVault.FIELDS.keys.joinToString()}",
            )
        }

        val snapshotId = arguments.optString("snapshot_id")
        val nodeId = arguments.optString("node_id")
        val observed = lastSnapshot.get()
            ?: return toolError("NO_OBSERVATION", "device_observe를 먼저 호출하세요.")
        if (snapshotId.isBlank() || snapshotId != observed.fingerprint.hash) {
            return toolError("STALE_SNAPSHOT", "가장 최근 snapshot_id가 아닙니다.")
        }
        val target = observed.nodes.firstOrNull { it.id == nodeId }
            ?: return toolError("NODE_NOT_FOUND", "snapshot에 해당 node_id가 없습니다.")
        if (!target.editable) {
            return toolError("NOT_EDITABLE", "입력창이 아닌 노드입니다: $nodeId")
        }

        val service = AgentAccessibilityService.activeService
            ?: return toolError("ACCESSIBILITY_NOT_CONNECTED", "접근성 서비스가 연결되지 않았습니다.")

        // 계정 필드는 지금 화면의 앱 것만 쓴다. 부르는 쪽이 고르게 하면 한 앱의
        // 자격증명이 다른 앱 화면에 들어갈 수 있다.
        val result = AtomicBoolean(false)
        val missing = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            val owner = accountOwner(service)
            val value = when {
                !SecretVault.isAccountField(field) -> SecretVault.reveal(service, field)
                owner.service == null -> null
                else -> SecretVault.reveal(service, field, owner.service)
            }
            if (value == null) {
                missing.set(
                    if (SecretVault.isAccountField(field)) {
                        "이 앱(${owner.shown})의 $field 이(가) 등록돼 있지 않습니다. " +
                            "앱의 \"내 정보\" 화면에서 이 앱 계정을 먼저 등록하세요."
                    } else {
                        "$field 값이 저장돼 있지 않습니다. 앱 화면에서 먼저 등록하세요."
                    },
                )
            } else {
                result.set(service.setTextOnSnapshotNode(target, observed.packageName, value))
            }
            latch.countDown()
        }
        latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        missing.get()?.let { message -> return toolError("FIELD_NOT_SET", message) }
        if (!result.get()) {
            return toolError("FILL_FAILED", "$nodeId 에 값을 넣지 못했습니다.")
        }

        captureSnapshotOnMainThread()?.let(lastSnapshot::set)
        return toolResult(
            JSONObject()
                .put("success", true)
                .put("field", field)
                .put("node_id", nodeId)
                .put("message", "$field 값을 입력했습니다."),
        )
    }

    /** 관찰할 때 본 그 항목이 지금도 같은 자리에 같은 내용으로 있는지. */
    private fun isStillThere(target: UiNode, current: UiSnapshot): Boolean {
        val now = current.nodes.firstOrNull { node -> node.id == target.id } ?: return false
        return now.text == target.text &&
            now.contentDescription == target.contentDescription &&
            now.bounds == target.bounds
    }

    private fun clickNode(arguments: JSONObject): JSONObject {
        val snapshotId = arguments.optString("snapshot_id")
        val nodeId = arguments.optString("node_id")
        val observed = lastSnapshot.get()
            ?: return toolError("NO_OBSERVATION", "device_observe를 먼저 호출하세요.")
        if (snapshotId.isBlank() || snapshotId != observed.fingerprint.hash) {
            return toolError("STALE_SNAPSHOT", "가장 최근 snapshot_id가 아닙니다.")
        }

        val target = observed.nodes.firstOrNull { it.id == nodeId }
            ?: return toolError("NODE_NOT_FOUND", "snapshot에 해당 node_id가 없습니다.")
        if (!target.enabled) {
            return toolError("NODE_DISABLED", "비활성 노드는 클릭할 수 없습니다.")
        }

        val current = captureSnapshotOnMainThread()
            ?: return toolError("NO_ACTIVE_WINDOW", "현재 UI 트리를 읽을 수 없습니다.")
        // 화면 전체가 그대로인지가 아니라, 누르려는 그 항목이 그대로인지를 본다.
        // 전체 지문으로 보면 시계나 타이머처럼 매초 바뀌는 값 하나 때문에 어떤
        // 클릭도 통과하지 못한다(실측: 타이머 화면의 "취소"를 누를 수 없어 좌표
        // 탭으로 우회해야 했다). 시계가 있는 화면 전반이 그렇다.
        //
        // 이 검사가 막으려던 건 "관찰한 뒤 화면이 넘어가서 엉뚱한 걸 누르는 것"인데,
        // 그건 누를 항목의 위치와 내용이 그대로인지만 봐도 알 수 있다.
        if (current.packageName != observed.packageName || !isStillThere(target, current)) {
            lastSnapshot.set(current)
            return toolError(
                "SCREEN_CHANGED",
                "누르려던 항목이 사라지거나 자리를 옮겨 클릭을 거부했습니다.",
            )
        }

        val action = clickSnapshotNodeOnMainThread(target, observed.packageName)
            ?: return toolError("ACCESSIBILITY_NOT_CONNECTED", "접근성 서비스가 연결되지 않았습니다.")
        var success = action.success
        var method = "node_click"
        if (
            !success &&
            target.visibleToUser &&
            target.bounds.width() > 0 &&
            target.bounds.height() > 0
        ) {
            success = tapOnMainThread(
                target.bounds.exactCenterX(),
                target.bounds.exactCenterY(),
            )
            if (success) method = "coordinate_tap"
        }
        var after = waitForScreenChange(current)
        var changed = after?.fingerprint?.hash != current.fingerprint.hash

        // Some WebView buttons report a successful ACTION_CLICK while only
        // moving accessibility focus. This is reproducible on the Megabox seat
        // map and leaves the seat unselected. Retry only an unchanged, visible
        // WebView button so native controls are not accidentally activated
        // twice.
        if (
            success &&
            !changed &&
            shouldRetryUnchangedWebButton(target, current) &&
            target.bounds.width() > 0 &&
            target.bounds.height() > 0
        ) {
            val tapped = tapOnMainThread(
                target.bounds.exactCenterX(),
                target.bounds.exactCenterY(),
            )
            if (tapped) {
                method = "node_click_then_verified_coordinate_retry"
                after = waitForScreenChange(current)
                changed = after?.fingerprint?.hash != current.fingerprint.hash
            }
        }
        if (after != null) lastSnapshot.set(after)

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

    private fun shouldRetryUnchangedWebButton(
        target: UiNode,
        snapshot: UiSnapshot,
    ): Boolean {
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

    private fun tapOnMainThread(x: Float, y: Float): Boolean {
        val service = AgentAccessibilityService.activeService ?: return false
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            service.tap(x, y) { completed ->
                success.set(completed)
                latch.countDown()
            }
        }
        latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return success.get()
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
        val snapshot = captureSnapshotOnMainThread()
        return JSONObject()
            .put("success", true)
            .put("server", SERVER_NAME)
            .put("server_version", SERVER_VERSION)
            .put("accessibility_connected", AgentAccessibilityService.activeService != null)
            .put("foreground_package", snapshot?.packageName.orEmpty())
            .put("agent_running", LocalChatRepository.state.value.generating)
            .put("timestamp_ms", System.currentTimeMillis())
    }

    private fun snapshotJson(snapshot: UiSnapshot, maxNodes: Int): JSONObject {
        // Keep the complete snapshot in lastSnapshot so node ids and stale
        // checks remain stable. Only the payload sent to an agent is filtered.
        val meaningfulNodes = snapshot.nodes.filter { node -> node.isMeaningfulForAgent() }
        val nodes = JSONArray()
        meaningfulNodes.take(maxNodes).forEach { node ->
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
                    .put("input_type", node.inputType)
                    .put("visible_to_user", node.visibleToUser)
                    .put(
                        "range",
                        node.range?.let { span ->
                            JSONObject()
                                .put("min", span.min)
                                .put("max", span.max)
                                .put("current", span.current)
                        } ?: JSONObject.NULL,
                    )
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
            .put("meaningful_node_count", meaningfulNodes.size)
            .put("returned_node_count", nodes.length())
            .put("truncated", meaningfulNodes.size > nodes.length())
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
        private const val FIND_NODE_SETTLE_MS = 400L
        private const val ACTION_SETTLE_MS = 350L
    }
}
