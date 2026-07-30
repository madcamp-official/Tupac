package com.example.mobileguiagent.cloud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.mobileguiagent.agent.PrivacyRoute
import com.example.mobileguiagent.agent.PrivacyDecision
import com.example.mobileguiagent.agent.ScreenPrivacyRouter
import com.example.mobileguiagent.agent.AgentWorkspace
import com.example.mobileguiagent.agent.AgentActionDispatcher
import com.example.mobileguiagent.credentials.LocalCredentialRepository
import com.example.mobileguiagent.device.CaptureScreenDeviceTool
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolExecutor
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.FillSecretDeviceTool
import com.example.mobileguiagent.device.LaunchAppDeviceTool
import com.example.mobileguiagent.device.ObserveUiDeviceTool
import com.example.mobileguiagent.device.ScrollDeviceTool
import com.example.mobileguiagent.device.SetTextDeviceTool
import com.example.mobileguiagent.device.SwipeDeviceTool
import com.example.mobileguiagent.device.TapDeviceTool
import com.example.mobileguiagent.device.TapNodeDeviceTool
import com.example.mobileguiagent.device.WaitDeviceTool
import com.example.mobileguiagent.model.AgentActionCycleGuard
import com.example.mobileguiagent.model.AgentActionVerifier
import com.example.mobileguiagent.model.AgentActionBoundaryPolicy
import com.example.mobileguiagent.model.TaskContract
import com.example.mobileguiagent.model.AgentCapability
import com.example.mobileguiagent.model.AgentSelectionPolicy
import com.example.mobileguiagent.model.AgentAttemptHistory
import com.example.mobileguiagent.model.AuthenticationInterceptor
import com.example.mobileguiagent.model.BlockingSurfaceInterceptor
import com.example.mobileguiagent.model.GoalInvariantInterceptor
import com.example.mobileguiagent.model.AgentRunContext
import com.example.mobileguiagent.model.AgentTraceEvent
import com.example.mobileguiagent.model.SelectionProgressGuard
import com.example.mobileguiagent.model.TaskStateConstraintInterceptor
import com.example.mobileguiagent.model.AgentOutcome
import com.example.mobileguiagent.model.AgentRunDisposition
import com.example.mobileguiagent.model.AgentStopReason
import com.example.mobileguiagent.model.AgentCompletionEvaluator
import com.example.mobileguiagent.model.EarliestShowtimeInterceptor
import com.example.mobileguiagent.model.ExhaustedShowtimeFallbackPolicy
import com.example.mobileguiagent.model.GeometricSeatDecision
import com.example.mobileguiagent.model.GeometricSeatInterceptor
import com.example.mobileguiagent.model.DateSelectionVerificationPolicy
import com.example.mobileguiagent.model.ModalProgressGuard
import com.example.mobileguiagent.model.PreferredAffordanceInterceptor
import com.example.mobileguiagent.model.RequestedAudienceInterceptor
import com.example.mobileguiagent.model.RequestedDayAvailabilityPolicy
import com.example.mobileguiagent.model.RequestedDateInterceptor
import com.example.mobileguiagent.model.RequiredEntityNavigationInterceptor
import com.example.mobileguiagent.model.RuntimeAction
import com.example.mobileguiagent.model.RuntimeInterception
import com.example.mobileguiagent.model.SeatSelectionPolicy
import com.example.mobileguiagent.model.SeatSelectionVerificationPolicy
import com.example.mobileguiagent.model.ShowtimeAttemptTracker
import com.example.mobileguiagent.model.ShowtimeSelectionVerificationPolicy
import com.example.mobileguiagent.model.toDeviceToolCall
import com.example.mobileguiagent.ocr.LocalOcrScreenAnalyzer
import com.example.mobileguiagent.remote.RemoteObservationRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object AutomaticBookingSettlePolicy {
    const val SEAT_MAP_TRANSITION_SETTLE_MS = 1_200L
    const val ENTITY_SELECTION_TRANSITION_SETTLE_MS = 1_200L
    const val ENTITY_SELECTION_SETTLE_MS = 1_200L
    // Megabox WebView can acknowledge the accessibility click before its
    // JavaScript price model commits the seat. Successful live transitions
    // have taken about 1.9 s end-to-end, so leave enough time for the price
    // subtree to become authoritative before declaring the center seat absent.
    const val SEAT_SELECTION_SETTLE_MS = 1_800L

    fun delayMs(interceptionCode: String): Long? =
        when (interceptionCode) {
            "AUDIENCE_CONFIRMED_CONTINUE" -> SEAT_MAP_TRANSITION_SETTLE_MS
            "SELECT_REQUIRED_ENTITY" -> ENTITY_SELECTION_SETTLE_MS
            "ENTITY_SELECTION_CONFIRMED_CONTINUE" ->
                ENTITY_SELECTION_TRANSITION_SETTLE_MS
            "SELECT_GEOMETRIC_CENTER_SEAT" -> SEAT_SELECTION_SETTLE_MS
            else -> null
        }
}

/**
 * Cloud vision planner over the shared Android Device Tool registry.
 * Sensitive values are redacted on-device before a Gemini request, and secret
 * filling is a deterministic package-bound broker operation rather than model
 * inference.
 */
class GeminiAgentRunner(
    private val deviceTools: DeviceToolExecutor,
    private val planner: AgentPlanner = RetryingAgentPlanner(GeminiApiClient()),
) {
    private val ocrAnalyzer = LocalOcrScreenAnalyzer(deviceTools)
    private val actionDispatcher = AgentActionDispatcher(deviceTools)

    suspend fun run(
        context: Context,
        apiKey: String,
        model: GeminiModel,
        goal: String,
        runContext: AgentRunContext,
        workspaceProvider: () -> AgentWorkspace,
        onProgress: (String) -> Unit,
        onTrace: suspend (AgentTraceEvent) -> Unit,
    ): AgentOutcome {
        require(apiKey.isNotBlank()) {
            "Gemini API 키가 없습니다. 프로젝트 local.properties에 " +
                "GEMINI_API_KEY=... 형식으로 추가하세요."
        }
        Log.i(
            TAG,
            "agent_run_start model=${model.apiId} skills=" +
                runContext.skills.all.joinToString { it.id }.ifBlank { "none" },
        )
        val recentActions = mutableListOf<String>()
        var completedActions = 0
        var previousSignature: String? = null
        var repeatedSignatureCount = 0
        var previousPrivacyRoute: PrivacyRoute? = null
        var pendingVerification: PendingVerification? = null
        var repeatedUnchangedAction: String? = null
        var repeatedUnchangedCount = 0
        val actionCycleGuard = AgentActionCycleGuard()
        val acknowledgedBlockingSurfaces = mutableSetOf<String>()
        val attemptHistory = AgentAttemptHistory()
        val rejectedTargetKeys = mutableSetOf<String>()
        val unavailableOptionScreens = mutableSetOf<String>()
        val attemptedSeatKeys = mutableSetOf<String>()
        val showtimeAttemptTracker = ShowtimeAttemptTracker()
        var alternatingCycleRejections = 0
        var consecutiveCredentialRecoveryAttempts = 0
        var deferredPreferredCount = 0
        var plannerDecisionCount = 0

        for (plannerStep in 1..(MAX_AGENT_CYCLES + FINAL_VERIFICATION_DRAIN_CYCLES)) {
            val finalVerificationDrain = plannerStep > MAX_AGENT_CYCLES
            if (finalVerificationDrain && pendingVerification == null) break
            val displayedStep = completedActions + 1
            onProgress(
                "${model.displayName} · 화면 판단 " +
                    "${plannerStep.coerceAtMost(MAX_AGENT_CYCLES)}/$MAX_AGENT_CYCLES",
            )

            val observationResult = observeWithRetry(
                model = model,
                displayedStep = displayedStep,
                onProgress = onProgress,
                onTrace = onTrace,
            )
            val observation = observationResult as? DeviceToolResult.UiObservation
            if (observation == null) {
                return failure(observationResult, completedActions)
            }
            Log.i(
                TAG,
                "trace_observe step=$plannerStep package=${observation.snapshot.packageName} " +
                    "fingerprint=${observation.snapshot.fingerprint.hash.take(12)} " +
                    "nodes=${observation.snapshot.nodes.size}",
            )

            // The regular observation at the start of each turn doubles as
            // verification for the preceding action. This costs no additional
            // screenshot/API request and prevents a dispatched gesture from
            // being mistaken for a real navigation success.
            var verifiedTransitionTargetKey: String? = null
            val inMemoryPending = pendingVerification
            if (inMemoryPending != null) {
                val pending = inMemoryPending
                val genericVerification = AgentActionVerifier.verify(
                    before = pending.beforeSnapshot,
                    after = observation.snapshot,
                    expectedChange = pending.expectedChange,
                    taskContract = runContext.taskContract,
                )
                val verification = when {
                    DateSelectionVerificationPolicy.applies(
                        targetKey = pending.targetKey,
                        runtimeCode = pending.runtimeCode,
                    ) ->
                        DateSelectionVerificationPolicy.enforce(
                            generic = genericVerification,
                            contract = runContext.taskContract,
                            after = observation.snapshot,
                        )

                    pending.targetKey?.startsWith(SEAT_TARGET_PREFIX) == true ->
                        SeatSelectionVerificationPolicy.enforce(
                            generic = genericVerification,
                            contract = runContext.taskContract,
                            before = pending.beforeSnapshot,
                            after = observation.snapshot,
                            targetKey = pending.targetKey,
                        )

                    ShowtimeSelectionVerificationPolicy.applies(
                        contract = runContext.taskContract,
                        targetKey = pending.targetKey,
                        runtimeCode = pending.runtimeCode,
                    ) ->
                        ShowtimeSelectionVerificationPolicy.enforce(
                            generic = genericVerification,
                            contract = runContext.taskContract,
                            before = pending.beforeSnapshot,
                            after = observation.snapshot,
                        )

                    else -> genericVerification
                }
                Log.i(
                    TAG,
                    "trace_verify step=$plannerStep action=${pending.actionName} " +
                        "before=${pending.beforeSnapshot.fingerprint.hash.take(12)} " +
                        "after=${observation.snapshot.fingerprint.hash.take(12)} " +
                        "verified=${verification.verified} " +
                        "evidence=${verification.evidence.joinToString()}",
                )
                onTrace(
                    AgentTraceEvent.ActionVerification(
                        step = displayedStep,
                        tool = pending.actionName,
                        callId = pending.callId,
                        targetKey = pending.targetKey,
                        verified = verification.verified,
                        evidence = verification.evidence.map { it.name },
                        message = verification.message,
                    ),
                )
                recentActions +=
                    "verification ${pending.action}: verified=${verification.verified}, " +
                    "evidence=${verification.evidence.joinToString()}, " +
                    "package=${observation.snapshot.packageName}"
                pending.targetKey
                    ?.takeIf { key -> key.startsWith(SEAT_TARGET_PREFIX) }
                    ?.let { seatKey ->
                        if (verification.verified) {
                            attemptedSeatKeys.clear()
                        } else {
                            attemptedSeatKeys += seatKey
                        }
                    }
                pending.showtimeMinutes
                    ?.takeIf { pending.runtimeCode == "SELECT_EARLIEST_SHOWTIME" }
                    ?.let { showtimeMinutes ->
                        if (verification.verified) {
                            showtimeAttemptTracker.clear(showtimeMinutes)
                        } else if (
                            showtimeAttemptTracker.recordUnchanged(showtimeMinutes) &&
                            AgentSelectionPolicy.NEXT_CANDIDATE_FALLBACK in
                            runContext.taskContract.selectionPolicies
                        ) {
                            val factKey =
                                GeometricSeatInterceptor.EXHAUSTED_SHOWTIME_FACT_PREFIX +
                                    showtimeMinutes
                            if (factKey !in workspaceProvider().facts) {
                                onTrace(
                                    AgentTraceEvent.RuntimeFact(
                                        step = displayedStep,
                                        key = factKey,
                                        value = "showtime_unresponsive_after_bounded_retries",
                                    ),
                                )
                            }
                            recentActions +=
                                "runtime SHOWTIME_UNRESPONSIVE: " +
                                "${clockText(showtimeMinutes)} excluded after bounded retries"
                            actionCycleGuard.reset()
                            repeatedUnchangedAction = null
                            repeatedUnchangedCount = 0
                            pendingVerification = null
                            continue
                        }
                }
                if (!verification.verified) {
                    val unchangedActionIdentity = buildString {
                        append(pending.actionName)
                        pending.targetKey?.let { targetKey ->
                            append(':')
                            append(targetKey)
                        }
                    }
                    repeatedUnchangedCount =
                        if (repeatedUnchangedAction == unchangedActionIdentity) {
                            repeatedUnchangedCount + 1
                        } else {
                            1
                        }
                    repeatedUnchangedAction = unchangedActionIdentity
                    if (repeatedUnchangedCount >= MAX_UNCHANGED_ACTIONS) {
                        return AgentOutcome(
                            message = "같은 화면에서 ${pending.actionName} 동작이 " +
                                "반복되어 안전하게 중단했습니다.",
                            status = "Gemini 화면 변화 없음",
                            steps = completedActions,
                            disposition = AgentRunDisposition.PAUSED,
                            stopReason = AgentStopReason.STALLED,
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
                    attemptHistory.onScreenChanged()
                    verifiedTransitionTargetKey = pending.targetKey
                }
                pendingVerification = null
                if (finalVerificationDrain) {
                    return AgentOutcome(
                        message =
                            "실행 한도 직전의 마지막 동작 결과까지 검증해 저장했습니다. " +
                                "추가 자동 동작은 실행하지 않았습니다.",
                        status = "에이전트 실행 한도 도달",
                        steps = completedActions,
                        disposition = AgentRunDisposition.PAUSED,
                        stopReason = AgentStopReason.STEP_LIMIT,
                    )
                }
            } else {
                // A PREPARED action can survive process death between Android
                // dispatch and result verification. Reconcile it against the
                // first fresh observation and never blindly execute it again.
                workspaceProvider().pendingAction?.let { pending ->
                    val afterSemantic =
                        AgentActionVerifier.semanticSignature(observation.snapshot)
                    val packageChanged = pending.beforePackage
                        ?.let { it != observation.snapshot.packageName }
                        ?: false
                    val semanticChanged = pending.beforeSemanticSignature
                        ?.let { it != afterSemantic }
                        ?: false
                    val evidence = buildList {
                        if (packageChanged) add("PACKAGE_CHANGED")
                        if (semanticChanged) add("SEMANTIC_UI_CHANGED")
                    }
                    val verified = evidence.isNotEmpty()
                    val message = if (verified) {
                        "Recovered pending action from durable state and observed " +
                            evidence.joinToString()
                    } else {
                        "Recovered pending action could not be verified from the current screen; " +
                            "it was not executed again."
                    }
                    onTrace(
                        AgentTraceEvent.ActionVerification(
                            step = displayedStep,
                            tool = pending.tool,
                            callId = pending.callId,
                            targetKey = pending.targetKey,
                            verified = verified,
                            evidence = evidence,
                            message = message,
                            resumed = true,
                        ),
                    )
                    recentActions +=
                        "resume reconciliation ${pending.tool}: verified=$verified, " +
                        "evidence=${evidence.joinToString()}"
                    if (!verified) {
                        if (
                            pending.targetKey?.startsWith(SEAT_TARGET_PREFIX) == true ||
                            pending.tool in SAFE_RESUME_REPLAN_TOOLS
                        ) {
                            pending.targetKey
                                ?.takeIf { key -> key.startsWith(SEAT_TARGET_PREFIX) }
                                ?.let(attemptedSeatKeys::add)
                            recentActions +=
                                "Recovered unverified reversible action ${pending.tool}; " +
                                "do not replay it blindly, replan from the fresh screen."
                            return@let
                        }
                        return AgentOutcome(
                            message = "이전 실행에서 시작된 ${pending.tool} 동작의 결과를 " +
                                "현재 화면에서 확인할 수 없어 재실행하지 않고 멈췄습니다. " +
                                "화면을 확인한 뒤 다시 실행해 주세요.",
                            status = "에이전트 재개 · 이전 동작 확인 필요",
                            steps = completedActions,
                            disposition = AgentRunDisposition.PAUSED,
                            stopReason = AgentStopReason.USER_HANDOFF,
                        )
                    }
                }
            }

            val deterministicCompletion = AgentCompletionEvaluator.evaluate(
                contract = runContext.taskContract,
                snapshot = observation.snapshot,
                validatedSelections = workspaceProvider()
                    .selections
                    .filterValues { selection -> selection.validated }
                    .keys,
            )
            if (deterministicCompletion.satisfied) {
                Log.i(
                    TAG,
                    "runtime_completion step=$plannerStep evidence=" +
                        deterministicCompletion.evidence.joinToString(),
                )
                return AgentOutcome(
                    message = "요청한 완료 경계를 현재 화면에서 확인했습니다.",
                    status = "${model.displayName} · 완료",
                    steps = completedActions,
                    disposition = AgentRunDisposition.SUCCEEDED,
                    stopReason = AgentStopReason.GOAL_COMPLETED,
                )
            }

            TaskStateConstraintInterceptor.summaryMismatch(
                contract = runContext.taskContract,
                snapshot = observation.snapshot,
            )?.let { mismatch ->
                verifiedTransitionTargetKey?.let(rejectedTargetKeys::add)
                recentActions +=
                    "runtime ${mismatch.code}: ${mismatch.message} " +
                        "Do not repeat the rejected target; correct the selected state."
                Log.w(
                    TAG,
                    "runtime_state_mismatch step=$plannerStep code=${mismatch.code} " +
                        "rejected_targets=$rejectedTargetKeys",
                )
            }

            val automaticBlockingAction = BlockingSurfaceInterceptor.intercept(
                contract = runContext.taskContract,
                snapshot = observation.snapshot,
            )?.automaticAction
            automaticBlockingAction?.toDeviceToolCall()?.let { automaticCall ->
                val automaticSignature = semanticActionSignature(
                    call = automaticCall,
                    observation = observation,
                )
                if (
                    actionCycleGuard.wouldRepeatAlternatingCycle(automaticSignature) ||
                    actionCycleGuard.wouldRepeatStateAction(automaticSignature)
                ) {
                    return AgentOutcome(
                        message = "자동 안내 처리까지 포함한 동일 상태 전이가 반복돼 중단했습니다.",
                        status = "런타임 통합 상태 사이클 감지",
                        steps = completedActions,
                        disposition = AgentRunDisposition.PAUSED,
                        stopReason = AgentStopReason.STALLED,
                    )
                }
                actionCycleGuard.record(automaticSignature)
                onProgress("${model.displayName} · 차단 안내 화면을 안전하게 처리 중…")
                Log.i(
                    TAG,
                    "runtime_interceptor step=$plannerStep code=BLOCKING_SURFACE_SAFE_ACTION " +
                        "action=${automaticCall.name} args=${safeArguments(automaticCall)}",
                )
                val dispatched = actionDispatcher.dispatch(
                    step = displayedStep,
                    call = automaticCall,
                    snapshot = observation.snapshot,
                    automatic = true,
                    onTrace = onTrace,
                )
                val automaticResult = dispatched.result
                recentActions +=
                    "runtime BLOCKING_SURFACE_SAFE_ACTION: " +
                    summarize(automaticCall, automaticResult)
                completedActions += 1
                if (screenChangingActionSucceeded(automaticCall, automaticResult)) {
                    if (
                        acknowledgedBlockingSurfaces.add(
                            observation.snapshot.fingerprint.hash,
                        )
                    ) {
                        actionCycleGuard.reset()
                    }
                    pendingVerification = PendingVerification(
                        callId = dispatched.callId,
                        actionName = automaticCall.name,
                        action = "${automaticCall.name}(${automaticCall.arguments})",
                        beforeSnapshot = observation.snapshot,
                        expectedChange = null,
                        targetKey = null,
                    )
                    settleAfter(
                        call = automaticCall,
                        result = automaticResult,
                        step = displayedStep,
                        onTrace = onTrace,
                    )
                    continue
                }
                if (
                    attemptHistory.recordRejection(
                        observation.snapshot.fingerprint.hash,
                        "BLOCKING_SURFACE_ACTION_FAILED",
                    )
                ) {
                    return AgentOutcome(
                        message = "차단 안내 화면의 안전한 액션이 반복해서 실패했습니다.",
                        status = "런타임 차단 화면 처리 실패",
                        steps = completedActions,
                        disposition = AgentRunDisposition.FAILED,
                        stopReason = AgentStopReason.TOOL_FAILURE,
                    )
                }
            }

            val preferredPending =
                PreferredAffordanceInterceptor.isPresentButNotActionable(
                    contract = runContext.taskContract,
                    snapshot = observation.snapshot,
                )
            if (preferredPending) {
                if (deferredPreferredCount < MAX_PREFERRED_AFFORDANCE_WAITS) {
                    deferredPreferredCount += 1
                    val startedAt = SystemClock.elapsedRealtime()
                    delay(PREFERRED_AFFORDANCE_WAIT_MS)
                    onTrace(
                        AgentTraceEvent.LatencySample(
                            step = displayedStep,
                            stage = "preferred_affordance_wait",
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            expectedMs = PREFERRED_AFFORDANCE_WAIT_MS,
                            operation = "preferred_affordance",
                            attempt = deferredPreferredCount,
                        ),
                    )
                    recentActions +=
                        "runtime preferred affordance is visible but not actionable; " +
                            "wait for the transient launch surface to settle"
                    continue
                }
            } else {
                deferredPreferredCount = 0
            }

            PreferredAffordanceInterceptor.intercept(
                contract = runContext.taskContract,
                snapshot = observation.snapshot,
            )?.let { preferredInterception ->
                val automaticCall = requireNotNull(
                    preferredInterception.automaticAction?.toDeviceToolCall(),
                )
                val automaticSignature = semanticActionSignature(
                    call = automaticCall,
                    observation = observation,
                )
                if (
                    actionCycleGuard.wouldRepeatAlternatingCycle(automaticSignature) ||
                    actionCycleGuard.wouldRepeatStateAction(automaticSignature)
                ) {
                    return AgentOutcome(
                        message = "스킬이 지정한 진입 제어를 반복했지만 화면 전이를 확인하지 못했습니다.",
                        status = "런타임 지정 진입점 상태 사이클 감지",
                        steps = completedActions,
                        disposition = AgentRunDisposition.PAUSED,
                        stopReason = AgentStopReason.STALLED,
                    )
                }
                actionCycleGuard.record(automaticSignature)
                onProgress("${model.displayName} · 스킬이 지정한 정확한 진입점으로 이동 중…")
                Log.i(
                    TAG,
                    "runtime_interceptor step=$plannerStep " +
                        "code=${preferredInterception.code} " +
                        "action=${automaticCall.name} args=${safeArguments(automaticCall)}",
                )
                val dispatched = actionDispatcher.dispatch(
                    step = displayedStep,
                    call = automaticCall,
                    snapshot = observation.snapshot,
                    automatic = true,
                    onTrace = onTrace,
                )
                val automaticResult = dispatched.result
                recentActions +=
                    "runtime ${preferredInterception.code}: " +
                    summarize(automaticCall, automaticResult)
                completedActions += 1
                if (screenChangingActionSucceeded(automaticCall, automaticResult)) {
                    pendingVerification = PendingVerification(
                        callId = dispatched.callId,
                        actionName = automaticCall.name,
                        action = "${automaticCall.name}(${automaticCall.arguments})",
                        beforeSnapshot = observation.snapshot,
                        expectedChange = null,
                        targetKey = TaskStateConstraintInterceptor.stableTargetKey(
                            contract = runContext.taskContract,
                            target = tapTargetNode(automaticCall, observation),
                            snapshot = observation.snapshot,
                        ),
                    )
                    settleAfter(
                        call = automaticCall,
                        result = automaticResult,
                        step = displayedStep,
                        onTrace = onTrace,
                    )
                    continue
                }
            }

            val validatedSelectionKeys = workspaceProvider()
                .selections
                .values
                .filter { selection -> selection.validated }
                .map { selection -> selection.candidateKey }
                .toSet()
            val dateInterception = RequestedDateInterceptor.intercept(
                contract = runContext.taskContract,
                snapshot = observation.snapshot,
                validatedSelectionKeys = validatedSelectionKeys,
            )
            val entityNavigationInterception = dateInterception
                ?: RequiredEntityNavigationInterceptor.intercept(
                    contract = runContext.taskContract,
                    snapshot = observation.snapshot,
                )
            val audienceInterception = entityNavigationInterception
                ?: RequestedAudienceInterceptor.intercept(
                    contract = runContext.taskContract,
                    snapshot = observation.snapshot,
                )
            val seatDecision = if (audienceInterception == null) {
                GeometricSeatInterceptor.evaluate(
                    contract = runContext.taskContract,
                    snapshot = observation.snapshot,
                    attemptedSeatKeys = attemptedSeatKeys,
                    validatedSeat =
                        workspaceProvider().selections["seat"]?.validated == true,
                )
            } else {
                null
            }
            if (
                audienceInterception == null &&
                seatDecision == null &&
                RequestedDayAvailabilityPolicy.noFutureShowtimeOnRequestedDay(
                    contract = runContext.taskContract,
                    snapshot = observation.snapshot,
                )
            ) {
                return AgentOutcome(
                    message =
                        "요청한 오늘 날짜에는 현재 시각 이후의 해당 영화 상영 회차가 " +
                            "없습니다. 날짜를 내일로 변경하지 않았습니다.",
                    status = "오늘 남은 상영 회차 없음",
                    steps = completedActions,
                    disposition = AgentRunDisposition.PAUSED,
                    stopReason = AgentStopReason.TASK_CONSTRAINT,
                )
            }
            var automaticBookingTargetKey: String? = null
            var automaticShowtimeMinutes: Int? = null
            val automaticBookingInterception = audienceInterception ?: when (seatDecision) {
                is GeometricSeatDecision.Select -> {
                    automaticBookingTargetKey = seatDecision.seatKey
                    seatDecision.interception
                }

                is GeometricSeatDecision.Exhausted -> {
                    if (
                        AgentSelectionPolicy.NEXT_CANDIDATE_FALLBACK !in
                        runContext.taskContract.selectionPolicies
                    ) {
                        return AgentOutcome(
                            message = "현재 회차에서 검증 가능한 중앙 좌석을 찾지 못했습니다.",
                            status = "중앙 좌석 없음",
                            steps = completedActions,
                            disposition = AgentRunDisposition.PAUSED,
                            stopReason = AgentStopReason.TASK_CONSTRAINT,
                        )
                    }
                    val exhaustedMinutes = seatDecision.showtimeMinutes
                        ?: return AgentOutcome(
                            message =
                                "중앙 좌석이 없지만 현재 상영 회차를 화면에서 식별하지 " +
                                    "못해 다음 회차로 안전하게 이동할 수 없습니다.",
                            status = "다음 회차 전환 정보 부족",
                            steps = completedActions,
                            disposition = AgentRunDisposition.PAUSED,
                            stopReason = AgentStopReason.TASK_CONSTRAINT,
                        )
                    val factKey =
                        GeometricSeatInterceptor.EXHAUSTED_SHOWTIME_FACT_PREFIX +
                            exhaustedMinutes
                    if (factKey !in workspaceProvider().facts) {
                        onTrace(
                            AgentTraceEvent.RuntimeFact(
                                step = displayedStep,
                                key = factKey,
                                value =
                                    "center_unavailable:" +
                                        seatDecision.centerSeatKeys.sorted().joinToString(","),
                            ),
                        )
                    }
                    attemptedSeatKeys.clear()
                    recentActions +=
                        "runtime CENTER_SEATS_EXHAUSTED: " +
                        "${clockText(exhaustedMinutes)} excluded; return for next showtime"
                    RuntimeInterception(
                        code = "CENTER_SEATS_EXHAUSTED_NEXT_SHOWTIME",
                        message =
                            "Every exact center seat failed validation for " +
                                "${clockText(exhaustedMinutes)}. Go back and select the next " +
                                "eligible showtime.",
                        automaticAction = RuntimeAction.GoBack,
                    )
                }

                null -> EarliestShowtimeInterceptor.intercept(
                    contract = runContext.taskContract,
                    snapshot = observation.snapshot,
                    excludedShowtimeMinutes = exhaustedShowtimeMinutes(
                        workspaceProvider(),
                    ),
                )
            }
            if (automaticBookingInterception?.code == "SELECT_REQUIRED_DATE") {
                automaticBookingTargetKey = runContext.taskContract.requiredDate?.targetKey
            }
            automaticBookingInterception?.automaticAction
                ?.toDeviceToolCall()
                ?.let { automaticCall ->
                    if (
                        automaticBookingTargetKey == null &&
                        automaticBookingInterception.code == "SELECT_EARLIEST_SHOWTIME"
                    ) {
                        val targetNode = tapTargetNode(automaticCall, observation)
                        automaticShowtimeMinutes =
                            TaskStateConstraintInterceptor.showtimeMinutes(
                                contract = runContext.taskContract,
                                target = targetNode,
                                snapshot = observation.snapshot,
                            )
                        automaticBookingTargetKey =
                            TaskStateConstraintInterceptor.stableTargetKey(
                                contract = runContext.taskContract,
                                target = targetNode,
                                snapshot = observation.snapshot,
                            )
                    }
                    val automaticSignature = semanticActionSignature(
                        call = automaticCall,
                        observation = observation,
                    )
                    if (
                        actionCycleGuard.wouldRepeatAlternatingCycle(automaticSignature) ||
                        actionCycleGuard.wouldRepeatStateAction(automaticSignature)
                    ) {
                        return AgentOutcome(
                            message =
                                "예매 상태를 맞추는 동일 자동 동작이 반복돼 안전하게 중단했습니다.",
                            status = "런타임 예매 상태 사이클 감지",
                            steps = completedActions,
                            disposition = AgentRunDisposition.PAUSED,
                            stopReason = AgentStopReason.STALLED,
                        )
                    }
                    actionCycleGuard.record(automaticSignature)
                    onProgress(
                        when (automaticBookingInterception.code) {
                            "AUDIENCE_INCREMENT_ADULT",
                            "AUDIENCE_DECREMENT_ADULT",
                            -> "${model.displayName} · 요청한 관람 인원을 맞추는 중…"
                            "AUDIENCE_CONFIRMED_CONTINUE" ->
                                "${model.displayName} · 좌석 선택 화면으로 이동 중…"
                            "SELECT_ENTITY_ROUTE_HINT",
                            "REVEAL_REQUIRED_ENTITY",
                            "SELECT_REQUIRED_ENTITY",
                            "ENTITY_SELECTION_CONFIRMED_CONTINUE",
                            -> "${model.displayName} · 요청한 극장을 정확히 선택하는 중…"
                            "SELECT_REQUIRED_DATE",
                            "REVEAL_REQUIRED_DATE",
                            -> "${model.displayName} · 요청한 날짜를 정확히 선택하는 중…"
                            "SELECT_GEOMETRIC_CENTER_SEAT" ->
                                "${model.displayName} · 정확한 중앙 좌석을 검증 중…"
                            "SELECT_EARLIEST_SHOWTIME" ->
                                "${model.displayName} · 실제 최우선 상영 회차를 선택 중…"
                            "REVEAL_EARLIEST_SHOWTIME" ->
                                "${model.displayName} · 최우선 상영 회차 위치로 이동 중…"
                            else ->
                                "${model.displayName} · 중앙 좌석이 있는 다음 회차를 찾는 중…"
                        },
                    )
                    Log.i(
                        TAG,
                        "runtime_interceptor step=$plannerStep " +
                            "code=${automaticBookingInterception.code} " +
                            "action=${automaticCall.name} " +
                            "target_key=$automaticBookingTargetKey " +
                            "args=${safeArguments(automaticCall)}",
                    )
                    val dispatched = actionDispatcher.dispatch(
                        step = displayedStep,
                        call = automaticCall,
                        snapshot = observation.snapshot,
                        automatic = true,
                        expectedChange = automaticBookingInterception.message,
                        targetKey = automaticBookingTargetKey,
                        onTrace = onTrace,
                    )
                    val automaticResult = dispatched.result
                    recentActions +=
                        "runtime ${automaticBookingInterception.code}: " +
                        summarize(automaticCall, automaticResult)
                    completedActions += 1
                    val actionReportedSuccess =
                        screenChangingActionSucceeded(automaticCall, automaticResult)
                    val verifyAsyncSeatAttempt =
                        automaticBookingInterception.code ==
                            "SELECT_GEOMETRIC_CENTER_SEAT" &&
                            automaticResult is DeviceToolResult.Action
                    if (actionReportedSuccess || verifyAsyncSeatAttempt) {
                        pendingVerification = PendingVerification(
                            callId = dispatched.callId,
                            actionName = automaticCall.name,
                            action = "${automaticCall.name}(${automaticCall.arguments})",
                            beforeSnapshot = observation.snapshot,
                            expectedChange = automaticBookingInterception.message,
                            targetKey = automaticBookingTargetKey,
                            runtimeCode = automaticBookingInterception.code,
                            showtimeMinutes = automaticShowtimeMinutes,
                        )
                        val automaticSettleMs =
                            AutomaticBookingSettlePolicy.delayMs(
                                automaticBookingInterception.code,
                            )
                        if (actionReportedSuccess) {
                            settleAfter(
                                call = automaticCall,
                                result = automaticResult,
                                delayMsOverride = automaticSettleMs,
                                step = displayedStep,
                                onTrace = onTrace,
                            )
                        } else {
                            delay(automaticSettleMs ?: ACTION_SETTLE_MS)
                        }
                        continue
                    }
                }

            val exhaustedShowtimes = exhaustedShowtimeMinutes(workspaceProvider())
            if (
                automaticBookingInterception == null &&
                seatDecision == null &&
                ExhaustedShowtimeFallbackPolicy.allFutureCandidatesExhausted(
                    contract = runContext.taskContract,
                    snapshot = observation.snapshot,
                    excludedShowtimeMinutes = exhaustedShowtimes,
                )
            ) {
                return AgentOutcome(
                    message =
                        "요청한 영화의 남은 모든 상영 회차에서 검증 가능한 중앙 좌석을 " +
                            "찾지 못했습니다. 중앙이 아닌 좌석은 선택하지 않았습니다.",
                    status = "모든 회차 중앙 좌석 없음",
                    steps = completedActions,
                    disposition = AgentRunDisposition.PAUSED,
                    stopReason = AgentStopReason.TASK_CONSTRAINT,
                )
            }

            val perception = preparePerception(
                context = context,
                goal = goal,
                model = model,
                plannerStep = plannerStep,
                displayedStep = displayedStep,
                observation = observation,
                repeatedUnchangedCount = repeatedUnchangedCount,
                previousPrivacyRoute = previousPrivacyRoute,
                onProgress = onProgress,
                onTrace = onTrace,
            )
            val privacy = perception.privacy
            val plannerSnapshot = perception.plannerSnapshot
            val screenElements = perception.screenElements
            previousPrivacyRoute = privacy.route
            val packageResources =
                if (
                    runContext.taskContract.allows(
                        AgentCapability.USE_STORED_CREDENTIALS,
                    )
                ) {
                    LocalCredentialRepository.publicCatalogForPackage(
                        context = context,
                        packageName = observation.snapshot.packageName,
                    )
                } else {
                    emptyList()
                }
            if (privacy.route == PrivacyRoute.USER_HANDOFF) {
                return AgentOutcome(
                    message = "결제 자격증명 또는 보안정보 화면을 감지했습니다. " +
                        "이 단계부터 직접 확인해 주세요.",
                    status = "개인정보 보호 · 사용자 확인 필요",
                    steps = completedActions,
                    disposition = AgentRunDisposition.PAUSED,
                    stopReason = AgentStopReason.USER_HANDOFF,
                )
            }
            if (privacy.route == PrivacyRoute.CLOUD_REDACTED) {
                val authenticationInterception = AuthenticationInterceptor.intercept(
                    contract = runContext.taskContract,
                    snapshot = observation.snapshot,
                    resources = packageResources,
                )
                val credentialAction = authenticationInterception
                    ?.automaticAction
                    ?.let { action ->
                        DeterministicCredentialAction(
                            code = authenticationInterception.code,
                            action = action,
                        )
                    }
                    ?: StoredCredentialFlowPolicy.memberLoginRecovery(
                        contract = runContext.taskContract,
                        snapshot = observation.snapshot,
                        resources = packageResources,
                    )
                if (credentialAction != null) {
                    val credentialCall = credentialAction.action.toDeviceToolCall()
                    onProgress(
                        when {
                            credentialAction.code == "AUTH_RESTORE_MEMBER_LOGIN" ->
                                "저장된 계정을 사용하도록 회원 로그인 화면으로 복구 중…"
                            credentialCall.name == FillSecretDeviceTool.NAME ->
                                "저장된 로그인 정보를 안전하게 입력하는 중…"
                            else ->
                                "인증 절차를 제출하는 중…"
                        },
                    )
                    Log.i(
                        TAG,
                        "runtime_interceptor step=$plannerStep " +
                            "code=${credentialAction.code} " +
                            "action=${credentialCall.name}",
                    )
                    val dispatched = actionDispatcher.dispatch(
                        step = displayedStep,
                        call = credentialCall,
                        snapshot = observation.snapshot,
                        onTrace = onTrace,
                    )
                    val credentialResult = dispatched.result
                    recentActions +=
                        "credential_broker:${credentialCall.name} -> " +
                        resultStatus(credentialResult)
                    completedActions += 1
                    when (CredentialBrokerResultPolicy.disposition(credentialResult)) {
                        CredentialBrokerDisposition.SUCCEEDED -> {
                            consecutiveCredentialRecoveryAttempts = 0
                            pendingVerification = PendingVerification(
                                callId = dispatched.callId,
                                actionName = credentialCall.name,
                                action = "${credentialCall.name}(<redacted>)",
                                beforeSnapshot = observation.snapshot,
                                expectedChange = null,
                                targetKey = null,
                            )
                            if (credentialAction.code == "AUTH_SUBMIT_READY") {
                                delay(AUTH_SUBMIT_SETTLE_MS)
                            } else {
                                settleAfter(
                                    call = credentialCall,
                                    result = credentialResult,
                                    step = displayedStep,
                                    onTrace = onTrace,
                                )
                            }
                            continue
                        }

                        CredentialBrokerDisposition.REOBSERVE_AND_RETRY -> {
                            consecutiveCredentialRecoveryAttempts += 1
                            if (
                                consecutiveCredentialRecoveryAttempts >=
                                MAX_CREDENTIAL_REOBSERVE_RETRIES
                            ) {
                                return AgentOutcome(
                                    message =
                                        "새 화면을 관찰한 뒤 저장된 로그인 입력을 " +
                                            "재시도했지만 반복해서 실패했습니다.",
                                    status = "런타임 인증 재시도 실패",
                                    steps = completedActions,
                                    disposition = AgentRunDisposition.FAILED,
                                    stopReason = AgentStopReason.TOOL_FAILURE,
                                )
                            }
                            recentActions +=
                                "credential broker requested a fresh observation; " +
                                    "the cloud planner was skipped"
                            onProgress(
                                "로그인 화면이 변경되어 다시 확인한 뒤 보안 입력을 재시도합니다…",
                            )
                            delay(CREDENTIAL_REOBSERVE_SETTLE_MS)
                            continue
                        }

                        CredentialBrokerDisposition.USER_HANDOFF -> {
                            val message = (credentialResult as? DeviceToolResult.Error)
                                ?.message
                                ?: "저장된 로그인 정보를 안전하게 확인할 수 없습니다."
                            return AgentOutcome(
                                message = message,
                                status = "보안 입력 · 사용자 승인 필요",
                                steps = completedActions,
                                disposition = AgentRunDisposition.PAUSED,
                                stopReason = AgentStopReason.USER_HANDOFF,
                            )
                        }

                        CredentialBrokerDisposition.FAIL ->
                            return failure(credentialResult, completedActions)
                    }
                }
            }

            if (plannerDecisionCount >= MAX_PLANNER_DECISIONS) {
                return AgentOutcome(
                    message =
                        "모델 판단 예산을 모두 사용했습니다. 검증된 자동 상태는 저장했으며 " +
                            "추가 모델 동작은 실행하지 않았습니다.",
                    status = "Gemini 판단 예산 도달",
                    steps = completedActions,
                    disposition = AgentRunDisposition.PAUSED,
                    stopReason = AgentStopReason.STEP_LIMIT,
                )
            }
            plannerDecisionCount += 1
            val plannerTurn = requestPlannerTurn(
                context = context,
                apiKey = apiKey,
                model = model,
                goal = goal,
                plannerStep = plannerDecisionCount,
                displayedStep = displayedStep,
                completedActions = completedActions,
                observation = observation,
                perception = perception,
                recentActions = recentActions,
                runContext = runContext,
                workspaceProvider = workspaceProvider,
                onTrace = onTrace,
            )
            when (plannerTurn) {
                is PlannerTurnResult.Retry -> {
                    recentActions += plannerTurn.feedback
                    onProgress(plannerTurn.progress)
                    continue
                }
                is PlannerTurnResult.Stop -> return plannerTurn.outcome
                is PlannerTurnResult.Decision -> Unit
            }
            plannerTurn as PlannerTurnResult.Decision
            val measuredDecision = plannerTurn.measured
            val plannerAction = measuredDecision.action
            val inferenceMs = plannerTurn.inferenceMs
            onTrace(
                AgentTraceEvent.PlannerDecision(
                    step = displayedStep,
                    model = model.apiId,
                    action = plannerAction.action,
                    reasonCode = plannerAction.reasonCode,
                    target = plannerAction.target,
                    expectedChange = plannerAction.expectedChange,
                    message = plannerAction.message,
                    latencyMs = inferenceMs,
                    requestBytes = measuredDecision.requestBytes,
                    promptTokenCount = measuredDecision.promptTokenCount,
                    candidatesTokenCount = measuredDecision.candidatesTokenCount,
                    totalTokenCount = measuredDecision.totalTokenCount,
                    plan = plannerAction.plan,
                    progressSummary = plannerAction.progressSummary,
                ),
            )
            Log.i(
                TAG,
                "${model.apiId} step=$plannerStep latency_ms=$inferenceMs " +
                    "action=${plannerAction.action} " +
                    "reason_code=${plannerAction.reasonCode.logValue()} " +
                    "target=${plannerAction.target.logValue()} " +
                    "expected_change=${plannerAction.expectedChange.logValue()}",
            )

            if (
                plannerAction.action == PlannerActionCatalog.WAIT &&
                observation.snapshot.isAgentHostGenerationSurface(context.packageName)
            ) {
                recentActions +=
                    "wait rejected: AGENT_HOST_UI. The foreground is the agent host and its " +
                    "generation/progress status is instrumentation. Never wait for it; choose " +
                    "a goal-directed action or finish when completion is observable."
                onProgress(
                    "${model.displayName} · 자체 상태를 제외하고 목표를 다시 판단 중…",
                )
                continue
            }

            when (plannerAction.action) {
                PlannerActionCatalog.FINISH_SUCCESS -> {
                    val completion = AgentCompletionEvaluator.evaluate(
                        runContext.taskContract,
                        observation.snapshot,
                        validatedSelections = workspaceProvider()
                            .selections
                            .filterValues { selection -> selection.validated }
                            .keys,
                        claimedEvidenceLabels = setOf(plannerAction.target),
                    )
                    if (completion.satisfied) {
                        return AgentOutcome(
                            message = plannerAction.message ?: "요청한 화면 작업을 완료했습니다.",
                            status = "${model.displayName} · 완료",
                            steps = completedActions,
                            disposition = AgentRunDisposition.SUCCEEDED,
                            stopReason = AgentStopReason.GOAL_COMPLETED,
                        )
                    }
                    recentActions +=
                        "rejected finish_success: missing observable completion evidence " +
                            completion.missing.joinToString()
                    onProgress("${model.displayName} · 완료 조건을 화면에서 재검증 중…")
                    continue
                }

                PlannerActionCatalog.FINISH_FAILURE ->
                    return AgentOutcome(
                        message = plannerAction.message ?: "현재 화면에서 안전하게 완료하지 못했습니다.",
                        status = "${model.displayName} · 완료하지 못함",
                        steps = completedActions,
                        disposition = AgentRunDisposition.FAILED,
                        stopReason = AgentStopReason.PLANNER_EXHAUSTED,
                    )
            }

            // Structured output guarantees the action name, but Gemini's JSON
            // schema cannot express action-specific required fields. Treat a
            // missing coordinate/node/text as planner feedback and re-observe
            // instead of terminating the whole agent loop with an exception.
            blockedElementActionReason(
                action = plannerAction,
                screenElements = screenElements,
                contract = runContext.taskContract,
            )?.let { reason ->
                return AgentOutcome(
                    message = reason,
                    status = "안전 정책 · 사용자 확인 필요",
                    steps = completedActions,
                    disposition = AgentRunDisposition.PAUSED,
                    stopReason = AgentStopReason.SAFETY_POLICY,
                )
            }
            val callConversion = runCatching {
                PlannerActionCatalog.toDeviceToolCall(
                    action = plannerAction,
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
            val proposedTarget = if (call.name == TapNodeDeviceTool.NAME) {
                val requestedId = call.arguments.optString("node_id")
                observation.snapshot.nodes.firstOrNull { it.id == requestedId }
            } else {
                null
            }
            StoredCredentialFlowPolicy.blockedGuestNavigationReason(
                contract = runContext.taskContract,
                resources = packageResources,
                target = tapTargetNode(call, observation),
            )?.let { reason ->
                Log.w(
                    TAG,
                    "${model.apiId} step=$plannerStep rejected_guest_fallback " +
                        "reason=$reason",
                )
                recentActions +=
                    "rejected ${call.name}: STORED_CREDENTIAL_GUEST_FALLBACK: $reason"
                if (
                    attemptHistory.recordRejection(
                        observation.snapshot.fingerprint.hash,
                        "STORED_CREDENTIAL_GUEST_FALLBACK",
                    )
                ) {
                    return AgentOutcome(
                        message = reason,
                        status = "런타임 비회원 전환 차단",
                        steps = completedActions,
                        disposition = AgentRunDisposition.FAILED,
                        stopReason = AgentStopReason.TASK_CONSTRAINT,
                    )
                }
                onProgress("${model.displayName} · 회원 로그인 경로로 복구 중…")
                continue
            }
            val proposedTargetKey = TaskStateConstraintInterceptor.stableTargetKey(
                contract = runContext.taskContract,
                target = proposedTarget,
                snapshot = observation.snapshot,
            )
            if (proposedTargetKey != null && proposedTargetKey in rejectedTargetKeys) {
                val reason =
                    "The target $proposedTargetKey previously produced a task-state " +
                        "mismatch. Do not select it again."
                recentActions += "rejected ${call.name}: REPEATED_INVALID_TARGET: $reason"
                if (
                    attemptHistory.recordRejection(
                        observation.snapshot.fingerprint.hash,
                        "REPEATED_INVALID_TARGET",
                    )
                ) {
                    return AgentOutcome(
                        message = reason,
                        status = "런타임 잘못된 선택 반복 차단",
                        steps = completedActions,
                        disposition = AgentRunDisposition.FAILED,
                        stopReason = AgentStopReason.TASK_CONSTRAINT,
                    )
                }
                onProgress("${model.displayName} · 이전의 잘못된 선택을 제외하고 재탐색 중…")
                continue
            }
            TaskStateConstraintInterceptor.correction(
                contract = runContext.taskContract,
                snapshot = observation.snapshot,
                proposedTarget = proposedTarget,
                excludedShowtimeMinutes = exhaustedShowtimeMinutes(
                    workspaceProvider(),
                ),
                validatedSelectionKeys = workspaceProvider()
                    .selections
                    .values
                    .filter { selection -> selection.validated }
                    .map { selection -> selection.candidateKey }
                    .toSet(),
            )?.let { interception ->
                Log.w(
                    TAG,
                    "${model.apiId} step=$plannerStep rejected_task_state " +
                        "code=${interception.code} reason=${interception.message}",
                )
                recentActions +=
                    "rejected ${call.name}: ${interception.code}: ${interception.message}"
                if (
                    attemptHistory.recordRejection(
                        observation.snapshot.fingerprint.hash,
                        interception.code,
                    )
                ) {
                    return AgentOutcome(
                        message = interception.message,
                        status = "런타임 영화·시간 조건 불일치",
                        steps = completedActions,
                        disposition = AgentRunDisposition.FAILED,
                        stopReason = AgentStopReason.TASK_CONSTRAINT,
                    )
                }
                onProgress("${model.displayName} · 영화·시간 조건에 맞는 선택을 재탐색 중…")
                continue
            }
            GoalInvariantInterceptor.correction(
                contract = runContext.taskContract,
                snapshot = observation.snapshot,
                proposedTarget = proposedTarget,
            )?.let { interception ->
                Log.w(
                    TAG,
                    "${model.apiId} step=$plannerStep rejected_goal_invariant " +
                        "code=${interception.code} reason=${interception.message}",
                )
                recentActions +=
                    "rejected ${call.name}: ${interception.code}: ${interception.message}"
                if (
                    attemptHistory.recordRejection(
                        observation.snapshot.fingerprint.hash,
                        interception.code,
                    )
                ) {
                    return AgentOutcome(
                        message = interception.message,
                        status = "런타임 목표 상태 불일치",
                        steps = completedActions,
                        disposition = AgentRunDisposition.FAILED,
                        stopReason = AgentStopReason.TASK_CONSTRAINT,
                    )
                }
                onProgress("${model.displayName} · 목표와 현재 선택 상태를 맞추는 중…")
                continue
            }
            recoverableGroundingError(
                call = call,
                observation = observation,
                contract = runContext.taskContract,
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
            val currentSemanticScreen =
                AgentActionVerifier.semanticSignature(observation.snapshot)
            if (
                currentSemanticScreen in unavailableOptionScreens &&
                call.targetsOptionSelector(observation.snapshot)
            ) {
                val reason =
                    "OPTION_NOT_FOUND already proved that this exact option is unavailable " +
                        "for the current item. Do not retry or tap this selector. Navigate " +
                        "back and choose a different candidate."
                recentActions += "rejected ${call.name}: REPEATED_UNAVAILABLE_OPTION: $reason"
                onProgress("${model.displayName} · 옵션 없는 상품을 제외하고 재탐색 중…")
                continue
            }
            ModalProgressGuard.correction(
                target = tapTargetNode(call, observation),
                snapshot = observation.snapshot,
            )?.let { reason ->
                Log.w(
                    TAG,
                    "${model.apiId} step=$plannerStep rejected_background_action " +
                        "reason=$reason",
                )
                recentActions += "rejected ${call.name}: $reason"
                if (
                    attemptHistory.recordRejection(
                        observation.snapshot.fingerprint.hash,
                        "BLOCKING_SURFACE_UNRESOLVED",
                    )
                ) {
                    return AgentOutcome(
                        message = "차단 안내 화면의 안전한 액션을 찾지 못했습니다.",
                        status = "런타임 차단 화면 미해결",
                        steps = completedActions,
                        disposition = AgentRunDisposition.FAILED,
                        stopReason = AgentStopReason.TOOL_FAILURE,
                    )
                }
                onProgress("${model.displayName} · 안내 창을 먼저 처리하는 중…")
                continue
            }
            SelectionProgressGuard.correction(
                goal = goal,
                targetLabel = tapTargetLabel(call, observation),
                visibleLabels = observation.snapshot.nodes
                    .asSequence()
                    .filter { node -> node.visibleToUser && node.enabled }
                    .map { node ->
                        listOfNotNull(node.text, node.contentDescription)
                            .joinToString(" ")
                    },
            )?.let { reason ->
                Log.w(
                    TAG,
                    "${model.apiId} step=$plannerStep rejected_deselect reason=$reason",
                )
                recentActions += "rejected ${call.name}: $reason"
                onProgress("${model.displayName} · 이미 선택된 항목의 다음 단계 찾는 중…")
                continue
            }
            blockedActionReason(
                call = call,
                observation = observation,
                contract = runContext.taskContract,
            )?.let { reason ->
                return AgentOutcome(
                    message = reason,
                    status = "안전 정책 · 사용자 확인 필요",
                    steps = completedActions,
                    disposition = AgentRunDisposition.PAUSED,
                    stopReason = AgentStopReason.SAFETY_POLICY,
                )
            }
            val semanticActionSignature = semanticActionSignature(
                call = call,
                observation = observation,
            )
            if (
                actionCycleGuard.wouldRepeatAlternatingCycle(
                    semanticActionSignature,
                ) ||
                actionCycleGuard.wouldRepeatStateAction(semanticActionSignature)
            ) {
                alternatingCycleRejections += 1
                val alternatives = visibleProgressControls(observation)
                Log.w(
                    TAG,
                    "alternating_cycle_rejected step=$plannerStep " +
                        "signature=$semanticActionSignature " +
                        "alternatives=$alternatives",
                )
                recentActions +=
                    "A two-screen A-B-A-B cycle was detected for the same visible target. " +
                    "Do not toggle that target again. Choose a different progress control. " +
                    "Visible completion/next controls: $alternatives"
                if (alternatingCycleRejections >= MAX_ALTERNATING_CYCLE_REJECTIONS) {
                    return AgentOutcome(
                        message = "두 화면 사이에서 같은 선택을 반복해 안전하게 중단했습니다.",
                        status = "Gemini 상태 사이클 감지",
                        steps = completedActions,
                        disposition = AgentRunDisposition.PAUSED,
                        stopReason = AgentStopReason.STALLED,
                    )
                }
                onProgress("${model.displayName} · 반복 경로를 다른 동작으로 보정 중…")
                continue
            }
            actionCycleGuard.record(semanticActionSignature)
            alternatingCycleRejections = 0
            val signature =
                "${observation.snapshot.fingerprint.hash}:${call.name}:${call.arguments}"
            repeatedSignatureCount =
                if (signature == previousSignature) repeatedSignatureCount + 1 else 1
            previousSignature = signature
            if (repeatedSignatureCount >= MAX_IDENTICAL_ACTIONS) {
                return AgentOutcome(
                    message = "같은 화면에서 동일 동작이 반복되어 안전하게 중단했습니다.",
                    status = "Gemini 반복 행동 감지",
                    steps = completedActions,
                    disposition = AgentRunDisposition.PAUSED,
                    stopReason = AgentStopReason.STALLED,
                )
            }

            Log.i(
                TAG,
                "trace_action step=$plannerStep action=${call.name} " +
                    "args=${safeArguments(call)} " +
                    "before=${observation.snapshot.fingerprint.hash.take(12)} " +
                    swipeContext(call, observation),
            )
            val dispatched = actionDispatcher.dispatch(
                step = displayedStep,
                call = call,
                snapshot = observation.snapshot,
                expectedChange = plannerAction.expectedChange,
                targetKey = proposedTargetKey,
                onTrace = onTrace,
            )
            val result = dispatched.result
            Log.i(
                TAG,
                "trace_result step=$plannerStep action=${call.name} " +
                    "result=${resultStatus(result)}",
            )
            recentActions += summarize(call, result)
            if (
                result is DeviceToolResult.Error &&
                result.code == "OPTION_NOT_FOUND"
            ) {
                unavailableOptionScreens +=
                    AgentActionVerifier.semanticSignature(observation.snapshot)
                recentActions +=
                    "OPTION_NOT_FOUND is definitive for the current item. " +
                        "Never retry this selector; navigate back and choose a different candidate."
            }
            completedActions += 1
            if (
                screenChangingActionSucceeded(call, result)
            ) {
                pendingVerification = PendingVerification(
                    callId = dispatched.callId,
                    actionName = call.name,
                    action = "${call.name}(${call.arguments})",
                    beforeSnapshot = observation.snapshot,
                    expectedChange = plannerAction.expectedChange,
                    targetKey = proposedTargetKey,
                )
            }
            if (result is DeviceToolResult.Error && result.code in NON_RECOVERABLE_ERRORS) {
                return failure(result, completedActions)
            }
            settleAfter(
                call = call,
                result = result,
                step = displayedStep,
                onTrace = onTrace,
            )
        }

        return AgentOutcome(
            message = "최대 실행 단계에 도달해 작업을 중단했습니다.",
            status = "Gemini 단계 제한 도달",
            steps = completedActions,
            disposition = AgentRunDisposition.PAUSED,
            stopReason = AgentStopReason.STEP_LIMIT,
        )
    }

    private suspend fun observeWithRetry(
        model: GeminiModel,
        displayedStep: Int,
        onProgress: (String) -> Unit,
        onTrace: suspend (AgentTraceEvent) -> Unit,
    ): DeviceToolResult {
        val call = DeviceToolCall(ObserveUiDeviceTool.NAME)
        var attempt = 0
        var observationStartedAt = SystemClock.elapsedRealtime()
        var result = execute(call)
        onTrace(
            AgentTraceEvent.LatencySample(
                step = displayedStep,
                stage = "observe_ui",
                durationMs = SystemClock.elapsedRealtime() - observationStartedAt,
                operation = call.name,
                attempt = attempt,
            ),
        )
        onTrace(
            AgentTraceEvent.ToolResult(
                step = displayedStep,
                call = call,
                result = result,
                automatic = true,
            ),
        )
        var retries = 0
        while (
            result is DeviceToolResult.Error &&
            result.code == TRANSIENT_UI_TREE_ERROR &&
            retries < MAX_TRANSIENT_OBSERVATION_RETRIES
        ) {
            retries += 1
            onProgress(
                "${model.displayName} · 앱 화면 로딩 대기 " +
                    "$retries/$MAX_TRANSIENT_OBSERVATION_RETRIES",
            )
            delay(TRANSIENT_OBSERVATION_RETRY_MS)
            attempt = retries
            observationStartedAt = SystemClock.elapsedRealtime()
            result = execute(call)
            onTrace(
                AgentTraceEvent.LatencySample(
                    step = displayedStep,
                    stage = "observe_ui",
                    durationMs = SystemClock.elapsedRealtime() - observationStartedAt,
                    operation = call.name,
                    attempt = attempt,
                ),
            )
            onTrace(
                AgentTraceEvent.ToolResult(
                    step = displayedStep,
                    call = call,
                    result = result,
                    automatic = true,
                ),
            )
        }
        return result
    }

    private suspend fun preparePerception(
        context: Context,
        goal: String,
        model: GeminiModel,
        plannerStep: Int,
        displayedStep: Int,
        observation: DeviceToolResult.UiObservation,
        repeatedUnchangedCount: Int,
        previousPrivacyRoute: PrivacyRoute?,
        onProgress: (String) -> Unit,
        onTrace: suspend (AgentTraceEvent) -> Unit,
    ): PreparedPerception {
        // Classify before OCR or screenshot capture. Sensitive screens use a
        // redacted accessibility snapshot and never attach pixels.
        var privacySnapshot = observation.snapshot
        var privacy = ScreenPrivacyRouter.route(privacySnapshot)
        var plannerSnapshot = if (privacy.route == PrivacyRoute.CLOUD_REDACTED) {
            RemoteObservationRedactor.redact(privacySnapshot)
        } else {
            privacySnapshot
        }
        var screenElements = ScreenElementFusion.fromAccessibility(plannerSnapshot)
        val explicitPopupDismissGoal = goal.isExplicitPopupDismissGoal()
        val ocrDecision = ScreenElementFusion.decideOcr(
            snapshot = observation.snapshot,
            repeatedUnchangedActions = repeatedUnchangedCount,
            explicitPopupDismissGoal = explicitPopupDismissGoal,
        )
        if (privacy.route == PrivacyRoute.CLOUD_OK && ocrDecision.shouldRun) {
            onProgress("${model.displayName} · UI 트리 보강을 위해 로컬 OCR 분석 중…")
            val ocrStarted = SystemClock.elapsedRealtime()
            val ocr = ocrAnalyzer
                .recognizeCurrentScreen(
                    maxDimension = SCREENSHOT_MAX_DIMENSION,
                    preferCloseIcons = explicitPopupDismissGoal,
                )
                .getOrNull()
            if (ocr != null) {
                screenElements = ScreenElementFusion.fuse(
                    snapshot = observation.snapshot,
                    ocr = ocr,
                    deviceWidth = context.resources.displayMetrics.widthPixels,
                    deviceHeight = context.resources.displayMetrics.heightPixels,
                )
                // OCR may reveal private text hidden by the accessibility
                // tree. Re-run the local privacy gate before serialization.
                privacySnapshot = ScreenElementFusion.privacySnapshot(
                    original = observation.snapshot,
                    elements = screenElements,
                )
                privacy = ScreenPrivacyRouter.route(privacySnapshot)
                plannerSnapshot = if (privacy.route == PrivacyRoute.CLOUD_REDACTED) {
                    RemoteObservationRedactor.redact(privacySnapshot)
                } else {
                    privacySnapshot
                }
                if (privacy.route == PrivacyRoute.CLOUD_REDACTED) {
                    screenElements = ScreenElementFusion.fromAccessibility(plannerSnapshot)
                }
                Log.i(
                    TAG,
                    "ocr_fusion step=$plannerStep latency_ms=" +
                        "${SystemClock.elapsedRealtime() - ocrStarted} " +
                        "ocr_ms=${ocr.recognitionMs} icon_ms=${ocr.iconDetectionMs} " +
                        "ocr_lines=${ocr.lines.size} close_icons=${ocr.closeIcons.size} " +
                        "elements=${screenElements.size} " +
                        "triggers=${ocrDecision.triggers.joinToString()} " +
                        "readable=${ocrDecision.readableLabels} " +
                        "actions=${ocrDecision.actionableNodes} " +
                        "unlabeled_actions=${ocrDecision.unlabeledActions} " +
                        "route=${privacy.route} reasons=${privacy.reasons.joinToString()}",
                )
            } else {
                Log.w(
                    TAG,
                    "ocr_fusion step=$plannerStep failed; using accessibility only " +
                        "triggers=${ocrDecision.triggers.joinToString()}",
                )
            }
        } else {
            Log.d(
                TAG,
                "ocr_skipped step=$plannerStep route=${privacy.route} " +
                    "readable=${ocrDecision.readableLabels} " +
                    "actions=${ocrDecision.actionableNodes} " +
                    "unlabeled_actions=${ocrDecision.unlabeledActions} " +
                    "reasons=${privacy.reasons.joinToString()}",
            )
        }

        if (privacy.route != previousPrivacyRoute) {
            onTrace(
                AgentTraceEvent.RuntimeRoute(
                    step = displayedStep,
                    runtime = when (privacy.route) {
                        PrivacyRoute.CLOUD_OK -> model.displayName
                        PrivacyRoute.CLOUD_REDACTED ->
                            "${model.displayName} · 민감값 마스킹"
                        PrivacyRoute.USER_HANDOFF -> "사용자 직접 확인"
                    },
                    reason = if (privacy.reasons.isEmpty()) {
                        "현재 화면에서 민감정보 신호가 발견되지 않았습니다."
                    } else {
                        "감지 신호: ${privacy.reasons.joinToString()}"
                    },
                ),
            )
        }
        return PreparedPerception(
            privacy = privacy,
            plannerSnapshot = plannerSnapshot,
            screenElements = screenElements,
        )
    }

    private suspend fun requestPlannerTurn(
        context: Context,
        apiKey: String,
        model: GeminiModel,
        goal: String,
        plannerStep: Int,
        displayedStep: Int,
        completedActions: Int,
        observation: DeviceToolResult.UiObservation,
        perception: PreparedPerception,
        recentActions: List<String>,
        runContext: AgentRunContext,
        workspaceProvider: () -> AgentWorkspace,
        onTrace: suspend (AgentTraceEvent) -> Unit,
    ): PlannerTurnResult {
        val startedAt = SystemClock.elapsedRealtime()
        val width = context.resources.displayMetrics.widthPixels
        val height = context.resources.displayMetrics.heightPixels
        fun request(
            snapshot: com.example.mobileguiagent.model.UiSnapshot,
            screenshot: DeviceToolResult.Screenshot? = null,
        ) = GeminiPlannerRequest(
            goal = goal,
            step = plannerStep,
            maxSteps = MAX_PLANNER_DECISIONS,
            screenWidth = width,
            screenHeight = height,
            observation = snapshot,
            screenElements = perception.screenElements,
            screenshot = screenshot,
            recentActions = recentActions.takeLast(MAX_RECENT_ACTIONS),
            skills = runContext.skills,
            taskContract = runContext.taskContract,
            workspace = workspaceProvider(),
            agentHostPackage = context.packageName,
        )

        var measured = planner.decide(
            apiKey = apiKey,
            model = model,
            request = request(perception.plannerSnapshot),
        )
        if (measured.action.action == PlannerActionCatalog.REQUEST_VISUAL) {
            if (perception.privacy.route != PrivacyRoute.CLOUD_OK) {
                return PlannerTurnResult.Retry(
                    feedback = "request_visual denied: sensitive screen; use redacted UI tree",
                    progress = "${model.displayName} · 민감 화면 UI 트리로 계속 판단 중…",
                )
            }
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
                return PlannerTurnResult.Stop(
                    failure(screenshot, completedActions),
                )
            }
            measured = planner.decide(
                apiKey = apiKey,
                model = model,
                request = request(observation.snapshot, screenshot),
            )
            if (measured.action.action == PlannerActionCatalog.REQUEST_VISUAL) {
                return PlannerTurnResult.Stop(
                    AgentOutcome(
                        message = "현재 화면을 이미지로 확인했지만 안전한 다음 동작을 정하지 못했습니다.",
                        status = "${model.displayName} · 화면 판단 실패",
                        steps = completedActions,
                        disposition = AgentRunDisposition.FAILED,
                        stopReason = AgentStopReason.PLANNER_EXHAUSTED,
                    ),
                )
            }
        }
        return PlannerTurnResult.Decision(
            measured = measured,
            inferenceMs = SystemClock.elapsedRealtime() - startedAt,
        )
    }

    private suspend fun execute(
        call: DeviceToolCall,
    ): DeviceToolResult =
        withContext(Dispatchers.IO) {
            deviceTools.execute(call)
        }

    private suspend fun settleAfter(
        call: DeviceToolCall,
        result: DeviceToolResult,
        delayMsOverride: Long? = null,
        step: Int,
        onTrace: suspend (AgentTraceEvent) -> Unit,
    ) {
        if (
            screenChangingActionSucceeded(call, result)
        ) {
            // The next loop's normal observe is the verification. A short
            // gesture settle avoids the previous fixed 1.2 s plus a second
            // verification pass on every action.
            val expectedDelayMs =
                delayMsOverride
                    ?: if (call.name == LaunchAppDeviceTool.NAME) {
                        LAUNCH_APP_SETTLE_MS
                    } else {
                        ACTION_SETTLE_MS
                    }
            val startedAt = SystemClock.elapsedRealtime()
            delay(expectedDelayMs)
            onTrace(
                AgentTraceEvent.LatencySample(
                    step = step,
                    stage = "action_settle",
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                    expectedMs = expectedDelayMs,
                    operation = call.name,
                ),
            )
        }
    }

    private fun blockedActionReason(
        call: DeviceToolCall,
        observation: DeviceToolResult.UiObservation,
        contract: TaskContract,
    ): String? {
        return when (call.name) {
            TapNodeDeviceTool.NAME -> {
                val nodeId = call.arguments.optString("node_id")
                val target = observation.snapshot.nodes.firstOrNull { node -> node.id == nodeId }
                AgentActionBoundaryPolicy.blockedTapReason(contract, target)
            }

            TapDeviceTool.NAME -> {
                val x = call.arguments.optDouble("x").toInt()
                val y = call.arguments.optDouble("y").toInt()
                observation.snapshot.nodes
                    .asSequence()
                    .filter { node ->
                        node.visibleToUser && node.enabled && node.bounds.contains(x, y)
                    }
                    .sortedBy { node -> node.bounds.width() * node.bounds.height() }
                    .mapNotNull { target ->
                        AgentActionBoundaryPolicy.blockedTapReason(contract, target)
                    }
                    .firstOrNull()
            }

            else -> null
        }
    }

    private fun screenChangingActionSucceeded(
        call: DeviceToolCall,
        result: DeviceToolResult,
    ): Boolean =
        (
            result is DeviceToolResult.Action &&
                result.success &&
                call.name != WaitDeviceTool.NAME
            ) ||
            (
                result is DeviceToolResult.Success &&
                    (
                        call.name == LaunchAppDeviceTool.NAME ||
                            call.name == ScrollDeviceTool.NAME
                        )
                )

    private fun semanticActionSignature(
        call: DeviceToolCall,
        observation: DeviceToolResult.UiObservation,
    ): String {
        val targetNode = tapTargetNode(call, observation)
        val actionTarget = targetNode
            ?.viewId
            ?.takeIf(String::isNotBlank)
            ?: tapTargetLabel(call, observation)
                ?.lowercase()
                ?.replace(Regex("""\s+"""), " ")
            ?: call.arguments.toString()
        val stateIdentity = if (!targetNode?.viewId.isNullOrBlank()) {
            observation.snapshot.packageName
        } else {
            AgentActionVerifier.semanticSignature(observation.snapshot)
        }
        return "$stateIdentity:${call.name}:$actionTarget"
    }

    private fun tapTargetLabel(
        call: DeviceToolCall,
        observation: DeviceToolResult.UiObservation,
    ): String? =
        tapTargetNode(call, observation)
            ?.let { node ->
                listOfNotNull(
                    node.text,
                    node.contentDescription,
                    node.viewId,
                ).joinToString(" ")
            }
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun tapTargetNode(
        call: DeviceToolCall,
        observation: DeviceToolResult.UiObservation,
    ) = when (call.name) {
            TapNodeDeviceTool.NAME -> {
                val requestedId = call.arguments.optString("node_id")
                observation.snapshot.nodes.firstOrNull { node -> node.id == requestedId }
            }
            TapDeviceTool.NAME -> {
                val x = call.arguments.optDouble("x").toInt()
                val y = call.arguments.optDouble("y").toInt()
                observation.snapshot.nodes
                    .filter { node ->
                        node.visibleToUser && node.enabled && node.bounds.contains(x, y)
                    }
                    .minByOrNull { node -> node.bounds.width() * node.bounds.height() }
            }
            else -> null
        }

    private fun visibleProgressControls(
        observation: DeviceToolResult.UiObservation,
    ): String = observation.snapshot.nodes
        .asSequence()
        .filter { node -> node.enabled && node.visibleToUser }
        .mapNotNull { node ->
            listOfNotNull(node.text, node.contentDescription)
                .joinToString(" ")
                .trim()
                .takeIf(String::isNotBlank)
        }
        .filter { label -> label.containsProgressTerm() }
        .distinct()
        .take(MAX_PROGRESS_CONTROL_HINTS)
        .joinToString()
        .ifBlank { "none" }

    private fun String.containsProgressTerm(): Boolean {
        val normalized = lowercase()
        return PROGRESS_TERMS.any(normalized::contains)
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
        contract: com.example.mobileguiagent.model.TaskContract,
    ): String? {
        if (
            call.name == TapNodeDeviceTool.NAME &&
            tapTargetNode(call, observation) == null
        ) {
            return "The proposed node_id is not present in the current observation. " +
                "Use only an exact current node or screen element id."
        }
        if (
            call.name == TapDeviceTool.NAME &&
            AgentSelectionPolicy.GEOMETRIC_CENTER in contract.selectionPolicies
        ) {
            val seatPatterns = contract.seatCandidatePatterns.mapNotNull { source ->
                runCatching { Regex(source, RegexOption.IGNORE_CASE) }.getOrNull()
            }.toSet()
            if (
                seatPatterns.isNotEmpty() &&
                SeatSelectionPolicy.resolve(observation.snapshot, seatPatterns)
                    .allSeats
                    .isNotEmpty()
            ) {
                return "A structured seat map is active. Coordinate taps cannot prove the " +
                    "requested geometric-center seat; use the runtime-selected exact seat node."
            }
        }
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

    private fun safeArguments(call: DeviceToolCall): String =
        when (call.name) {
            SetTextDeviceTool.NAME, FillSecretDeviceTool.NAME -> "<redacted>"
            else -> call.arguments.toString()
        }

    private fun String.logValue(): String =
        replace(Regex("""[\r\n\t]+"""), " ").take(240)

    private fun swipeContext(
        call: DeviceToolCall,
        observation: DeviceToolResult.UiObservation,
    ): String {
        if (call.name != SwipeDeviceTool.NAME) return ""
        val startX = call.arguments.optDouble("start_x")
        val startY = call.arguments.optDouble("start_y")
        val endX = call.arguments.optDouble("end_x")
        val endY = call.arguments.optDouble("end_y")
        val dx = endX - startX
        val dy = endY - startY
        val direction = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
            if (dx < 0) "left" else "right"
        } else {
            if (dy < 0) "up" else "down"
        }
        val targets = observation.snapshot.nodes
            .asSequence()
            .filter { node ->
                node.visibleToUser &&
                    node.scrollable &&
                    node.bounds.contains(startX.toInt(), startY.toInt())
            }
            .sortedBy { node -> node.bounds.width() * node.bounds.height() }
            .take(4)
            .map { node ->
                "${node.id}:${node.viewId ?: node.className}:${node.bounds.flattenToString()}"
            }
            .toList()
        return " swipe_direction=$direction dx=${dx.toInt()} dy=${dy.toInt()} " +
            "scroll_targets=$targets"
    }

    private fun blockedElementActionReason(
        action: GeminiPlannerAction,
        screenElements: List<ScreenElement>,
        contract: TaskContract,
    ): String? {
        if (action.action != PlannerActionCatalog.TAP_ELEMENT) return null
        val element = screenElements.firstOrNull { candidate ->
            candidate.id == action.elementId
        } ?: return null
        return AgentActionBoundaryPolicy.blockedLabelReason(
            contract,
            listOfNotNull(
                element.text,
                element.contentDescription,
                element.viewId,
            ).joinToString(" "),
        )
    }

    private fun String.isExplicitPopupDismissGoal(): Boolean {
        val normalized = lowercase()
        val popupContext = POPUP_TERMS.any(normalized::contains)
        val dismissIntent = DISMISS_TERMS.any(normalized::contains)
        return popupContext && dismissIntent
    }

    private fun com.example.mobileguiagent.model.UiSnapshot.isAgentHostGenerationSurface(
        hostPackage: String,
    ): Boolean {
        if (packageName != hostPackage) return false
        return nodes.any { node ->
            listOfNotNull(node.text, node.contentDescription).any { label ->
                HOST_GENERATION_TERMS.any(label.lowercase()::contains)
            }
        }
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
        is DeviceToolResult.Success ->
            "${call.name}(${call.arguments}) -> success=true"
        is DeviceToolResult.UiObservation ->
            "observe ${result.snapshot.packageName} nodes=${result.snapshot.nodes.size}"
    }

    private fun DeviceToolCall.targetsOptionSelector(
        snapshot: com.example.mobileguiagent.model.UiSnapshot,
    ): Boolean {
        if (
            name != com.example.mobileguiagent.device.SelectOptionDeviceTool.NAME &&
            name != TapNodeDeviceTool.NAME
        ) {
            return false
        }
        val nodeId = arguments.optString("node_id").takeIf(String::isNotBlank)
            ?: return false
        val node = snapshot.nodes.firstOrNull { it.id == nodeId } ?: return false
        val label = listOfNotNull(node.text, node.contentDescription, node.hint)
            .joinToString(" ")
            .lowercase()
        val widget = node.className.orEmpty().lowercase()
        val role = node.roleDescription.orEmpty().lowercase()
        return name == com.example.mobileguiagent.device.SelectOptionDeviceTool.NAME ||
            widget.contains("spinner") ||
            widget.contains("autocompletetextview") ||
            role.contains("dropdown") ||
            role.contains("drop-down") ||
            role.contains("combo") ||
            label.contains("옵션") ||
            label.contains("선택") ||
            label.contains("dropdown")
    }

    private fun failure(result: DeviceToolResult, steps: Int): AgentOutcome {
        val message = when (result) {
            is DeviceToolResult.Error -> "${result.code}: ${result.message}"
            else -> "필요한 화면 정보를 가져오지 못했습니다."
        }
        return AgentOutcome(
            message = message,
            status = "Gemini 기기 작업 실패",
            steps = steps,
            disposition = AgentRunDisposition.FAILED,
            stopReason = AgentStopReason.TOOL_FAILURE,
        )
    }

    private fun exhaustedShowtimeMinutes(workspace: AgentWorkspace): Set<Int> =
        workspace.facts.keys
            .asSequence()
            .filter { key ->
                key.startsWith(GeometricSeatInterceptor.EXHAUSTED_SHOWTIME_FACT_PREFIX)
            }
            .mapNotNull { key ->
                key.removePrefix(GeometricSeatInterceptor.EXHAUSTED_SHOWTIME_FACT_PREFIX)
                    .toIntOrNull()
            }
            .toSet()

    private fun clockText(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)

    private companion object {
        const val TAG = "GeminiAgentRunner"
        const val SEAT_TARGET_PREFIX = "seat:"
        // Opening an unpinned app through a launcher, handling startup UI,
        // searching, and reaching a reversible cart action routinely exceeds
        // 14 planner turns. Hard safety gates and repetition limits still stop
        // risky or stuck runs before this bounded ceiling.
        const val MAX_AGENT_CYCLES = 60
        const val MAX_PLANNER_DECISIONS = 20
        const val FINAL_VERIFICATION_DRAIN_CYCLES = 1
        const val MAX_IDENTICAL_ACTIONS = 3
        const val MAX_UNCHANGED_ACTIONS = 3
        const val MAX_ALTERNATING_CYCLE_REJECTIONS = 3
        const val MAX_PROGRESS_CONTROL_HINTS = 6
        const val MAX_RECENT_ACTIONS = 6
        const val MAX_TRANSIENT_OBSERVATION_RETRIES = 5
        const val MAX_CREDENTIAL_REOBSERVE_RETRIES = 3
        const val TRANSIENT_OBSERVATION_RETRY_MS = 400L
        const val CREDENTIAL_REOBSERVE_SETTLE_MS = 250L
        const val TRANSIENT_UI_TREE_ERROR = "UI_TREE_UNAVAILABLE"
        const val SCREENSHOT_MAX_DIMENSION = 1_024
        const val ACTION_SETTLE_MS = 350L
        const val PREFERRED_AFFORDANCE_WAIT_MS = 500L
        const val MAX_PREFERRED_AFFORDANCE_WAITS = 4
        const val AUTH_SUBMIT_SETTLE_MS = 1_200L
        const val LAUNCH_APP_SETTLE_MS = 2_500L
        val NON_RECOVERABLE_ERRORS = setOf(
            "ACCESSIBILITY_NOT_CONNECTED",
            "SCREENSHOT_SECURITY_EXCEPTION",
            "SCREENSHOT_UNSUPPORTED",
        )
        val SAFE_RESUME_REPLAN_TOOLS = setOf(
            TapNodeDeviceTool.NAME,
            SwipeDeviceTool.NAME,
            WaitDeviceTool.NAME,
            "go_back",
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
        val PROGRESS_TERMS = listOf(
            "선택 완료",
            "완료",
            "다음",
            "계속",
            "확인",
            "continue",
            "next",
            "done",
        )
        val HOST_GENERATION_TERMS = listOf(
            "답변 생성 중",
            "화면 판단",
            "generating",
            "analyzing screen",
        )
    }

    private data class PendingVerification(
        val callId: String,
        val actionName: String,
        val action: String,
        val beforeSnapshot: com.example.mobileguiagent.model.UiSnapshot,
        val expectedChange: String?,
        val targetKey: String?,
        val runtimeCode: String? = null,
        val showtimeMinutes: Int? = null,
    )

    private data class PreparedPerception(
        val privacy: PrivacyDecision,
        val plannerSnapshot: com.example.mobileguiagent.model.UiSnapshot,
        val screenElements: List<ScreenElement>,
    )

    private sealed interface PlannerTurnResult {
        data class Decision(
            val measured: GeminiMeasuredDecision,
            val inferenceMs: Long,
        ) : PlannerTurnResult

        data class Retry(
            val feedback: String,
            val progress: String,
        ) : PlannerTurnResult

        data class Stop(
            val outcome: AgentOutcome,
        ) : PlannerTurnResult
    }
}
