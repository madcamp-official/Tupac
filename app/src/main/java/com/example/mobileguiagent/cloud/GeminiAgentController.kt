package com.example.mobileguiagent.cloud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.mobileguiagent.agent.PrivacyRoute
import com.example.mobileguiagent.agent.PrivateScreenActionPolicy
import com.example.mobileguiagent.agent.ScreenPrivacyRouter
import com.example.mobileguiagent.agent.ShoppingActionPolicy
import com.example.mobileguiagent.credentials.LocalCredentialRepository
import com.example.mobileguiagent.credentials.PublicCredentialDescriptor
import com.example.mobileguiagent.device.CaptureScreenDeviceTool
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolExecutor
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.FinishDeviceTool
import com.example.mobileguiagent.device.FillSecretDeviceTool
import com.example.mobileguiagent.device.GoBackDeviceTool
import com.example.mobileguiagent.device.GoHomeDeviceTool
import com.example.mobileguiagent.device.ObserveUiDeviceTool
import com.example.mobileguiagent.device.SetTextDeviceTool
import com.example.mobileguiagent.device.SubmitTextDeviceTool
import com.example.mobileguiagent.device.SwipeDeviceTool
import com.example.mobileguiagent.device.TapDeviceTool
import com.example.mobileguiagent.device.TapNodeDeviceTool
import com.example.mobileguiagent.device.WaitDeviceTool
import com.example.mobileguiagent.model.AgentTraceEvent
import com.example.mobileguiagent.model.LocalPrivacyPlan
import com.example.mobileguiagent.model.LocalPrivacyPlanner
import com.example.mobileguiagent.model.LocalAgentOutcome
import com.example.mobileguiagent.ocr.LocalOcrScreenAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cloud vision planner over the same local Device Tool registry used by the
 * on-device model and MCP. Screenshots leave the phone only for the explicit
 * Gemini runtime; Gemini never talks to AccessibilityService directly.
 */
class GeminiAgentController(
    private val deviceTools: DeviceToolExecutor,
    private val localPrivacyPlanner: LocalPrivacyPlanner,
    private val apiClient: GeminiApiClient = GeminiApiClient(),
) {
    private val ocrAnalyzer = LocalOcrScreenAnalyzer(deviceTools)

    suspend fun run(
        context: Context,
        apiKey: String,
        model: GeminiModel,
        goal: String,
        @Suppress("UNUSED_PARAMETER")
        controllerVisible: Boolean,
        onProgress: (String) -> Unit,
        onTrace: (AgentTraceEvent) -> Unit,
    ): LocalAgentOutcome {
        require(apiKey.isNotBlank()) {
            "Gemini API 키가 없습니다. 프로젝트 local.properties에 " +
                "GEMINI_API_KEY=... 형식으로 추가하세요."
        }
        // Do not disclose the entire opaque-key catalog. Build a bounded local
        // shortlist from safe aliases, then let Gemini select only within it.
        val normalizedGoal = goal.lowercase()
        val publicCatalog = LocalCredentialRepository
            .publicCatalog(context)
            .sortedByDescending { resource ->
                val alias = resource.scopeAlias.lowercase()
                val terms = alias.split(Regex("""[^\p{L}\p{N}]+"""))
                    .filter { it.length >= 2 }
                (if (alias in normalizedGoal) 10_000 else 0) +
                    terms.count(normalizedGoal::contains) * 100 +
                    if (AUTH_GOAL_TERMS.any(normalizedGoal::contains)) 10 else 0
            }
            .take(MAX_CLOUD_RESOURCE_CANDIDATES)
        val selectedResourceIds = if (publicCatalog.isEmpty()) {
            emptySet()
        } else {
            onProgress("${model.displayName} · 필요한 로컬 보안 리소스 선택 중…")
            runCatching {
                apiClient.selectCredentialResources(
                    apiKey = apiKey,
                    model = model,
                    goal = goal,
                    resources = publicCatalog,
                )
            }.onFailure { error ->
                Log.w(TAG, "credential_resource_selection_failed", error)
            }.getOrDefault(emptySet())
        }
        val selectedResources: List<PublicCredentialDescriptor> =
            publicCatalog.filter { it.id in selectedResourceIds }
        if (selectedResources.isNotEmpty()) {
            Log.i(
                TAG,
                "credential_resources_selected ids=" +
                    selectedResources.joinToString { it.id },
            )
        }
        val recentActions = mutableListOf<String>()
        var completedActions = 0
        var previousSignature: String? = null
        var repeatedSignatureCount = 0
        var previousPrivacyRoute: PrivacyRoute? = null
        var pendingVerification: PendingVerification? = null
        var repeatedUnchangedAction: String? = null
        var repeatedUnchangedCount = 0

        for (plannerStep in 1..MAX_STEPS) {
            val displayedStep = completedActions + 1
            onProgress(
                "${model.displayName} · 화면 판단 $plannerStep/$MAX_STEPS",
            )

            val observationCall = DeviceToolCall(ObserveUiDeviceTool.NAME)
            var observationResult = execute(observationCall)
            onTrace(
                AgentTraceEvent.ToolResult(
                    step = displayedStep,
                    call = observationCall,
                    result = observationResult,
                    automatic = true,
                ),
            )
            var transientObservationRetries = 0
            while (
                observationResult is DeviceToolResult.Error &&
                observationResult.code == TRANSIENT_UI_TREE_ERROR &&
                transientObservationRetries < MAX_TRANSIENT_OBSERVATION_RETRIES
            ) {
                transientObservationRetries += 1
                onProgress(
                    "${model.displayName} · 앱 화면 로딩 대기 " +
                        "$transientObservationRetries/$MAX_TRANSIENT_OBSERVATION_RETRIES",
                )
                delay(TRANSIENT_OBSERVATION_RETRY_MS)
                observationResult = execute(observationCall)
                onTrace(
                    AgentTraceEvent.ToolResult(
                        step = displayedStep,
                        call = observationCall,
                        result = observationResult,
                        automatic = true,
                    ),
                )
            }
            val observation = observationResult as? DeviceToolResult.UiObservation
            if (observation == null) {
                return failure(observationResult, completedActions)
            }

            // The regular observation at the start of each turn doubles as
            // verification for the preceding action. This costs no additional
            // screenshot/API request and prevents a dispatched gesture from
            // being mistaken for a real navigation success.
            pendingVerification?.let { pending ->
                val packageChanged =
                    observation.snapshot.packageName != pending.beforePackage
                val treeChanged =
                    observation.snapshot.fingerprint.hash != pending.beforeFingerprint
                val screenChanged = packageChanged || treeChanged
                recentActions +=
                    "verification ${pending.action}: screen_changed=$screenChanged, " +
                    "package=${observation.snapshot.packageName}"
                if (!screenChanged) {
                    repeatedUnchangedCount =
                        if (repeatedUnchangedAction == pending.actionName) {
                            repeatedUnchangedCount + 1
                        } else {
                            1
                        }
                    repeatedUnchangedAction = pending.actionName
                    if (repeatedUnchangedCount >= MAX_UNCHANGED_ACTIONS) {
                        return LocalAgentOutcome(
                            message = "같은 화면에서 ${pending.actionName} 동작이 " +
                                "반복되어 안전하게 중단했습니다.",
                            status = "Gemini 화면 변화 없음",
                            steps = completedActions,
                        )
                    }
                    if (repeatedUnchangedCount >= 2) {
                        recentActions +=
                            "The last strategy failed repeatedly. Do not repeat " +
                                "${pending.actionName}; choose a different grounded action."
                    }
                } else {
                    repeatedUnchangedAction = null
                    repeatedUnchangedCount = 0
                }
                pendingVerification = null
            }

            // First gate the accessibility text before capturing pixels. Only
            // a cloud-safe screen may enter the local OCR enrichment stage.
            var privacySnapshot = observation.snapshot
            var privacy = ScreenPrivacyRouter.route(privacySnapshot)
            var screenElements =
                ScreenElementFusion.fromAccessibility(observation.snapshot)
            if (
                privacy.route == PrivacyRoute.CLOUD_OK &&
                ScreenElementFusion.shouldRunOcr(observation.snapshot)
            ) {
                onProgress("${model.displayName} · 로컬 화면 요소 분석 중…")
                val ocrStarted = SystemClock.elapsedRealtime()
                val ocr = ocrAnalyzer
                    .recognizeCurrentScreen(
                        maxDimension = SCREENSHOT_MAX_DIMENSION,
                        preferCloseIcons = goal.isExplicitPopupDismissGoal(),
                    )
                    .getOrNull()
                if (ocr != null) {
                    screenElements = ScreenElementFusion.fuse(
                        snapshot = observation.snapshot,
                        ocr = ocr,
                        deviceWidth = context.resources.displayMetrics.widthPixels,
                        deviceHeight = context.resources.displayMetrics.heightPixels,
                    )
                    // OCR may reveal private text that the accessibility tree
                    // hid. Re-run the on-device privacy gate before serializing
                    // any fused element into a Gemini request.
                    privacySnapshot = ScreenElementFusion.privacySnapshot(
                        original = observation.snapshot,
                        elements = screenElements,
                    )
                    privacy = ScreenPrivacyRouter.route(privacySnapshot)
                    Log.i(
                        TAG,
                        "ocr_fusion step=$plannerStep latency_ms=" +
                            "${SystemClock.elapsedRealtime() - ocrStarted} " +
                            "ocr_ms=${ocr.recognitionMs} " +
                            "icon_ms=${ocr.iconDetectionMs} " +
                            "ocr_lines=${ocr.lines.size} " +
                            "close_icons=${ocr.closeIcons.size} " +
                            "elements=${screenElements.size} " +
                            "route=${privacy.route} " +
                            "reasons=${privacy.reasons.joinToString()}",
                    )
                } else {
                    Log.w(TAG, "ocr_fusion step=$plannerStep failed; using accessibility only")
                }
            }

            // Routing is screen-scoped and is reevaluated every step.
            if (privacy.route != previousPrivacyRoute) {
                onTrace(
                    AgentTraceEvent.RuntimeRoute(
                        step = displayedStep,
                        runtime = when (privacy.route) {
                            PrivacyRoute.CLOUD_OK -> model.displayName
                            PrivacyRoute.LOCAL_ONLY -> "MiniCPM · 로컬 개인정보 모드"
                            PrivacyRoute.USER_HANDOFF -> "사용자 직접 확인"
                        },
                        reason = if (privacy.reasons.isEmpty()) {
                            "현재 화면에서 민감정보 신호가 발견되지 않았습니다."
                        } else {
                            "감지 신호: ${privacy.reasons.joinToString()}"
                        },
                    ),
                )
                previousPrivacyRoute = privacy.route
            }
            if (privacy.route == PrivacyRoute.USER_HANDOFF) {
                return LocalAgentOutcome(
                    message = "인증·결제 또는 보안정보 화면을 감지했습니다. " +
                        "이 단계부터 직접 확인해 주세요.",
                    status = "개인정보 보호 · 사용자 확인 필요",
                    steps = completedActions,
                )
            }
            if (privacy.route == PrivacyRoute.LOCAL_ONLY) {
                onProgress("개인정보 보호 · 로컬 모델 판단 중…")
                when (
                    val localPlan = localPrivacyPlanner.decide(
                        context = context,
                        goal = goal,
                        snapshot = privacySnapshot,
                        allowedResources = selectedResources,
                    )
                ) {
                    is LocalPrivacyPlan.Handoff ->
                        return LocalAgentOutcome(
                            message = localPlan.message,
                            status = "개인정보 보호 · 사용자 확인 필요",
                            steps = completedActions,
                        )

                    is LocalPrivacyPlan.Tool -> {
                        val localCall = if (
                            localPlan.call.name == TapNodeDeviceTool.NAME &&
                            repeatedUnchangedAction == TapNodeDeviceTool.NAME &&
                            repeatedUnchangedCount >= 1
                        ) {
                            DeviceToolCall(
                                name = localPlan.call.name,
                                arguments = JSONObject(localPlan.call.arguments.toString())
                                    .put("coordinate_fallback", true),
                            )
                        } else {
                            localPlan.call
                        }
                        val blocked = blockedActionReason(
                            call = localCall,
                            observation = observation,
                            localOnly = true,
                            goal = goal,
                        )
                        if (blocked != null) {
                            return LocalAgentOutcome(
                                message = blocked,
                                status = "안전 정책 · 사용자 확인 필요",
                                steps = completedActions,
                            )
                        }
                        onTrace(AgentTraceEvent.ToolCall(displayedStep, localCall))
                        val localResult = execute(localCall)
                        Log.i(
                            TAG,
                            "local_action step=$plannerStep " +
                                "tool=${localCall.name} " +
                                "arguments=${localCall.arguments} " +
                                "result=${resultStatus(localResult)}",
                        )
                        onTrace(
                            AgentTraceEvent.ToolResult(
                                displayedStep,
                                localCall,
                                localResult,
                            ),
                        )
                        recentActions +=
                            "local_private:${localCall.name} -> ${resultStatus(localResult)}"
                        completedActions += 1
                        if (
                            localCall.name == FillSecretDeviceTool.NAME &&
                            localResult is DeviceToolResult.Action &&
                            localResult.success
                        ) {
                            return LocalAgentOutcome(
                                message = "승인된 보안값을 기기 안에서 입력했습니다. " +
                                    "로그인/제출 버튼은 직접 확인해 주세요.",
                                status = "로컬 보안 입력 완료 · 사용자 확인 필요",
                                steps = completedActions,
                            )
                        }
                        if (
                            localResult is DeviceToolResult.Action &&
                            localResult.success &&
                            localCall.name != WaitDeviceTool.NAME
                        ) {
                            pendingVerification = PendingVerification(
                                actionName = localCall.name,
                                action = "${localCall.name}(${localCall.arguments})",
                                beforePackage = observation.snapshot.packageName,
                                beforeFingerprint = observation.snapshot.fingerprint.hash,
                            )
                        }
                        if (
                            localResult is DeviceToolResult.Error &&
                            localResult.code in NON_RECOVERABLE_ERRORS
                        ) {
                            return failure(localResult, completedActions)
                        }
                        settleAfter(localCall, localResult)
                        continue
                    }
                }
            }

            val startedAt = SystemClock.elapsedRealtime()
            var plannerAction = apiClient.decide(
                apiKey = apiKey,
                model = model,
                request = GeminiPlannerRequest(
                    goal = goal,
                    step = plannerStep,
                    maxSteps = MAX_STEPS,
                    screenWidth = context.resources.displayMetrics.widthPixels,
                    screenHeight = context.resources.displayMetrics.heightPixels,
                    observation = observation.snapshot,
                    screenElements = screenElements,
                    recentActions = recentActions.takeLast(MAX_RECENT_ACTIONS),
                ),
            )
            if (plannerAction.action == ACTION_REQUEST_VISUAL) {
                val screenshotCall = DeviceToolCall(
                    CaptureScreenDeviceTool.NAME,
                    JSONObject().put("max_dimension", SCREENSHOT_MAX_DIMENSION),
                )
                val screenshot = execute(screenshotCall)
                onTrace(
                    AgentTraceEvent.ToolResult(
                        step = displayedStep,
                        call = screenshotCall,
                        result = screenshot,
                        automatic = true,
                    ),
                )
                if (screenshot !is DeviceToolResult.Screenshot) {
                    return failure(screenshot, completedActions)
                }
                plannerAction = apiClient.decide(
                    apiKey = apiKey,
                    model = model,
                    request = GeminiPlannerRequest(
                        goal = goal,
                        step = plannerStep,
                        maxSteps = MAX_STEPS,
                        screenWidth = context.resources.displayMetrics.widthPixels,
                        screenHeight = context.resources.displayMetrics.heightPixels,
                        observation = observation.snapshot,
                        screenElements = screenElements,
                        screenshot = screenshot,
                        recentActions = recentActions.takeLast(MAX_RECENT_ACTIONS),
                    ),
                )
                if (plannerAction.action == ACTION_REQUEST_VISUAL) {
                    return LocalAgentOutcome(
                        message = "현재 화면을 이미지로 확인했지만 안전한 다음 동작을 정하지 못했습니다.",
                        status = "${model.displayName} · 화면 판단 실패",
                        steps = completedActions,
                    )
                }
            }
            val inferenceMs = SystemClock.elapsedRealtime() - startedAt
            Log.i(
                TAG,
                "${model.apiId} step=$plannerStep latency_ms=$inferenceMs " +
                    "action=${plannerAction.action}",
            )

            when (plannerAction.action) {
                ACTION_FINISH_SUCCESS ->
                    return LocalAgentOutcome(
                        message = plannerAction.message ?: "요청한 화면 작업을 완료했습니다.",
                        status = "${model.displayName} · 완료",
                        steps = completedActions,
                    )

                ACTION_FINISH_FAILURE ->
                    return LocalAgentOutcome(
                        message = plannerAction.message ?: "현재 화면에서 안전하게 완료하지 못했습니다.",
                        status = "${model.displayName} · 완료하지 못함",
                        steps = completedActions,
                    )
            }

            // Structured output guarantees the action name, but Gemini's JSON
            // schema cannot express action-specific required fields. Treat a
            // missing coordinate/node/text as planner feedback and re-observe
            // instead of terminating the whole agent loop with an exception.
            blockedElementActionReason(
                action = plannerAction,
                screenElements = screenElements,
            )?.let { reason ->
                return LocalAgentOutcome(
                    message = reason,
                    status = "안전 정책 · 사용자 확인 필요",
                    steps = completedActions,
                )
            }
            val callConversion = runCatching {
                plannerAction.toDeviceToolCall(
                    screenWidth = context.resources.displayMetrics.widthPixels,
                    screenHeight = context.resources.displayMetrics.heightPixels,
                    screenElements = screenElements,
                )
            }
            val call = callConversion.getOrNull()
            if (call == null) {
                val reason = callConversion.exceptionOrNull()?.message
                    ?: "동작 인자가 올바르지 않습니다."
                Log.w(
                    TAG,
                    "${model.apiId} step=$plannerStep rejected_action=" +
                        "${plannerAction.action} reason=$reason",
                )
                recentActions +=
                    "invalid ${plannerAction.action}: $reason; return every required argument"
                onProgress("${model.displayName} · 잘못된 동작 보정 중…")
                continue
            }
            recoverableGroundingError(
                call = call,
                observation = observation,
            )?.let { reason ->
                Log.w(
                    TAG,
                    "${model.apiId} step=$plannerStep rejected_action=" +
                        "${plannerAction.action} reason=$reason",
                )
                recentActions += "rejected ${call.name}: $reason"
                onProgress("${model.displayName} · 위험한 제스처 보정 중…")
                continue
            }
            blockedActionReason(
                call = call,
                observation = observation,
                localOnly = false,
                goal = goal,
            )?.let { reason ->
                return LocalAgentOutcome(
                    message = reason,
                    status = "안전 정책 · 사용자 확인 필요",
                    steps = completedActions,
                )
            }
            val signature =
                "${observation.snapshot.fingerprint.hash}:${call.name}:${call.arguments}"
            repeatedSignatureCount =
                if (signature == previousSignature) repeatedSignatureCount + 1 else 1
            previousSignature = signature
            if (repeatedSignatureCount >= MAX_IDENTICAL_ACTIONS) {
                return LocalAgentOutcome(
                    message = "같은 화면에서 동일 동작이 반복되어 안전하게 중단했습니다.",
                    status = "Gemini 반복 행동 감지",
                    steps = completedActions,
                )
            }

            onTrace(AgentTraceEvent.ToolCall(displayedStep, call))
            val result = execute(call)
            onTrace(AgentTraceEvent.ToolResult(displayedStep, call, result))
            recentActions += summarize(call, result)
            completedActions += 1
            if (
                result is DeviceToolResult.Action &&
                result.success &&
                call.name != WaitDeviceTool.NAME
            ) {
                pendingVerification = PendingVerification(
                    actionName = call.name,
                    action = "${call.name}(${call.arguments})",
                    beforePackage = observation.snapshot.packageName,
                    beforeFingerprint = observation.snapshot.fingerprint.hash,
                )
            }
            if (result is DeviceToolResult.Error && result.code in NON_RECOVERABLE_ERRORS) {
                return failure(result, completedActions)
            }
            settleAfter(call, result)
        }

        return LocalAgentOutcome(
            message = "최대 실행 단계에 도달해 작업을 중단했습니다.",
            status = "Gemini 단계 제한 도달",
            steps = completedActions,
        )
    }

    private suspend fun execute(call: DeviceToolCall): DeviceToolResult =
        withContext(Dispatchers.IO) {
            deviceTools.execute(call)
        }

    private suspend fun settleAfter(
        call: DeviceToolCall,
        result: DeviceToolResult,
    ) {
        if (
            result is DeviceToolResult.Action &&
            result.success &&
            call.name != WaitDeviceTool.NAME
        ) {
            // The next loop's normal observe is the verification. A short
            // gesture settle avoids the previous fixed 1.2 s plus a second
            // verification pass on every action.
            delay(ACTION_SETTLE_MS)
        }
    }

    private fun blockedActionReason(
        call: DeviceToolCall,
        observation: DeviceToolResult.UiObservation,
        localOnly: Boolean,
        goal: String,
    ): String? {
        if (localOnly && call.name in setOf(SetTextDeviceTool.NAME, TapDeviceTool.NAME)) {
            return "개인정보 화면에서는 자유 좌표 탭이나 자동 텍스트 입력을 실행하지 않습니다."
        }
        if (call.name != TapNodeDeviceTool.NAME) return null
        val nodeId = call.arguments.optString("node_id")
        val target = observation.snapshot.nodes.firstOrNull { node -> node.id == nodeId }
        if (localOnly) {
            PrivateScreenActionPolicy.blockedLocalTapReason(
                node = target,
                goal = goal,
            )?.let { return it }
        }
        return ShoppingActionPolicy.blockedTapReason(target)
    }

    /**
     * Android's Y axis is easy for a planner to invert. On a launcher, a
     * downward vertical swipe exposes private notifications instead of the app
     * drawer. Reject it before dispatch and feed the correction into the next
     * planner turn. This is a generic grounding guard, not an app workflow.
     */
    private fun recoverableGroundingError(
        call: DeviceToolCall,
        observation: DeviceToolResult.UiObservation,
    ): String? {
        if (
            call.name == SwipeDeviceTool.NAME &&
            "launcher" in observation.snapshot.packageName.lowercase()
        ) {
            val startX = call.arguments.optDouble("start_x")
            val startY = call.arguments.optDouble("start_y")
            val endX = call.arguments.optDouble("end_x")
            val endY = call.arguments.optDouble("end_y")
            val vertical = kotlin.math.abs(endY - startY) > kotlin.math.abs(endX - startX)
            if (vertical && endY > startY) {
                return "Android y increases downward. To open the app drawer, use an " +
                    "upward gesture with start_y > end_y; this proposal opens notifications."
            }
        }
        if (call.name == SetTextDeviceTool.NAME) {
            val requested = call.arguments.optString("text").trim()
            val alreadyEntered = observation.snapshot.nodes.any { node ->
                node.visibleToUser &&
                    node.enabled &&
                    node.editable &&
                    node.text?.trim() == requested
            }
            if (requested.isNotEmpty() && alreadyEntered) {
                return "The editable field already contains this exact text. Do not type it " +
                    "again; use submit_text or choose the next grounded control."
            }
        }
        return null
    }

    private fun resultStatus(result: DeviceToolResult): String = when (result) {
        is DeviceToolResult.Action -> "success=${result.success}"
        is DeviceToolResult.Error -> "error=${result.code}"
        else -> result::class.java.simpleName
    }

    private fun GeminiPlannerAction.toDeviceToolCall(
        screenWidth: Int,
        screenHeight: Int,
        screenElements: List<ScreenElement>,
    ): DeviceToolCall = when (action) {
        ACTION_TAP -> DeviceToolCall(
            TapDeviceTool.NAME,
            JSONObject()
                .put("x", x.requiredCoordinate("x").toPixels(screenWidth))
                .put("y", y.requiredCoordinate("y").toPixels(screenHeight)),
        )

        ACTION_TAP_NODE -> DeviceToolCall(
            TapNodeDeviceTool.NAME,
            JSONObject().put(
                "node_id",
                nodeId?.takeIf(String::isNotBlank)
                    ?: error("tap_node에는 node_id가 필요합니다."),
            ),
        )

        ACTION_TAP_ELEMENT -> {
            val requestedId = elementId?.takeIf(String::isNotBlank)
                ?: error("tap_element에는 element_id가 필요합니다.")
            val element = screenElements.firstOrNull { candidate ->
                candidate.id == requestedId
            } ?: error("현재 화면에 element_id=$requestedId 요소가 없습니다.")
            element.nodeId?.let { nodeId ->
                DeviceToolCall(
                    TapNodeDeviceTool.NAME,
                    JSONObject().put("node_id", nodeId),
                )
            } ?: DeviceToolCall(
                TapDeviceTool.NAME,
                JSONObject()
                    .put(
                        "x",
                        element.bounds.exactCenterX()
                            .coerceIn(0f, (screenWidth - 1).coerceAtLeast(1).toFloat()),
                    )
                    .put(
                        "y",
                        element.bounds.exactCenterY()
                            .coerceIn(0f, (screenHeight - 1).coerceAtLeast(1).toFloat()),
                    ),
            )
        }

        ACTION_SWIPE -> DeviceToolCall(
            SwipeDeviceTool.NAME,
            JSONObject()
                // A vertical swipe needs only one meaningful X. Structured
                // output occasionally omits end_x; reuse x rather than losing
                // a safe, otherwise complete gesture turn.
                .put(
                    "start_x",
                    (x ?: endX).requiredCoordinate("x").toPixels(screenWidth),
                )
                .put("start_y", y.requiredCoordinate("y").toPixels(screenHeight))
                .put(
                    "end_x",
                    (endX ?: x).requiredCoordinate("end_x").toPixels(screenWidth),
                )
                .put("end_y", endY.requiredCoordinate("end_y").toPixels(screenHeight))
                .put("duration_ms", DEFAULT_SWIPE_MS),
        )

        ACTION_TYPE -> DeviceToolCall(
            SetTextDeviceTool.NAME,
            JSONObject().put(
                "text",
                text?.takeIf(String::isNotBlank)
                    ?: error("type에는 text가 필요합니다."),
            ),
        )

        ACTION_SUBMIT -> DeviceToolCall(SubmitTextDeviceTool.NAME)
        ACTION_HOME -> DeviceToolCall(GoHomeDeviceTool.NAME)
        ACTION_BACK -> DeviceToolCall(GoBackDeviceTool.NAME)
        ACTION_WAIT -> DeviceToolCall(
            WaitDeviceTool.NAME,
            JSONObject().put(
                "duration_ms",
                (durationMs ?: WaitDeviceTool.DEFAULT_DURATION_MS)
                    .coerceIn(
                        WaitDeviceTool.MIN_DURATION_MS,
                        WaitDeviceTool.MAX_DURATION_MS,
                    ),
            ),
        )

        else -> error("지원하지 않는 Gemini action입니다: $action")
    }

    private fun blockedElementActionReason(
        action: GeminiPlannerAction,
        screenElements: List<ScreenElement>,
    ): String? {
        if (action.action != ACTION_TAP_ELEMENT) return null
        val element = screenElements.firstOrNull { candidate ->
            candidate.id == action.elementId
        } ?: return null
        return ShoppingActionPolicy.blockedLabelReason(
            listOfNotNull(
                element.text,
                element.contentDescription,
                element.viewId,
            ).joinToString(" "),
        )
    }

    private fun Double?.requiredCoordinate(name: String): Double {
        val value = this ?: error("$name 좌표가 없습니다.")
        require(value in 0.0..NORMALIZED_COORDINATE_MAX) {
            "$name=$value 좌표가 0..1000 범위를 벗어났습니다."
        }
        return value
    }

    private fun Double.toPixels(dimension: Int): Double =
        this / NORMALIZED_COORDINATE_MAX * (dimension - 1).coerceAtLeast(1)

    private fun String.isExplicitPopupDismissGoal(): Boolean {
        val normalized = lowercase()
        val popupContext = POPUP_TERMS.any(normalized::contains)
        val dismissIntent = DISMISS_TERMS.any(normalized::contains)
        return popupContext && dismissIntent
    }

    private fun summarize(
        call: DeviceToolCall,
        result: DeviceToolResult,
    ): String = when (result) {
        is DeviceToolResult.Action ->
            "${call.name}(${call.arguments}) -> success=${result.success}"
        is DeviceToolResult.Error ->
            "${call.name}(${call.arguments}) -> error=${result.code}"
        is DeviceToolResult.Screenshot -> "screenshot ${result.width}x${result.height}"
        is DeviceToolResult.UiObservation ->
            "observe ${result.snapshot.packageName} nodes=${result.snapshot.nodes.size}"
    }

    private fun failure(result: DeviceToolResult, steps: Int): LocalAgentOutcome {
        val message = when (result) {
            is DeviceToolResult.Error -> "${result.code}: ${result.message}"
            else -> "필요한 화면 정보를 가져오지 못했습니다."
        }
        return LocalAgentOutcome(
            message = message,
            status = "Gemini 기기 작업 실패",
            steps = steps,
        )
    }

    private companion object {
        const val TAG = "GeminiAgentController"
        // Opening an unpinned app through a launcher, handling startup UI,
        // searching, and reaching a reversible cart action routinely exceeds
        // 14 planner turns. Hard safety gates and repetition limits still stop
        // risky or stuck runs before this bounded ceiling.
        const val MAX_STEPS = 24
        const val MAX_IDENTICAL_ACTIONS = 3
        const val MAX_UNCHANGED_ACTIONS = 3
        const val MAX_RECENT_ACTIONS = 6
        const val MAX_CLOUD_RESOURCE_CANDIDATES = 12
        const val MAX_TRANSIENT_OBSERVATION_RETRIES = 5
        const val TRANSIENT_OBSERVATION_RETRY_MS = 400L
        const val TRANSIENT_UI_TREE_ERROR = "UI_TREE_UNAVAILABLE"
        const val SCREENSHOT_MAX_DIMENSION = 1_024
        const val ACTION_SETTLE_MS = 350L
        const val DEFAULT_SWIPE_MS = 400L
        const val NORMALIZED_COORDINATE_MAX = 1_000.0
        const val ACTION_TAP = "tap"
        const val ACTION_TAP_ELEMENT = "tap_element"
        const val ACTION_TAP_NODE = "tap_node"
        const val ACTION_SWIPE = "swipe"
        const val ACTION_TYPE = "type"
        const val ACTION_SUBMIT = "submit"
        const val ACTION_HOME = "home"
        const val ACTION_BACK = "back"
        const val ACTION_WAIT = "wait"
        const val ACTION_REQUEST_VISUAL = "request_visual"
        const val ACTION_FINISH_SUCCESS = "finish_success"
        const val ACTION_FINISH_FAILURE = "finish_failure"
        val NON_RECOVERABLE_ERRORS = setOf(
            "ACCESSIBILITY_NOT_CONNECTED",
            "SCREENSHOT_SECURITY_EXCEPTION",
            "SCREENSHOT_UNSUPPORTED",
        )
        val AUTH_GOAL_TERMS = listOf(
            "로그인",
            "계정",
            "아이디",
            "비밀번호",
            "login",
            "account",
            "password",
        )
        val POPUP_TERMS = listOf(
            "팝업",
            "광고",
            "popup",
            "advertisement",
            "modal",
        )
        val DISMISS_TERMS = listOf(
            "닫",
            "꺼",
            "제거",
            "close",
            "dismiss",
        )
    }

    private data class PendingVerification(
        val actionName: String,
        val action: String,
        val beforePackage: String,
        val beforeFingerprint: String,
    )
}
