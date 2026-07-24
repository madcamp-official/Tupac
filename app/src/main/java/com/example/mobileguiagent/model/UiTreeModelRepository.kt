package com.example.mobileguiagent.model

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.example.minicpm_v_demo.LlamaEngine
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class UiTreeModelState(
    val modelFilePresent: Boolean = false,
    val modelReady: Boolean = false,
    val busy: Boolean = false,
    val status: String = "MiniCPM-V 4.6 모델 파일을 확인하세요.",
    val capturedPackage: String = "",
    val capturedNodeCount: Int = 0,
    val goal: String = DEFAULT_UI_GOAL,
    val rawResponse: String = "",
    val selectedNodeId: String? = null,
    val selectedNodeLabel: String? = null,
    val expectedSelection: Boolean? = null,
    val clickAttempted: Boolean = false,
    val clickSucceeded: Boolean? = null,
    val usedClickableAncestor: Boolean = false,
    val screenChanged: Boolean? = null,
    val resultVerified: Boolean? = null,
    val actionLatencyMs: Long? = null,
    val latencyMs: Long? = null,
)

/**
 * Captures a real Settings UI-tree and asks the local MiniCPM-V 4.6 model
 * which node best satisfies the goal. Valid Wi-Fi-path selections are resolved
 * against the current native tree, clicked, and verified after the transition.
 */
object UiTreeModelRepository {
    private const val TAG = "UiTreeModelRepository"
    private const val MODEL_DIR = "models/minicpm-v-4_6-instruct"
    const val MODEL_FILE_NAME = "MiniCPM-V-4_6-Q4_K_M.gguf"
    const val EXPECTED_MODEL_MD5 = "fd778481dd56b6036dd8f9cf7c1519cf"
    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val CAPTURE_TIMEOUT_MS = 12_000L
    private const val ACTION_TIMEOUT_MS = 7_000L
    private const val CACHED_SNAPSHOT_MAX_AGE_MS = 10 * 60 * 1_000L
    private const val MAX_PROMPT_NODES = 80

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(UiTreeModelState())
    val state: StateFlow<UiTreeModelState> = mutableState.asStateFlow()

    private var job: Job? = null
    fun modelFile(context: Context): File =
        File(context.filesDir, "$MODEL_DIR/$MODEL_FILE_NAME")

    fun refresh(context: Context) {
        val file = modelFile(context.applicationContext)
        mutableState.update {
            it.copy(
                modelFilePresent = file.isFile,
                status = when {
                    it.modelReady -> it.status
                    file.isFile -> "4.6 모델 파일 준비됨 · UI-tree 테스트를 실행하세요."
                    else -> "4.6 모델 파일이 아직 앱 저장소에 없습니다."
                },
            )
        }
    }

    fun updateGoal(goal: String) {
        mutableState.update { it.copy(goal = goal) }
    }

    fun runSettingsTreeTest(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            mutableState.update {
                it.copy(
                    busy = true,
                    rawResponse = "",
                    selectedNodeId = null,
                    selectedNodeLabel = null,
                    expectedSelection = null,
                    clickAttempted = false,
                    clickSucceeded = null,
                    usedClickableAncestor = false,
                    screenChanged = null,
                    resultVerified = null,
                    actionLatencyMs = null,
                    latencyMs = null,
                )
            }
            val startedAt = System.currentTimeMillis()
            try {
                val modelFile = modelFile(appContext)
                require(modelFile.isFile) {
                    "4.6 모델 파일이 없습니다: ${modelFile.absolutePath}"
                }
                val snapshot = captureSettingsSnapshot(appContext)
                mutableState.update {
                    it.copy(
                        capturedPackage = snapshot.packageName,
                        capturedNodeCount = snapshot.nodes.size,
                        status = "UI-tree 캡처 완료 · 4.6 모델 로딩 중…",
                    )
                }
                returnToAgentApp(appContext)
                delay(500)

                val engine = LlamaEngine.getInstance(appContext)
                withContext(Dispatchers.IO) {
                    engine.loadModel(modelFile)
                }
                mutableState.update {
                    it.copy(
                        modelReady = true,
                        status = "4.6 모델이 UI-tree를 분석 중…",
                    )
                }

                val goal = mutableState.value.goal.trim().ifBlank { DEFAULT_UI_GOAL }
                val response = engine.generate(
                    systemPrompt = SYSTEM_PROMPT,
                    userPrompt = buildPrompt(goal, snapshot),
                    predictLength = MODEL_RESPONSE_TOKEN_LIMIT,
                )
                val selectedNodeId = parseFinalNodeId(response)
                val selectedNode = snapshot.nodes.firstOrNull { it.id == selectedNodeId }
                val label = selectedNode?.bestLabel()
                val expected = label?.let(::isExpectedWifiEntry)

                if (selectedNode == null || expected != true) {
                    mutableState.update {
                        it.copy(
                            busy = false,
                            modelReady = true,
                            status = if (selectedNode == null) {
                                "응답은 받았지만 유효한 node_id를 찾지 못했습니다."
                            } else {
                                "안전 중단: Wi-Fi 예상 경로가 아닌 노드는 클릭하지 않습니다."
                            },
                            rawResponse = response,
                            selectedNodeId = selectedNodeId,
                            selectedNodeLabel = label,
                            expectedSelection = expected,
                            latencyMs = System.currentTimeMillis() - startedAt,
                        )
                    }
                    Log.i(
                        TAG,
                        "UI-tree grounding result: id=$selectedNodeId label=$label expected=$expected",
                    )
                    return@launch
                }

                mutableState.update {
                    it.copy(
                        modelReady = true,
                        status = "모델 선택 확인 · 현재 설정 트리에서 자동 클릭하는 중…",
                        rawResponse = response,
                        selectedNodeId = selectedNodeId,
                        selectedNodeLabel = label,
                        expectedSelection = true,
                        clickAttempted = true,
                    )
                }
                val actionResult = executeSelectedNode(
                    context = appContext,
                    selectedNode = selectedNode,
                )
                mutableState.update {
                    it.copy(
                        busy = false,
                        modelReady = true,
                        status = when {
                            !actionResult.clickSucceeded ->
                                "실패: 모델 선택 노드를 현재 화면에서 클릭하지 못했습니다."
                            actionResult.verified ->
                                "성공: 모델이 고른 노드를 자동 클릭하고 결과 화면을 확인했습니다."
                            actionResult.screenChanged ->
                                "부분 성공: 자동 클릭과 화면 전환은 됐지만 결과 검증에 실패했습니다."
                            else ->
                                "실패: 클릭은 호출됐지만 화면 변화를 확인하지 못했습니다."
                        },
                        clickSucceeded = actionResult.clickSucceeded,
                        usedClickableAncestor = actionResult.usedClickableAncestor,
                        screenChanged = actionResult.screenChanged,
                        resultVerified = actionResult.verified,
                        actionLatencyMs = actionResult.latencyMs,
                        latencyMs = System.currentTimeMillis() - startedAt,
                    )
                }
                Log.i(
                    TAG,
                    "UI-tree action result: id=$selectedNodeId label=$label " +
                        "click=${actionResult.clickSucceeded} " +
                        "ancestor=${actionResult.usedClickableAncestor} " +
                        "changed=${actionResult.screenChanged} verified=${actionResult.verified}",
                )
            } catch (error: Throwable) {
                Log.e(TAG, "UI-tree model test failed", error)
                returnToAgentApp(appContext)
                mutableState.update {
                    it.copy(
                        busy = false,
                        status = "모델 테스트 실패: ${error.message ?: error::class.java.simpleName}",
                        latencyMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    private suspend fun executeSelectedNode(
        context: Context,
        selectedNode: UiNode,
    ): ModelClickOutcome {
        val startedAt = System.currentTimeMillis()
        val observationStartedAt = startedAt
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )

        val before = waitForLiveSettingsSnapshot(
            context = context,
            timeoutMs = ACTION_TIMEOUT_MS,
            capturedAfterMillis = observationStartedAt,
        ) { snapshot ->
            snapshot.nodes.any { node -> node.matchesStableTarget(selectedNode) }
        } ?: return ModelClickOutcome(
            clickSucceeded = false,
            usedClickableAncestor = false,
            screenChanged = false,
            verified = false,
            latencyMs = System.currentTimeMillis() - startedAt,
        )

        val clickResult = AgentAccessibilityService.activeService
            ?.clickSnapshotNode(
                target = selectedNode,
                expectedPackage = SETTINGS_PACKAGE,
            )
            ?: return ModelClickOutcome(
                clickSucceeded = false,
                usedClickableAncestor = false,
                screenChanged = false,
                verified = false,
                latencyMs = System.currentTimeMillis() - startedAt,
            )

        if (!clickResult.success) {
            return ModelClickOutcome(
                clickSucceeded = false,
                usedClickableAncestor = clickResult.usedClickableAncestor,
                screenChanged = false,
                verified = false,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
        }

        val changedSnapshot = waitForLiveSettingsSnapshot(
            context = context,
            timeoutMs = ACTION_TIMEOUT_MS,
            capturedAfterMillis = System.currentTimeMillis(),
        ) { snapshot ->
            snapshot.fingerprint.hash != before.fingerprint.hash
        }
        val verifiedSnapshot = when {
            changedSnapshot?.let(::looksLikeConnectionsScreen) == true -> changedSnapshot
            else -> waitForLiveSettingsSnapshot(
                context = context,
                timeoutMs = ACTION_TIMEOUT_MS,
                capturedAfterMillis = System.currentTimeMillis(),
            ) { snapshot ->
                snapshot.fingerprint.hash != before.fingerprint.hash &&
                    looksLikeConnectionsScreen(snapshot)
            }
        }
        val screenChanged = changedSnapshot != null || verifiedSnapshot != null
        return ModelClickOutcome(
            clickSucceeded = true,
            usedClickableAncestor = clickResult.usedClickableAncestor,
            screenChanged = screenChanged,
            verified = verifiedSnapshot != null,
            latencyMs = System.currentTimeMillis() - startedAt,
        )
    }

    private suspend fun waitForLiveSettingsSnapshot(
        context: Context,
        timeoutMs: Long,
        capturedAfterMillis: Long,
        predicate: (UiSnapshot) -> Boolean,
    ): UiSnapshot? {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            AgentAccessibilityService.activeService
                ?.captureSnapshot()
                ?.takeIf { snapshot ->
                    snapshot.packageName == SETTINGS_PACKAGE && predicate(snapshot)
                }
                ?.let { return it }
            UiSnapshotStore.read(context)
                ?.takeIf { snapshot ->
                    snapshot.packageName == SETTINGS_PACKAGE &&
                        snapshot.capturedAtMillis >= capturedAfterMillis &&
                        predicate(snapshot)
                }
                ?.let { return it }
            delay(150)
        }
        return null
    }

    private suspend fun captureSettingsSnapshot(context: Context): UiSnapshot {
        mutableState.update { it.copy(status = "설정 앱을 열고 UI-tree를 캡처하는 중…") }
        UiSnapshotStore.read(context)
            ?.takeIf { snapshot ->
                snapshot.packageName == SETTINGS_PACKAGE &&
                    System.currentTimeMillis() - snapshot.capturedAtMillis <=
                    CACHED_SNAPSHOT_MAX_AGE_MS &&
                    isSettingsHomeSnapshot(snapshot)
            }
            ?.let { snapshot ->
                mutableState.update { it.copy(status = "저장된 최신 설정 UI-tree를 불러오는 중…") }
                return snapshot
            }

        val captureStartedAt = System.currentTimeMillis()
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )

        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < CAPTURE_TIMEOUT_MS) {
            AgentAccessibilityService.activeService
                ?.captureSnapshot()
                ?.takeIf { snapshot ->
                    snapshot.packageName == SETTINGS_PACKAGE &&
                        isSettingsHomeSnapshot(snapshot)
                }
                ?.let { return it }
            UiSnapshotStore.read(context)
                ?.takeIf { snapshot ->
                    snapshot.packageName == SETTINGS_PACKAGE &&
                        snapshot.capturedAtMillis >= captureStartedAt &&
                        isSettingsHomeSnapshot(snapshot)
                }
                ?.let { return it }
            delay(150)
        }
        error("설정 앱의 UI-tree를 제한 시간 안에 읽지 못했습니다.")
    }

    private fun returnToAgentApp(context: Context) {
        val foregroundPackage =
            AgentAccessibilityService.activeService?.captureSnapshot()?.packageName
        if (foregroundPackage == context.packageName) {
            return
        }
        if (
            foregroundPackage == SETTINGS_PACKAGE &&
            AgentAccessibilityService.activeService?.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK,
            ) == true
        ) {
            return
        }
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            ?.let(context::startActivity)
    }

    private fun buildPrompt(goal: String, snapshot: UiSnapshot): String {
        val nodes = snapshot.nodes
            .filter { node ->
                node.enabled &&
                    (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())
            }
            .take(MAX_PROMPT_NODES)
        return buildString {
            appendLine("GOAL: $goal")
            appendLine("FOREGROUND_PACKAGE: ${snapshot.packageName}")
            appendLine(
                "The list below is the labeled-node projection of the UI tree; " +
                    "choose the visible label for the next step.",
            )
            appendLine("NODES:")
            nodes.forEach { node ->
                appendLine(
                    JSONObject()
                        .put("id", node.id)
                        .put("depth", node.depth)
                        .put("text", node.text.orEmpty())
                        .put("content_description", node.contentDescription.orEmpty())
                        .put("class", node.className.orEmpty().substringAfterLast('.'))
                        .put("view_id", node.viewId.orEmpty())
                        .put("clickable", node.clickable)
                        .put("editable", node.editable)
                        .put("scrollable", node.scrollable)
                        .put("enabled", node.enabled)
                        .toString(),
                )
            }
            appendLine("/no_think")
            appendLine(
                """Output the JSON answer now. Do not explain, list alternatives, or repeat node IDs.""",
            )
        }
    }

    /**
     * Reasoning-capable models can mention several node IDs before the final
     * answer. Only a JSON object's node_id is an executable model decision.
     */
    private fun parseFinalNodeId(response: String): String? =
        JSON_NODE_ID_PATTERN.findAll(response)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)

    private fun UiNode.bestLabel(): String? =
        text?.takeIf(String::isNotBlank)
            ?: contentDescription?.takeIf(String::isNotBlank)
            ?: viewId?.takeIf(String::isNotBlank)

    private fun UiNode.matchesStableTarget(target: UiNode): Boolean {
        val labels = listOfNotNull(text, contentDescription)
            .map(::normalizeLabel)
            .toSet()
        val targetLabels = listOfNotNull(target.text, target.contentDescription)
            .map(::normalizeLabel)
            .toSet()
        return labels.intersect(targetLabels).isNotEmpty()
    }

    private fun looksLikeConnectionsScreen(snapshot: UiSnapshot): Boolean {
        val labels = snapshot.nodes
            .flatMap { node -> listOfNotNull(node.text, node.contentDescription) }
            .map(::normalizeLabel)
        val hasWifi = labels.any { label -> label == "wi-fi" || label == "wifi" }
        val hasSecondaryConnectionSetting = labels.any { label ->
            label.contains("bluetooth") ||
                label.contains("블루투스") ||
                label.contains("비행기 탑승 모드") ||
                label.contains("데이터 사용") ||
                label.contains("모바일 네트워크")
        }
        return hasWifi && hasSecondaryConnectionSetting
    }

    private fun normalizeLabel(value: String): String =
        value.lowercase()
            .replace('‑', '-')
            .replace('–', '-')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isExpectedWifiEntry(label: String): Boolean {
        val normalized = normalizeLabel(label)
        return normalized in EXPECTED_WIFI_EXACT_LABELS ||
            normalized.contains("wi-fi") ||
            normalized.contains("wifi") ||
            normalized.contains("와이파이")
    }

    private fun isSettingsHomeSnapshot(snapshot: UiSnapshot): Boolean {
        val labels = snapshot.nodes
            .flatMap { node -> listOfNotNull(node.text, node.contentDescription) }
            .map(::normalizeLabel)
            .toSet()
        val hasConnectionsEntry = labels.any { label ->
            label == "연결" || label == "connections"
        }
        val anchorCount = SETTINGS_HOME_ANCHORS.count(labels::contains)
        return hasConnectionsEntry && anchorCount >= 2
    }

    private val JSON_NODE_ID_PATTERN =
        Regex(""""node_id"\s*:\s*"(node_\d+)"""")
    private const val MODEL_RESPONSE_TOKEN_LIMIT = 768
    private val EXPECTED_WIFI_EXACT_LABELS = setOf(
        "연결",
        "connections",
        "네트워크",
        "network",
        "인터넷",
        "internet",
    )
    private val SETTINGS_HOME_ANCHORS = setOf(
        "소리 및 진동",
        "알림",
        "디스플레이",
        "배터리",
        "sounds and vibration",
        "notifications",
        "display",
        "battery",
    )

    private data class ModelClickOutcome(
        val clickSucceeded: Boolean,
        val usedClickableAncestor: Boolean,
        val screenChanged: Boolean,
        val verified: Boolean,
        val latencyMs: Long,
    )

    private const val SYSTEM_PROMPT =
        """You are a mobile UI-tree grounding model.
Select the single existing node that is the best next step for the user's goal.
Prefer a labeled enabled node whose text or description semantically matches the goal.
Never invent a node ID.
Do not reason aloud. Do not discuss alternatives. Answer immediately.
Return only one compact JSON object using this schema:
{"node_id":"node_0","label":"visible label","reason":"short reason"}"""
}

const val DEFAULT_UI_GOAL = "Wi-Fi 설정 화면으로 이동하기 위해 지금 눌러야 할 항목을 선택해"
