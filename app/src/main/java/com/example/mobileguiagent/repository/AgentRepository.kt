package com.example.mobileguiagent.repository

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.model.PocMetric
import com.example.mobileguiagent.model.UiSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

sealed interface AgentAction {
    data class OpenApp(val packageName: String) : AgentAction
    data class TapText(val text: String) : AgentAction
    data class InputText(val text: String) : AgentAction
    data object OpenWifi : AgentAction
    data object Back : AgentAction
    data object Home : AgentAction
    data object DumpTree : AgentAction
    data object ScrollDown : AgentAction
}

data class AgentUiState(
    val serviceConnected: Boolean = false,
    val running: Boolean = false,
    val foregroundPackage: String = "",
    val status: String = "접근성 서비스를 활성화하세요.",
    val logs: List<String> = emptyList(),
    val lastNodeCount: Int = 0,
    val lastFingerprint: String = "",
    val metricsFilePath: String = "",
)

object RuleBasedCommandParser {
    fun parse(rawCommand: String): AgentAction? {
        val command = rawCommand.trim().lowercase()
        return when {
            command == "설정 열어" || command == "설정 앱 열어" ->
                AgentAction.OpenApp(SETTINGS_PACKAGE)

            "와이파이" in command || "wi-fi" in command || "wifi" in command ->
                AgentAction.OpenWifi

            command == "뒤로 가" || command == "뒤로" ->
                AgentAction.Back

            command == "홈으로 가" || command == "홈" ->
                AgentAction.Home

            command == "ui 트리" || command == "트리 출력" || command == "화면 읽어" ->
                AgentAction.DumpTree

            command == "아래로 스크롤" || command == "스크롤" ->
                AgentAction.ScrollDown

            command.startsWith("입력 ") ->
                AgentAction.InputText(rawCommand.trim().removePrefix("입력 ").trim())

            command.startsWith("눌러 ") ->
                AgentAction.TapText(rawCommand.trim().removePrefix("눌러 ").trim())

            else -> null
        }
    }
}

object AgentRepository {
    private const val TAG = "AgentRepository"
    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val MAX_LOG_LINES = 80
    private const val SCREEN_WAIT_TIMEOUT_MS = 6_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = mutableState.asStateFlow()
    private var runningJob: Job? = null

    fun syncServiceConnection() {
        onServiceConnectionChanged(AgentAccessibilityService.activeService != null)
    }

    fun onServiceConnectionChanged(connected: Boolean) {
        if (mutableState.value.serviceConnected == connected) return
        mutableState.update {
            it.copy(
                serviceConnected = connected,
                status = if (connected) {
                    "접근성 서비스 연결됨"
                } else {
                    "접근성 서비스 연결 끊김"
                },
            )
        }
        appendLog(if (connected) "서비스 연결 완료" else "서비스 연결 해제")
    }

    fun onAccessibilityEvent(
        packageName: String,
        className: String,
        eventType: Int,
    ) {
        if (packageName.isNotBlank()) {
            mutableState.update { it.copy(foregroundPackage = packageName) }
        }
        if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            appendLog("화면 전환: $packageName / $className")
        }
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun runCommand(context: Context, rawCommand: String) {
        val action = RuleBasedCommandParser.parse(rawCommand)
        if (action == null) {
            setStatus("지원하지 않는 명령입니다: $rawCommand")
            appendLog("명령 해석 실패: $rawCommand")
            return
        }
        runAction(context.applicationContext, action)
    }

    fun runAction(context: Context, action: AgentAction) {
        if (runningJob?.isActive == true) {
            appendLog("실행 중인 작업이 있어 새 명령을 무시했습니다.")
            return
        }
        runningJob = scope.launch {
            mutableState.update { it.copy(running = true) }
            try {
                when (action) {
                    is AgentAction.OpenApp -> runOpenApp(context, action.packageName)
                    is AgentAction.TapText -> runTapText(context, action.text)
                    is AgentAction.InputText -> runInputText(context, action.text)
                    AgentAction.OpenWifi -> runWifiScenario(context)
                    AgentAction.Back -> runGlobalAction(context, AccessibilityService.GLOBAL_ACTION_BACK, "뒤로 가기")
                    AgentAction.Home -> runGlobalAction(context, AccessibilityService.GLOBAL_ACTION_HOME, "홈으로 가기")
                    AgentAction.DumpTree -> runDumpTree()
                    AgentAction.ScrollDown -> runScrollDown(context)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Action failed", error)
                setStatus("실행 오류: ${error.message ?: error::class.java.simpleName}")
                appendLog("예외 발생: ${error.message}")
            } finally {
                mutableState.update { it.copy(running = false) }
            }
        }
    }

    private suspend fun runWifiScenario(context: Context) {
        val startedAt = System.currentTimeMillis()
        var steps = 0
        var nodeActionUsed = false
        var screenChanged = false
        var failureCode: String? = null

        val service = requireService() ?: run {
            failureCode = "ACCESSIBILITY_NOT_CONNECTED"
            recordMetric(
                context,
                PocMetric(
                    task = "Wi-Fi 메뉴 열기",
                    success = false,
                    steps = steps,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    nodeActionUsed = false,
                    coordinateActionUsed = false,
                    screenChanged = false,
                    failureCode = failureCode,
                ),
            )
            return
        }

        setStatus("설정 앱을 여는 중…")
        appendLog("PoC 시작: 설정 → Wi-Fi")
        if (!openSettings(context)) {
            failureCode = "SETTINGS_LAUNCH_FAILED"
            finishWifiMetric(context, startedAt, steps, nodeActionUsed, screenChanged, failureCode)
            return
        }
        steps += 1

        val settingsSnapshot = waitForSnapshot { snapshot ->
            snapshot.packageName == SETTINGS_PACKAGE && isReadySettingsSnapshot(snapshot)
        }
        if (settingsSnapshot == null) {
            failureCode = "SETTINGS_NOT_OBSERVED"
            setStatus("설정 앱의 UI 트리를 확인하지 못했습니다.")
            finishWifiMetric(context, startedAt, steps, nodeActionUsed, screenChanged, failureCode)
            return
        }
        updateSnapshotState(settingsSnapshot)
        appendLog("설정 UI 트리 수집: ${settingsSnapshot.nodes.size}개 노드")

        if (looksLikeWifiScreen(settingsSnapshot)) {
            setStatus("성공: 이미 Wi-Fi 관련 설정 화면입니다.")
            appendLog("검증: 시작 화면이 이미 Wi-Fi 목표 상태")
            finishWifiMetric(
                context = context,
                startedAt = startedAt,
                steps = steps,
                nodeActionUsed = false,
                screenChanged = false,
                failureCode = null,
            )
            return
        }

        val directResult = service.clickText(DIRECT_WIFI_LABELS, exactOnly = true)
        val firstClickWasDirect = directResult.success
        val firstResult = if (firstClickWasDirect) {
            directResult
        } else {
            waitForTextClick(
                service = service,
                candidates = SETTINGS_CATEGORY_LABELS,
                exactOnly = false,
                timeoutMs = 3_000L,
            )
        }

        if (!firstResult.success) {
            failureCode = "TARGET_NODE_NOT_FOUND"
            setStatus("Wi-Fi 또는 네트워크 메뉴를 찾지 못했습니다.")
            appendLog("대상 노드 없음: Wi-Fi/네트워크/연결")
            service.dumpTreeToLog()
            finishWifiMetric(context, startedAt, steps, nodeActionUsed, screenChanged, failureCode)
            return
        }

        nodeActionUsed = true
        steps += 1
        appendLog(
            "노드 클릭: ${firstResult.matchedText}" +
                if (firstResult.usedClickableAncestor) " (클릭 가능한 부모 사용)" else "",
        )

        val afterFirstClick = waitForReadyScreenChange(settingsSnapshot)
        if (afterFirstClick == null) {
            failureCode = "SCREEN_DID_NOT_CHANGE"
            setStatus("클릭은 호출됐지만 화면 변화를 확인하지 못했습니다.")
            finishWifiMetric(context, startedAt, steps, nodeActionUsed, screenChanged, failureCode)
            return
        }
        screenChanged = true
        updateSnapshotState(afterFirstClick)

        var finalSnapshot = afterFirstClick
        if (!firstClickWasDirect) {
            val wifiResult = waitForTextClick(
                service = service,
                candidates = DIRECT_WIFI_LABELS,
                exactOnly = false,
                timeoutMs = 4_000L,
            )
            if (wifiResult.success) {
                nodeActionUsed = true
                steps += 1
                appendLog(
                    "Wi-Fi 노드 클릭: ${wifiResult.matchedText}" +
                        if (wifiResult.usedClickableAncestor) " (클릭 가능한 부모 사용)" else "",
                )
                waitForReadyScreenChange(afterFirstClick)?.let {
                    finalSnapshot = it
                    screenChanged = true
                    updateSnapshotState(it)
                }
            } else if (!looksLikeWifiScreen(afterFirstClick)) {
                failureCode = "WIFI_NODE_NOT_FOUND_AFTER_CATEGORY"
                service.dumpTreeToLog()
            }
        }

        val success = failureCode == null && looksLikeWifiScreen(finalSnapshot)
        if (!success && failureCode == null) {
            failureCode = "RESULT_VERIFICATION_FAILED"
        }
        setStatus(
            if (success) {
                "성공: Wi-Fi 관련 설정 화면을 확인했습니다."
            } else {
                "실패: 화면은 바뀌었지만 Wi-Fi 화면인지 확인하지 못했습니다."
            },
        )
        appendLog(
            "검증: package=${finalSnapshot.packageName}, " +
                "nodes=${finalSnapshot.nodes.size}, success=$success",
        )
        finishWifiMetric(
            context = context,
            startedAt = startedAt,
            steps = steps,
            nodeActionUsed = nodeActionUsed,
            screenChanged = screenChanged,
            failureCode = failureCode,
        )
    }

    private suspend fun runOpenApp(context: Context, packageName: String) {
        val startedAt = System.currentTimeMillis()
        val opened = if (packageName == SETTINGS_PACKAGE) {
            openSettings(context)
        } else {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchIntent == null) false else {
                context.startActivity(launchIntent)
                true
            }
        }
        val observed = if (opened && AgentAccessibilityService.activeService != null) {
            waitForSnapshot { it.packageName == packageName }
        } else {
            null
        }
        val success = opened && (observed != null || AgentAccessibilityService.activeService == null)
        setStatus(if (success) "앱 실행 성공: $packageName" else "앱 실행 확인 실패: $packageName")
        appendLog("앱 실행: $packageName, success=$success")
        recordMetric(
            context,
            PocMetric(
                task = "앱 실행: $packageName",
                success = success,
                steps = 1,
                latencyMs = System.currentTimeMillis() - startedAt,
                nodeActionUsed = false,
                coordinateActionUsed = false,
                screenChanged = observed != null,
                failureCode = if (success) null else "APP_LAUNCH_FAILED",
            ),
        )
    }

    private fun runTapText(context: Context, text: String) {
        val startedAt = System.currentTimeMillis()
        val before = AgentAccessibilityService.activeService?.captureSnapshot()
        val result = AgentAccessibilityService.activeService?.clickText(listOf(text))
        val success = result?.success == true
        setStatus(if (success) "텍스트 노드를 눌렀습니다: $text" else "텍스트 노드를 찾지 못했습니다: $text")
        appendLog("텍스트 클릭: $text, success=$success")
        recordMetric(
            context,
            PocMetric(
                task = "텍스트 클릭: $text",
                success = success,
                steps = 1,
                latencyMs = System.currentTimeMillis() - startedAt,
                nodeActionUsed = true,
                coordinateActionUsed = false,
                screenChanged = before?.fingerprint?.hash !=
                    AgentAccessibilityService.activeService?.captureSnapshot()?.fingerprint?.hash,
                failureCode = if (success) null else "TARGET_NODE_NOT_FOUND",
            ),
        )
    }

    private fun runInputText(context: Context, text: String) {
        val startedAt = System.currentTimeMillis()
        val success = AgentAccessibilityService.activeService?.setTextOnFirstEditable(text) == true
        setStatus(if (success) "텍스트 입력 성공" else "활성 입력창을 찾지 못했습니다.")
        appendLog("텍스트 입력: success=$success, length=${text.length}")
        recordMetric(
            context,
            PocMetric(
                task = "텍스트 입력",
                success = success,
                steps = 1,
                latencyMs = System.currentTimeMillis() - startedAt,
                nodeActionUsed = true,
                coordinateActionUsed = false,
                screenChanged = false,
                failureCode = if (success) null else "EDITABLE_NODE_NOT_FOUND",
            ),
        )
    }

    private fun runGlobalAction(context: Context, action: Int, taskName: String) {
        val startedAt = System.currentTimeMillis()
        val success = AgentAccessibilityService.activeService?.performGlobalAction(action) == true
        setStatus(if (success) "$taskName 실행" else "$taskName 실패: 접근성 서비스를 확인하세요.")
        appendLog("$taskName: success=$success")
        recordMetric(
            context,
            PocMetric(
                task = taskName,
                success = success,
                steps = 1,
                latencyMs = System.currentTimeMillis() - startedAt,
                nodeActionUsed = false,
                coordinateActionUsed = false,
                screenChanged = success,
                failureCode = if (success) null else "GLOBAL_ACTION_FAILED",
            ),
        )
    }

    private fun runDumpTree() {
        val snapshot = AgentAccessibilityService.activeService?.dumpTreeToLog()
        if (snapshot == null) {
            setStatus("UI 트리를 읽지 못했습니다.")
            appendLog("UI 트리 수집 실패")
            return
        }
        updateSnapshotState(snapshot)
        setStatus("UI 트리 ${snapshot.nodes.size}개 노드를 Logcat에 출력했습니다.")
        appendLog("UI 트리 출력: ${snapshot.packageName}, ${snapshot.nodes.size}개")
    }

    private fun runScrollDown(context: Context) {
        val service = requireService() ?: return
        val width = resourcesDisplayWidth(service)
        val height = resourcesDisplayHeight(service)
        val startedAt = System.currentTimeMillis()
        service.swipe(
            startX = width * 0.5f,
            startY = height * 0.75f,
            endX = width * 0.5f,
            endY = height * 0.25f,
        ) { success ->
            scope.launch {
                setStatus(if (success) "아래로 스크롤했습니다." else "스크롤 제스처가 취소됐습니다.")
                appendLog("좌표 스와이프: success=$success")
                recordMetric(
                    context,
                    PocMetric(
                        task = "아래로 스크롤",
                        success = success,
                        steps = 1,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        nodeActionUsed = false,
                        coordinateActionUsed = true,
                        screenChanged = success,
                        failureCode = if (success) null else "GESTURE_CANCELLED",
                    ),
                )
            }
        }
    }

    private fun requireService(): AgentAccessibilityService? {
        val service = AgentAccessibilityService.activeService
        if (service == null) {
            setStatus("접근성 서비스가 연결되지 않았습니다.")
            appendLog("중단: 접근성 서비스 미연결")
        }
        return service
    }

    private fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        true
    }.getOrElse {
        Log.e(TAG, "Unable to open settings", it)
        false
    }

    private suspend fun waitForSnapshot(
        timeoutMs: Long = SCREEN_WAIT_TIMEOUT_MS,
        predicate: (UiSnapshot) -> Boolean,
    ): UiSnapshot? {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            AgentAccessibilityService.activeService
                ?.captureSnapshot()
                ?.takeIf(predicate)
                ?.let { return it }
            delay(150)
        }
        return null
    }

    private suspend fun waitForReadyScreenChange(before: UiSnapshot): UiSnapshot? =
        waitForSnapshot { after ->
            after.fingerprint.hash != before.fingerprint.hash &&
                after.packageName == SETTINGS_PACKAGE &&
                isReadySettingsSnapshot(after)
        }

    private suspend fun waitForTextClick(
        service: AgentAccessibilityService,
        candidates: List<String>,
        exactOnly: Boolean,
        timeoutMs: Long,
    ): com.example.mobileguiagent.model.NodeActionResult {
        val startedAt = System.currentTimeMillis()
        var lastResult = com.example.mobileguiagent.model.NodeActionResult(success = false)
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            lastResult = service.clickText(candidates, exactOnly)
            if (lastResult.success) return lastResult
            delay(120)
        }
        return lastResult
    }

    private fun looksLikeWifiScreen(snapshot: UiSnapshot): Boolean {
        val text = snapshot.nodes
            .flatMap { node -> listOfNotNull(node.text, node.contentDescription) }
            .joinToString(" ")
            .lowercase()
            .replace('‑', '-')
        return WIFI_RESULT_MARKERS.any { marker -> marker in text }
    }

    private fun isReadySettingsSnapshot(snapshot: UiSnapshot): Boolean {
        val labeledNodeCount = snapshot.nodes.count { node ->
            !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        }
        val actionableNodeCount = snapshot.nodes.count { node ->
            node.clickable || node.scrollable || node.editable
        }
        return labeledNodeCount >= 4 && actionableNodeCount >= 2
    }

    private fun updateSnapshotState(snapshot: UiSnapshot) {
        mutableState.update {
            it.copy(
                foregroundPackage = snapshot.packageName,
                lastNodeCount = snapshot.nodes.size,
                lastFingerprint = snapshot.fingerprint.hash.take(12),
            )
        }
    }

    private fun finishWifiMetric(
        context: Context,
        startedAt: Long,
        steps: Int,
        nodeActionUsed: Boolean,
        screenChanged: Boolean,
        failureCode: String?,
    ) {
        recordMetric(
            context,
            PocMetric(
                task = "Wi-Fi 메뉴 열기",
                success = failureCode == null,
                steps = steps,
                latencyMs = System.currentTimeMillis() - startedAt,
                nodeActionUsed = nodeActionUsed,
                coordinateActionUsed = false,
                screenChanged = screenChanged,
                failureCode = failureCode,
            ),
        )
    }

    private fun recordMetric(context: Context, metric: PocMetric) {
        val file = File(context.filesDir, "poc_metrics.jsonl")
        val json = JSONObject()
            .put("task", metric.task)
            .put("success", metric.success)
            .put("steps", metric.steps)
            .put("latency_ms", metric.latencyMs)
            .put("node_action_used", metric.nodeActionUsed)
            .put("coordinate_action_used", metric.coordinateActionUsed)
            .put("screen_changed", metric.screenChanged)
            .put("failure_code", metric.failureCode)
        runCatching {
            file.appendText(json.toString() + "\n")
            mutableState.update { it.copy(metricsFilePath = file.absolutePath) }
        }.onFailure {
            Log.e(TAG, "Failed to persist metric", it)
        }
    }

    private fun setStatus(message: String) {
        mutableState.update { it.copy(status = message) }
    }

    private fun appendLog(message: String) {
        val timestamp = java.text.SimpleDateFormat(
            "HH:mm:ss",
            java.util.Locale.getDefault(),
        ).format(java.util.Date())
        mutableState.update {
            it.copy(logs = (it.logs + "[$timestamp] $message").takeLast(MAX_LOG_LINES))
        }
    }

    private fun resourcesDisplayWidth(context: Context): Int =
        context.resources.displayMetrics.widthPixels

    private fun resourcesDisplayHeight(context: Context): Int =
        context.resources.displayMetrics.heightPixels

    private val DIRECT_WIFI_LABELS = listOf(
        "Wi-Fi",
        "Wi‑Fi",
        "와이파이",
        "인터넷",
        "Internet",
    )

    private val SETTINGS_CATEGORY_LABELS = listOf(
        "네트워크 및 인터넷",
        "연결",
        "Network & internet",
        "Network and internet",
        "Connections",
    )

    private val WIFI_RESULT_MARKERS = listOf(
        "wi-fi",
        "wifi",
        "와이파이",
        "인터넷",
        "internet",
        "네트워크",
        "network",
    )
}

private const val SETTINGS_PACKAGE = "com.android.settings"
