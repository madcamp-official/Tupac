package com.example.mobileguiagent.model

import android.content.Context
import android.util.Log
import com.example.minicpm_v_demo.LlamaEngine
import com.example.mobileguiagent.device.CaptureScreenDeviceTool
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.FinishDeviceTool
import com.example.mobileguiagent.device.FillSecretDeviceTool
import com.example.mobileguiagent.device.GoBackDeviceTool
import com.example.mobileguiagent.device.GoHomeDeviceTool
import com.example.mobileguiagent.device.ObserveUiDeviceTool
import com.example.mobileguiagent.device.TapDeviceTool
import com.example.mobileguiagent.device.TapNodeDeviceTool
import com.example.mobileguiagent.device.SetTextDeviceTool
import com.example.mobileguiagent.device.SubmitTextDeviceTool
import com.example.mobileguiagent.device.SwipeDeviceTool
import com.example.mobileguiagent.device.WaitDeviceTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class LocalAgentOutcome(
    val message: String,
    val status: String,
    val steps: Int,
)

sealed interface AgentTraceEvent {
    data class ToolCall(val step: Int, val call: DeviceToolCall) : AgentTraceEvent
    data class RuntimeRoute(
        val step: Int,
        val runtime: String,
        val reason: String,
    ) : AgentTraceEvent
    data class ToolResult(
        val step: Int,
        val call: DeviceToolCall,
        val result: DeviceToolResult,
        val automatic: Boolean = false,
    ) : AgentTraceEvent
}

/**
 * Remembers only the most recently vision-encoded rendered screen.
 *
 * Keeping one key allows A -> B -> A to be inspected again, while consecutive
 * retries on unchanged screen A reuse the model's prior visual proposal instead
 * of allocating the projector buffers again.
 */
internal class VisionEncodingBudget {
    private var latestKey: String? = null
    private var latestProposal: String? = null

    fun shouldEncode(
        screenshot: DeviceToolResult.Screenshot?,
        stableScreenKey: String? = null,
    ): Boolean =
        screenshot != null && keyOf(screenshot, stableScreenKey) != latestKey

    fun markEncoded(
        screenshot: DeviceToolResult.Screenshot,
        stableScreenKey: String? = null,
    ) {
        latestKey = keyOf(screenshot, stableScreenKey)
        latestProposal = null
    }

    fun rememberProposal(
        screenshot: DeviceToolResult.Screenshot,
        proposal: String,
        stableScreenKey: String? = null,
    ) {
        if (keyOf(screenshot, stableScreenKey) == latestKey) {
            latestProposal = proposal.take(MAX_VISUAL_PROPOSAL_LENGTH)
        }
    }

    fun priorProposal(
        screenshot: DeviceToolResult.Screenshot?,
        stableScreenKey: String? = null,
    ): String? =
        screenshot
            ?.takeIf { keyOf(it, stableScreenKey) == latestKey }
            ?.let { latestProposal }

    fun digestOf(screenshot: DeviceToolResult.Screenshot?): String? =
        screenshot?.let { keyOf(it, null) }

    private fun keyOf(
        screenshot: DeviceToolResult.Screenshot,
        stableScreenKey: String?,
    ): String {
        if (stableScreenKey != null) {
            return "${screenshot.width}x${screenshot.height}:state:$stableScreenKey"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(screenshot.jpegBytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "${screenshot.width}x${screenshot.height}:$digest"
    }

    private companion object {
        const val MAX_VISUAL_PROPOSAL_LENGTH = 400
    }
}

/**
 * Bounded observe-decide-act-verify loop for local vision-language models.
 *
 * Every tool result and a fresh post-action UI observation are fed into the
 * next model turn. The model chooses only one next action per turn; this class
 * owns execution limits, repeated-action detection, and completion.
 */
class LocalAgentController(
    private val toolAdapter: LocalDeviceToolAdapter,
) {
    suspend fun run(
        context: Context,
        engine: LlamaEngine,
        goal: String,
        initialCall: DeviceToolCall,
        visionAvailable: Boolean,
        modelProfile: OnDeviceModelProfile,
        onProgress: (String) -> Unit,
        onTrace: (AgentTraceEvent) -> Unit,
    ): LocalAgentOutcome {
        val history = mutableListOf<AgentStepRecord>()
        val visionEncodingBudget = VisionEncodingBudget()
        var previousActionSignature: String? = null
        var consecutiveActionCount = 0
        val display = context.resources.displayMetrics
        var nextCall = modelCallToDeviceCoordinates(
            call = initialCall,
            coordinateSpace = modelProfile.coordinateSpace,
            screenWidth = display.widthPixels,
            screenHeight = display.heightPixels,
        )
        var pendingSecretFill: DeviceToolCall? = null

        for (stepNumber in 1..MAX_AGENT_STEPS) {
            // Node IDs are traversal-local capabilities. They must come from
            // this run's latest observation, never from a copied resource ID.
            if (
                nextCall.name in OBSERVATION_REQUIRED_TOOLS &&
                history.none { it.result is DeviceToolResult.UiObservation }
            ) {
                if (nextCall.name == FillSecretDeviceTool.NAME) {
                    // Preserve only the opaque reference. The model-provided
                    // node ID is untrusted until a real UI observation exists.
                    pendingSecretFill = DeviceToolCall(
                        name = FillSecretDeviceTool.NAME,
                        arguments = JSONObject()
                            .put(
                                "secret_ref",
                                nextCall.arguments.optString("secret_ref").trim(),
                            ),
                    )
                }
                Log.w(
                    TAG,
                    "Rejected ${nextCall.name} without a current UI observation",
                )
                nextCall = DeviceToolCall(ObserveUiDeviceTool.NAME)
            }
            // A finish emitted before the model has seen the real phone screen
            // is not evidence of completion. Bootstrap the same generic
            // observe-decide-act loop instead of accepting an unverified finish.
            if (
                nextCall.name == FinishDeviceTool.NAME &&
                history.none { it.result is DeviceToolResult.UiObservation }
            ) {
                Log.w(TAG, "Rejected finish without current-screen evidence")
                nextCall = DeviceToolCall(ObserveUiDeviceTool.NAME)
            }

            onProgress("기기 작업 $stepNumber/$MAX_AGENT_STEPS · ${nextCall.name}")
            onTrace(AgentTraceEvent.ToolCall(stepNumber, nextCall))
            Log.i(TAG, "Step $stepNumber tool call: $nextCall")

            if (nextCall.name == FinishDeviceTool.NAME) {
                return LocalAgentOutcome(
                    message = nextCall.arguments.optString("message")
                        .trim()
                        .ifBlank { "작업을 마쳤습니다." },
                    status = "기기 작업 완료",
                    steps = stepNumber - 1,
                )
            }

            // The same gesture can be legitimate on two different screens
            // (for example, two list rows at the same coordinates). Count it as
            // a repeat only while the latest observable UI state is unchanged.
            val signature = repeatGuardSignature(
                call = nextCall,
                history = history,
                screenshotDigest = visionEncodingBudget.digestOf(
                    currentScreenshot(history),
                ),
            )
            consecutiveActionCount =
                if (signature == previousActionSignature) {
                    consecutiveActionCount + 1
                } else {
                    1
                }
            previousActionSignature = signature
            if (consecutiveActionCount >= MAX_IDENTICAL_ACTIONS) {
                return LocalAgentOutcome(
                    message = "같은 동작이 반복되어 작업을 중단했습니다.",
                    status = "반복 행동 감지",
                    steps = stepNumber - 1,
                )
            }

            val result = withContext(Dispatchers.IO) {
                toolAdapter.execute(nextCall)
            }
            history += AgentStepRecord(nextCall, result)
            onTrace(AgentTraceEvent.ToolResult(stepNumber, nextCall, result))
            Log.i(TAG, "Step $stepNumber tool result: ${toolAdapter.resultJson(result)}")

            if (
                result is DeviceToolResult.UiObservation &&
                pendingSecretFill != null
            ) {
                val editableCandidates = result.snapshot.nodes.filter { node ->
                    node.editable && node.enabled && node.visibleToUser
                }
                if (editableCandidates.size == 1) {
                    val pending = checkNotNull(pendingSecretFill)
                    nextCall = DeviceToolCall(
                        name = FillSecretDeviceTool.NAME,
                        arguments = JSONObject()
                            .put("node_id", editableCandidates.single().id)
                            .put(
                                "secret_ref",
                                pending.arguments.optString("secret_ref"),
                            ),
                    )
                    pendingSecretFill = null
                    Log.i(
                        TAG,
                        "Grounded pending fill_secret to the sole editable node " +
                            editableCandidates.single().id,
                    )
                    // A unique, observed editable node is a deterministic
                    // grounding result. Skip another slow LLM turn; the broker
                    // still enforces package, field role and one-time approval.
                    continue
                }
                // Multiple fields require semantic selection by the planner.
                // Never guess which one should receive a credential.
                pendingSecretFill = null
            }

            if (
                result is DeviceToolResult.Error &&
                result.code in NON_RECOVERABLE_ERRORS
            ) {
                return LocalAgentOutcome(
                    message = "툴 실행 실패 (${result.code}): ${result.message}",
                    status = "기기 작업 실패",
                    steps = stepNumber,
                )
            }

            val needsFreshObservation =
                result is DeviceToolResult.Action ||
                    (
                        result is DeviceToolResult.Error &&
                            nextCall.name in SCREEN_CHANGING_TOOLS
                        )
            if (needsFreshObservation) {
                if (
                    result is DeviceToolResult.Action &&
                    result.success &&
                    nextCall.name != WaitDeviceTool.NAME
                ) {
                    delay(ACTION_SETTLE_MS)
                }
                val observation = withContext(Dispatchers.IO) {
                    toolAdapter.execute(
                        DeviceToolCall(
                            name = ObserveUiDeviceTool.NAME,
                            arguments = JSONObject(),
                        ),
                    )
                }
                history += AgentStepRecord(
                    call = DeviceToolCall(ObserveUiDeviceTool.NAME),
                    result = observation,
                    automatic = true,
                )
                onTrace(
                    AgentTraceEvent.ToolResult(
                        step = stepNumber,
                        call = DeviceToolCall(ObserveUiDeviceTool.NAME),
                        result = observation,
                        automatic = true,
                    ),
                )
                Log.i(
                    TAG,
                    "Step $stepNumber post-action observation: " +
                        toolAdapter.resultJson(observation),
                )
            }

            // The planner should see the actual rendered screen before every
            // next decision. Screenshot capture is therefore owned by the
            // controller instead of relying on the model to request it.
            if (visionAvailable && result !is DeviceToolResult.Screenshot) {
                val screenshotCall = DeviceToolCall(
                    name = CaptureScreenDeviceTool.NAME,
                    arguments = JSONObject().put(
                        "max_dimension",
                        modelProfile.screenshotMaxDimension,
                    ),
                )
                val screenshot = withContext(Dispatchers.IO) {
                    toolAdapter.execute(screenshotCall)
                }
                history += AgentStepRecord(
                    call = screenshotCall,
                    result = screenshot,
                    automatic = true,
                )
                onTrace(
                    AgentTraceEvent.ToolResult(
                        step = stepNumber,
                        call = screenshotCall,
                        result = screenshot,
                        automatic = true,
                    ),
                )
                Log.i(
                    TAG,
                    "Step $stepNumber automatic screenshot: " +
                        toolAdapter.resultJson(screenshot),
                )
            }

            // A failed newest capture must not silently fall back to a stale
            // pre-action image. In that case the planner continues from the
            // fresh UI observation without being shown an outdated image.
            val latestScreenshot = currentScreenshot(history)
            val plannerDecision = decideNextAction(
                context = context,
                engine = engine,
                goal = goal,
                stepNumber = stepNumber,
                history = history,
                screenshot = latestScreenshot,
                visionAvailable = visionAvailable,
                modelProfile = modelProfile,
                visionEncodingBudget = visionEncodingBudget,
            )
            if (plannerDecision != null) {
                nextCall = plannerDecision
                continue
            }

            return LocalAgentOutcome(
                message = "현재 화면에서 유효한 다음 GUI 동작을 결정하지 못했습니다.",
                status = "툴 선택 실패",
                steps = stepNumber,
            )
        }

        return LocalAgentOutcome(
            message = "최대 실행 단계에 도달해 작업을 중단했습니다.",
            status = "단계 제한 도달",
            steps = MAX_AGENT_STEPS,
        )
    }

    /**
     * Repairs protocol mistakes inside the agent loop.
     *
     * The repair never maps an invented semantic tool (for example open_app)
     * to a preselected gesture. It gives the model the same current screenshot,
     * UI tree, skill, and registry again so the model must choose a real atomic
     * action from visible evidence.
     */
    private suspend fun decideNextAction(
        context: Context,
        engine: LlamaEngine,
        goal: String,
        stepNumber: Int,
        history: List<AgentStepRecord>,
        screenshot: DeviceToolResult.Screenshot?,
        visionAvailable: Boolean,
        modelProfile: OnDeviceModelProfile,
        visionEncodingBudget: VisionEncodingBudget,
    ): DeviceToolCall? {
        var validationFeedback: String? = null
        val stableScreenKey = stableVisionScreenKey(history)
        var visualProposal = visionEncodingBudget.priorProposal(
            screenshot = screenshot,
            stableScreenKey = stableScreenKey,
        )
        var attachScreenshotForRepair =
            screenshot != null &&
                visionAvailable &&
                visionEncodingBudget.shouldEncode(
                    screenshot = screenshot,
                    stableScreenKey = stableScreenKey,
                ) &&
                (
                    modelProfile.preferImagePlanning ||
                        latestObservationNeedsVision(history)
                    )
        var screenshotEncodedThisStep = false

        repeat(MAX_PLANNER_ATTEMPTS) { attemptIndex ->
            // Encoding the same image repeatedly is both redundant and very
            // memory-intensive on a phone. Each unchanged screen may enter the
            // vision projector at most once. Any protocol/schema correction
            // after that uses the UI tree plus the rejection feedback.
            val imageBackedAttempt =
                !screenshotEncodedThisStep &&
                    attachScreenshotForRepair &&
                    screenshot != null &&
                    visionAvailable
            val plannerPrompt =
                if (modelProfile.plannerPromptStyle == ModelPlannerPromptStyle.GUI_OWL) {
                    buildGuiOwlPlannerPrompt(
                        context = context,
                        goal = goal,
                        stepNumber = stepNumber,
                        history = history,
                        validationFeedback = validationFeedback,
                        screenshotAttached = imageBackedAttempt,
                        priorVisualProposal = visualProposal,
                    )
                } else {
                    buildPlannerPrompt(
                        goal = goal,
                        stepNumber = stepNumber,
                        context = context,
                        history = history,
                        validationFeedback = validationFeedback,
                        screenshotAttached = imageBackedAttempt,
                        screenshot = screenshot,
                        modelProfile = modelProfile,
                    )
                }
            val predictLength =
                when {
                    modelProfile.plannerPromptStyle == ModelPlannerPromptStyle.GUI_OWL ->
                        GUI_OWL_PLANNER_TOKEN_LIMIT
                    modelProfile.toolCallProtocol ==
                        ModelToolCallProtocol.EXAONE_JSON_DSL_FALLBACK ->
                        EXAONE_PLANNER_TOKEN_LIMIT
                    else -> PLANNER_TOKEN_LIMIT
                }
            val modelOutput = if (imageBackedAttempt) {
                screenshotEncodedThisStep = true
                visionEncodingBudget.markEncoded(
                    screenshot = checkNotNull(screenshot),
                    stableScreenKey = stableScreenKey,
                )
                engine.generateWithImage(
                    systemPrompt = plannerSystemPrompt(
                        context,
                        history,
                        goal,
                        modelProfile,
                    ),
                    userPrompt = plannerPrompt,
                    imageBytes = checkNotNull(screenshot).jpegBytes,
                    predictLength = predictLength,
                )
            } else {
                engine.generate(
                    systemPrompt = plannerSystemPrompt(
                        context,
                        history,
                        goal,
                        modelProfile,
                    ),
                    userPrompt = plannerPrompt,
                    predictLength = predictLength,
                )
            }.trim()
            if (imageBackedAttempt) {
                visualProposal = modelOutput.take(MAX_VISUAL_PROPOSAL_LENGTH)
                visionEncodingBudget.rememberProposal(
                    screenshot = checkNotNull(screenshot),
                    proposal = modelOutput,
                    stableScreenKey = stableScreenKey,
                )
            }
            Log.i(
                TAG,
                "Step $stepNumber planner attempt ${attemptIndex + 1} response: " +
                    modelOutput.take(LOG_TEXT_LIMIT),
            )

            val parsedCall = toolAdapter.parseToolCall(
                modelOutput,
                modelProfile.toolCallProtocol,
            )
            if (parsedCall != null) {
                val deviceCall = modelCallToDeviceCoordinates(
                    call = parsedCall,
                    coordinateSpace = modelProfile.coordinateSpace,
                    screenWidth = context.resources.displayMetrics.widthPixels,
                    screenHeight = context.resources.displayMetrics.heightPixels,
                )
                val callValidationError = validatePlannerCall(
                    call = deviceCall,
                    goal = goal,
                    controllerPackage = context.packageName,
                    history = history,
                    screenWidth = context.resources.displayMetrics.widthPixels,
                    screenHeight = context.resources.displayMetrics.heightPixels,
                    screenshotAvailable = screenshot != null && visionAvailable,
                    imageGroundingAvailable =
                        imageBackedAttempt ||
                            priorVisualProposalGrounds(
                                proposal = visualProposal,
                                proposedCall = deviceCall,
                                coordinateSpace = modelProfile.coordinateSpace,
                                screenWidth = context.resources.displayMetrics.widthPixels,
                                screenHeight = context.resources.displayMetrics.heightPixels,
                            ),
                )
                if (callValidationError == null) return deviceCall
                validationFeedback = callValidationError
                attachScreenshotForRepair =
                    !screenshotEncodedThisStep &&
                        callValidationError.contains(REQUIRES_SCREENSHOT_FEEDBACK)
                Log.w(
                    TAG,
                    "Step $stepNumber planner call rejected: $validationFeedback",
                )
                return@repeat
            }

            validationFeedback = plannerValidationFeedback(
                output = modelOutput,
                modelProfile = modelProfile,
            )
            attachScreenshotForRepair =
                !screenshotEncodedThisStep && modelProfile.preferImagePlanning
            Log.w(
                TAG,
                "Step $stepNumber planner output rejected: $validationFeedback",
            )
        }
        return null
    }

    private fun plannerSystemPrompt(
        context: Context,
        history: List<AgentStepRecord>,
        goal: String,
        modelProfile: OnDeviceModelProfile,
    ): String {
        if (modelProfile.plannerPromptStyle == ModelPlannerPromptStyle.GUI_OWL) {
            // This local Q4 checkpoint grounds direct action JSON more reliably
            // than an XML-wrapped aggregate function. The adapter translates
            // these aliases into the same registered Device Tools used by MCP;
            // this prompt contains no app/package/coordinate workflow.
            return buildString {
                appendLine("""# Android GUI action

Choose exactly one next action from the current screenshot.
The virtual screen resolution is 1000x1000 with top-left [0,0].
Return only the first complete JSON object and no prose or XML.

Allowed complete JSON forms:
{"tool":"click","arguments":{"coordinate":[number,number]}}
{"tool":"swipe","arguments":{"coordinate":[number,number],"coordinate2":[number,number]}}
{"tool":"type","arguments":{"text":"text"}}
{"tool":"system_button","arguments":{"button":"Home"}}
{"tool":"system_button","arguments":{"button":"Back"}}
{"tool":"wait","arguments":{"time":2}}
{"tool":"terminate","arguments":{"status":"success"}}
{"tool":"terminate","arguments":{"status":"failure"}}

Rules:
- Replace number and text placeholders with values grounded in the current screen.
- Click the center of a visible control, not the edge.
- If another app is required, use Home and navigate through visible launcher UI.
- Never invent open, open_app, package launch, shell, intent, or MCP actions.
- For a popup or WebView overlay, click its visible close control and inspect
  the newer screenshot on the next turn.
- Terminate success only when the newest screenshot visibly proves the whole
  task is complete.""")
                appendLine()
                appendLine("Navigation and recovery skill:")
                append(
                    AgentSkillLoader.load(
                        context,
                        AgentSkillLoader.GUI_OWL_NAVIGATION,
                    ),
                )
            }
        }
        return buildString {
        appendLine(
            """You are the action planner for an Android GUI agent.
Keep pursuing the user's original goal across multiple turns.
You receive real tool results, a fresh UI observation, and the current
rendered screenshot after actions. When an image is attached, it is the latest
screen; otherwise reason from the latest UI observation.
Choose exactly one next tool. Never invent a tool or XML tool-call syntax.
Prefer a node from the current observation over coordinate tap. Call observe_ui
only when no current observation exists.
Use the screenshot to understand popups, WebViews, visual-only controls, and
whether the previous action visibly succeeded. Use UI node ids when a matching
node exists; otherwise choose tap coordinates from the screenshot.
Do not call capture_screen: the controller already captured and attached it.
After a screen-changing action, inspect the newest screenshot and observation
before selecting a different action.
An automatic observe_ui result is already the newest screen state. Do not call
observe_ui again when that result contains a package name and nodes.
Never repeat the same action unless the latest screenshot proves it did not
take effect and repeating it is the safest recovery. If the goal is visible as
complete, call finish instead of observing or repeating an action.
For goals such as going Home, the launcher foreground package proves completion.
	Call finish only after the goal is visibly complete or safely impossible.
	Return only one JSON object matching the declared tool schema.""",
        )
        appendLine()
        appendLine(modelCoordinateInstruction(modelProfile.coordinateSpace))
        appendLine()
        appendLine("LOADED_SKILL:")
        appendLine(
            AgentSkillLoader.load(
                context,
                AgentSkillLoader.GUI_APP_NAVIGATION,
            ),
        )
        appendLine()
        append(
            toolAdapter.promptSectionFor(
                protocol = modelProfile.toolCallProtocol,
                excludedToolNames = plannerExcludedTools(
                    history = history,
                    goal = goal,
                    controllerPackage = context.packageName,
                ),
            ),
        )
        }
    }

    private fun plannerExcludedTools(
        history: List<AgentStepRecord>,
        goal: String,
        controllerPackage: String,
    ): Set<String> = buildSet {
        val latestObservationIndex = history.indexOfLast {
            it.result is DeviceToolResult.UiObservation
        }
        val latestActionIndex = history.indexOfLast {
            it.result is DeviceToolResult.Action
        }
        if (history.any { it.result is DeviceToolResult.Screenshot }) {
            add(CaptureScreenDeviceTool.NAME)
        }
        if (latestObservationIndex > latestActionIndex) {
            add(ObserveUiDeviceTool.NAME)
        }
        val latestObservation = history
            .asReversed()
            .firstNotNullOfOrNull { record ->
                record.result as? DeviceToolResult.UiObservation
            }
        if (
            latestObservation != null &&
            isOnLauncherSurface(history, latestObservation)
        ) {
            // Once Home has established which package represents this device's
            // launcher, returning Home again cannot advance launcher search.
            add(GoHomeDeviceTool.NAME)
        }
        val hasGroundedGoalNode =
            latestObservation?.snapshot?.nodes?.any { node ->
                node.enabled && node.clickable && labelsOverlapGoal(node.label(), goal)
            } == true
        val isControllerEchoScreen =
            latestObservation?.snapshot?.packageName == controllerPackage &&
                latestObservation.snapshot.nodes.any { node ->
                    node.enabled &&
                        !node.clickable &&
                        normalizedGroundingText(node.label()) ==
                        normalizedGroundingText(goal)
                }
        if (latestObservation?.snapshot?.nodes?.none { it.editable && it.enabled } != false) {
            add(SetTextDeviceTool.NAME)
            add(SubmitTextDeviceTool.NAME)
        }
        if (
            followsSuccessfulHome(history) &&
            !hasGroundedGoalNode
        ) {
            // Home exposes no grounded target. A coordinate or unrelated icon
            // tap is not an observation-driven choice; the loaded navigation
            // skill should advance with a reversible exploration gesture.
            add(TapDeviceTool.NAME)
            add(TapNodeDeviceTool.NAME)
        }
        if (isControllerEchoScreen) {
            add(TapDeviceTool.NAME)
            add(TapNodeDeviceTool.NAME)
            add(FinishDeviceTool.NAME)
        }
        if (
            latestObservation != null &&
            isOnLauncherSurface(history, latestObservation) &&
            !hasGroundedGoalNode
        ) {
            // The launcher cannot prove an unrelated named-app goal complete.
            // Keep finish unavailable until a grounded result is opened or the
            // foreground package changes away from this launcher surface.
            add(FinishDeviceTool.NAME)
        }
    }

    /**
     * GUI-Owl is action-tuned and performs best with the current screenshot and
     * concise textual state. Do not serialize UI nodes or prior tool objects:
     * they can distract its visual grounding or be copied as the next output.
     */
    private fun buildGuiOwlPlannerPrompt(
        context: Context,
        goal: String,
        stepNumber: Int,
        history: List<AgentStepRecord>,
        validationFeedback: String?,
        screenshotAttached: Boolean,
        priorVisualProposal: String?,
    ): String {
        val latestObservation = history
            .asReversed()
            .firstNotNullOfOrNull { record ->
                record.result as? DeviceToolResult.UiObservation
            }
        val recentActions = history
            .asSequence()
            .filterNot(AgentStepRecord::automatic)
            .toList()
            .takeLast(MAX_GUI_OWL_ACTION_HINTS)
            .joinToString("\n") { record ->
                val outcome = when (val result = record.result) {
                    is DeviceToolResult.Action ->
                        if (result.success) "dispatched; verify current image" else "failed"
                    is DeviceToolResult.Error -> "error:${result.code}"
                    is DeviceToolResult.UiObservation -> "observed"
                    is DeviceToolResult.Screenshot -> "captured"
                    is DeviceToolResult.Success -> "dispatched; verify current image"
                }
                "- ${record.call.actionHint()}: $outcome"
            }
            .ifBlank { "- none" }
        val display = context.resources.displayMetrics
        val accessibilityHintNodes = latestObservation
            ?.snapshot
            ?.nodes
            ?.asSequence()
            ?.filter { node ->
                node.enabled &&
                    node.bounds.width() > 0 &&
                    node.bounds.height() > 0 &&
                    node.bounds.centerX() in 0 until display.widthPixels &&
                    node.bounds.centerY() in 0 until display.heightPixels &&
                    (
                        node.clickable ||
                            node.editable ||
                            node.scrollable ||
                            !node.text.isNullOrBlank() ||
                            !node.contentDescription.isNullOrBlank()
                        )
            }
            ?.sortedByDescending { node ->
                guiOwlHintScore(node = node, goal = goal)
            }
            ?.distinctBy { node ->
                listOf(
                    node.text.orEmpty(),
                    node.contentDescription.orEmpty(),
                    node.viewId.orEmpty(),
                    node.bounds.flattenToString(),
                ).joinToString("|")
            }
            ?.take(MAX_GUI_OWL_ACCESSIBILITY_HINTS)
            ?.toList()
            .orEmpty()
        val accessibilityHints = accessibilityHintNodes
            .joinToString("\n") { node ->
                val label = listOfNotNull(
                    node.text?.takeIf(String::isNotBlank),
                    node.contentDescription?.takeIf(String::isNotBlank),
                ).joinToString(" / ")
                    .ifBlank { node.viewId.orEmpty().substringAfterLast('/') }
                    .replace('\n', ' ')
                    .take(MAX_GROUNDING_LABEL_LENGTH)
                val left = normalizeToVirtual(node.bounds.left, display.widthPixels)
                val top = normalizeToVirtual(node.bounds.top, display.heightPixels)
                val right = normalizeToVirtual(node.bounds.right, display.widthPixels)
                val bottom = normalizeToVirtual(node.bounds.bottom, display.heightPixels)
                val centerX = normalizeToVirtual(node.bounds.centerX(), display.widthPixels)
                val centerY = normalizeToVirtual(node.bounds.centerY(), display.heightPixels)
                "- \"$label\" center=[$centerX,$centerY] " +
                    "bounds=[$left,$top,$right,$bottom] " +
                    "clickable=${node.clickable} editable=${node.editable}"
            }
            .ifBlank { "- none" }
        val recommendedGrounding = run {
            val matchingTarget = accessibilityHintNodes.firstOrNull { node ->
                node.clickable && labelsOverlapGoal(node.label(), goal)
            }
            val editableSearch = accessibilityHintNodes.firstOrNull { node ->
                node.editable && "search" in node.viewId.orEmpty().lowercase()
            }
            val clickableSearch = accessibilityHintNodes.firstOrNull { node ->
                node.clickable && "search" in node.viewId.orEmpty().lowercase()
            }
            when {
                matchingTarget != null ->
                    "A visible target-matching control is grounded at " +
                        virtualCenter(matchingTarget, display.widthPixels, display.heightPixels) +
                        ". Click that control."
                editableSearch != null ->
                    "The current launcher search field is editable at " +
                        virtualCenter(editableSearch, display.widthPixels, display.heightPixels) +
                        ". Type the target app name from the task."
                clickableSearch != null ->
                    "The target is not visibly listed and the current launcher exposes a " +
                        "search control at " +
                        virtualCenter(clickableSearch, display.widthPixels, display.heightPixels) +
                        ". Click that search control now."
                else -> "No semantic next action is certain; inspect the screenshot."
            }
        }
        return buildString {
            appendLine("Task: $goal")
            appendLine("Step: ${stepNumber + 1}/$MAX_AGENT_STEPS")
            appendLine(
                "Screenshot: " +
                    if (screenshotAttached) "attached and current" else "not attached",
            )
            appendLine(
                "Foreground package: " +
                    latestObservation?.snapshot?.packageName.orEmpty().ifBlank { "unknown" },
            )
            appendLine("Recent actions:")
            appendLine(recentActions)
            appendLine(
                "Accessibility hints from the same current screen " +
                    "(virtual 0..1000 coordinates):",
            )
            appendLine(accessibilityHints)
            appendLine("Grounding recommendation: $recommendedGrounding")
            if (!validationFeedback.isNullOrBlank()) {
                appendLine("Rejected prior output: ${validationFeedback.take(240)}")
            }
            if (!priorVisualProposal.isNullOrBlank() && !screenshotAttached) {
                appendLine(
                    "Prior proposal grounded on this exact same rendered image " +
                        "(untrusted; correct it using current feedback):",
                )
                appendLine(priorVisualProposal.take(MAX_VISUAL_PROPOSAL_LENGTH))
            }
            append(
                if (screenshotAttached) {
                    "Inspect the image and return one allowed JSON action now."
                } else {
                    "Reason from the current accessibility hints, recent verified actions, " +
                        "and any same-screen visual proposal; return one allowed JSON action."
                },
            )
        }
    }

    private fun buildPlannerPrompt(
        goal: String,
        stepNumber: Int,
        context: Context,
        history: List<AgentStepRecord>,
        validationFeedback: String?,
        screenshotAttached: Boolean,
        screenshot: DeviceToolResult.Screenshot?,
        modelProfile: OnDeviceModelProfile,
    ): String {
        val display = context.resources.displayMetrics
        val recent = history.takeLast(MAX_HISTORY_RECORDS)
        val latestObservationIndex = recent.indexOfLast {
            it.result is DeviceToolResult.UiObservation
        }
        return buildString {
            appendLine("ORIGINAL_GOAL:")
            appendLine(goal)
            appendLine("CURRENT_STEP: ${stepNumber + 1}/$MAX_AGENT_STEPS")
            appendLine("DEVICE_SCREEN_PX: ${display.widthPixels}x${display.heightPixels}")
            appendLine(
                "MODEL_COORDINATE_SPACE: " +
                    when (modelProfile.coordinateSpace) {
                        ModelCoordinateSpace.DEVICE_PIXELS ->
                            "absolute DEVICE_SCREEN_PX; convert the visible image location " +
                                "to physical screen pixels"
                        ModelCoordinateSpace.NORMALIZED_1000 ->
                            "virtual 1000x1000; x=0..1000 left-to-right and y=0..1000 " +
                                "top-to-bottom; the controller converts it to device pixels"
                    },
            )
            if (screenshot != null) {
                appendLine(
                    "CAPTURED_IMAGE_PX: ${screenshot.width}x${screenshot.height}; it shows " +
                        "the entire ${display.widthPixels}x${display.heightPixels} display.",
                )
            }
            appendLine(
                "GROUNDING_NOTE: Text that repeats ORIGINAL_GOAL inside a conversation, log, " +
                    "or status node is not proof and is not a control. Check clickable and " +
                    "foreground_package. If the foreground screen is unrelated and exposes no " +
                    "route to the goal, follow the loaded skill's Home/launcher strategy.",
            )
            appendLine("TOOL_HISTORY_AND_LATEST_OBSERVATION:")
            appendLine(
                JSONArray(
                    recent.mapIndexed { index, record ->
                        // Keep the native UI snapshot complete for execution,
                        // but send a goal-aware bounded node projection to the
                        // 4K-context local planner.
                        val result = toolAdapter.resultJson(record.result, goal)
                        if (
                            result.optString("type") == "ui_observation" &&
                            index != latestObservationIndex
                        ) {
                            result.remove("nodes")
                        }
                        JSONObject()
                            .put("tool", record.call.name)
                            .put("arguments", record.call.arguments)
                            .put("automatic", record.automatic)
                            .put("result", result)
                    },
                ).toString(),
            )
            appendLine(
                "CURRENT_SCREENSHOT: " +
                    if (screenshotAttached) {
                        "attached to this prompt; inspect it before the next decision"
                    } else if (screenshot != null) {
                        "captured but not attached on this UI-tree-first attempt"
                    } else {
                        "unavailable; use the latest UI observation"
                    },
            )
            if (!validationFeedback.isNullOrBlank()) {
                appendLine("PREVIOUS_OUTPUT_VALIDATION_ERROR:")
                appendLine(validationFeedback)
                appendLine(
                    "Re-plan from the visible screen. Do not rename, translate, or invent a tool.",
                )
            }
            appendLine("Select the single next action now.")
        }
    }

    private fun validatePlannerCall(
        call: DeviceToolCall,
        goal: String,
        controllerPackage: String,
        history: List<AgentStepRecord>,
        screenWidth: Int,
        screenHeight: Int,
        screenshotAvailable: Boolean,
        imageGroundingAvailable: Boolean,
    ): String? {
        toolAdapter.validationError(call)?.let { error ->
            return "$error Match the exact input_schema for ${call.name}."
        }
        coordinateBoundsError(call, screenWidth, screenHeight)?.let { return it }
        val latestExplicitRecord = history.lastOrNull { !it.automatic }
        if (
            call.name == GoHomeDeviceTool.NAME &&
            latestExplicitRecord?.call?.name == GoHomeDeviceTool.NAME &&
            (latestExplicitRecord.result as? DeviceToolResult.Action)?.success == true
        ) {
            return "go_home already succeeded and the current launcher screen was observed. " +
                "Repeating it cannot advance the goal; follow the loaded GUI navigation skill."
        }
        val latestObservation = history
            .asReversed()
            .firstNotNullOfOrNull { record ->
                record.result as? DeviceToolResult.UiObservation
            }
        if (
            call.name == GoBackDeviceTool.NAME &&
            latestExplicitRecord?.call?.name == GoHomeDeviceTool.NAME &&
            (latestExplicitRecord.result as? DeviceToolResult.Action)?.success == true &&
            latestObservation != null &&
            isOnLauncherSurface(history, latestObservation)
        ) {
            return "go_back cannot advance from the launcher immediately after go_home. " +
                "Follow the navigation skill and explore the current launcher instead."
        }
        if (
            call.name == SetTextDeviceTool.NAME ||
            call.name == SubmitTextDeviceTool.NAME
        ) {
            if (latestObservation?.snapshot?.nodes?.none { it.editable && it.enabled } != false) {
                return "${call.name} is unavailable because the current UI has no editable " +
                    "node. Expose or focus a real input control first."
            }
        }
        if (call.name == FinishDeviceTool.NAME) {
            val latestObservation = history
                .asReversed()
                .firstNotNullOfOrNull { record ->
                    record.result as? DeviceToolResult.UiObservation
                }
            val goalGrounded = latestObservation?.snapshot?.nodes?.any { node ->
                node.enabled && labelsOverlapGoal(node.label(), goal)
            } == true
            if (
                latestObservation != null &&
                isOnLauncherSurface(history, latestObservation) &&
                !goalGrounded
            ) {
                return "finish is not grounded: the phone is still on the launcher and no " +
                    "visible node supports ORIGINAL_GOAL. Continue GUI exploration."
            }
            val isControllerEchoScreen =
                latestObservation?.snapshot?.packageName == controllerPackage &&
                    latestObservation.snapshot.nodes.any { node ->
                        node.enabled &&
                            !node.clickable &&
                            normalizedGroundingText(node.label()) ==
                            normalizedGroundingText(goal)
                    }
            if (isControllerEchoScreen) {
                return "finish is not grounded: ORIGINAL_GOAL is only visible as a " +
                    "non-clickable conversation echo in the agent UI."
            }
            unchangedScreenAfterLatestAction(history)?.let { unchangedAction ->
                return "finish is not grounded: the screenshot is byte-for-byte unchanged " +
                    "after $unchangedAction. The prior action did not visibly advance the goal; " +
                    "inspect the current image and choose a different recovery."
            }
        }
        if (call.name == ObserveUiDeviceTool.NAME) {
            val latestObservationIndex = history.indexOfLast {
                it.result is DeviceToolResult.UiObservation
            }
            val latestActionIndex = history.indexOfLast {
                it.result is DeviceToolResult.Action
            }
            if (latestObservationIndex > latestActionIndex) {
                return "observe_ui is redundant: the latest UI observation already describes " +
                    "the current screen. Choose an action from that evidence."
            }
        }
        if (
            call.name == CaptureScreenDeviceTool.NAME &&
            history.any { it.result is DeviceToolResult.Screenshot }
        ) {
            return "capture_screen is redundant: the controller already captured the current " +
                "screen. Choose the next action."
        }
        if (
            screenshotAvailable &&
            !imageGroundingAvailable &&
            call.name in VISUAL_PROOF_TOOLS
        ) {
            if (call.name == TapDeviceTool.NAME) {
                val latestObservation = history
                    .asReversed()
                    .firstNotNullOfOrNull { record ->
                        record.result as? DeviceToolResult.UiObservation
                    }
                val semanticNodes = latestObservation?.snapshot?.nodes
                    ?.filter { node ->
                        node.enabled &&
                            node.clickable &&
                            node.isLikelyNavigationControl()
                    }
                    .orEmpty()
                if (
                    latestObservation != null &&
                    isOnLauncherSurface(history, latestObservation) &&
                    semanticNodes.isNotEmpty()
                ) {
                    val candidates = semanticNodes.take(MAX_SEMANTIC_NODE_HINTS)
                        .joinToString { node ->
                            "${node.id}(${node.viewId.orEmpty().substringAfterLast('/')})"
                        }
                    return "Coordinate tap is unnecessary because the current launcher " +
                        "exposes semantic navigation nodes: $candidates. Use tap_node with " +
                        "the best visible control instead."
                }
            }
            return "${call.name} $REQUIRES_SCREENSHOT_FEEDBACK. Re-evaluate the same " +
                "screen with the attached image, then return the appropriate tool."
        }
        if (call.name == TapDeviceTool.NAME) {
            val latestObservation = history
                .asReversed()
                .firstNotNullOfOrNull { record ->
                    record.result as? DeviceToolResult.UiObservation
                }
            val isControllerEchoScreen =
                latestObservation?.snapshot?.packageName == controllerPackage &&
                    latestObservation.snapshot.nodes.any { node ->
                        node.enabled &&
                            !node.clickable &&
                            normalizedGroundingText(node.label()) ==
                            normalizedGroundingText(goal)
                    }
            if (isControllerEchoScreen) {
                return "tap is unavailable on the agent conversation screen because the only " +
                    "goal match is the user's non-clickable message. Navigate away first."
            }
            validateTapGrounding(call, goal, history)?.let { return it }
        }
        if (call.name == TapNodeDeviceTool.NAME) {
            validateNodeGrounding(
                call = call,
                goal = goal,
                controllerPackage = controllerPackage,
                history = history,
            )?.let { return it }
        }
        return null
    }

    /**
     * GUI-specialized models use a virtual coordinate space that is independent
     * of screenshot resizing. Convert only the registered coordinate tools; UI
     * node IDs and textual arguments remain untouched.
     */
    private fun modelCallToDeviceCoordinates(
        call: DeviceToolCall,
        coordinateSpace: ModelCoordinateSpace,
        screenWidth: Int,
        screenHeight: Int,
    ): DeviceToolCall {
        if (coordinateSpace != ModelCoordinateSpace.NORMALIZED_1000) return call
        val coordinateFields = when (call.name) {
            TapDeviceTool.NAME -> listOf("x" to screenWidth, "y" to screenHeight)
            SwipeDeviceTool.NAME -> listOf(
                "start_x" to screenWidth,
                "start_y" to screenHeight,
                "end_x" to screenWidth,
                "end_y" to screenHeight,
            )
            else -> return call
        }
        val arguments = JSONObject(call.arguments.toString())
        coordinateFields.forEach { (field, dimension) ->
            if (!arguments.has(field)) return@forEach
            val modelCoordinate = arguments.optDouble(field, Double.NaN)
            if (!modelCoordinate.isFinite()) return@forEach
            val deviceCoordinate =
                modelCoordinate / NORMALIZED_COORDINATE_MAX * (dimension - 1).coerceAtLeast(1)
            arguments.put(field, deviceCoordinate)
        }
        return call.copy(arguments = arguments)
    }

    private fun modelCoordinateInstruction(
        coordinateSpace: ModelCoordinateSpace,
    ): String = when (coordinateSpace) {
        ModelCoordinateSpace.DEVICE_PIXELS ->
            "For tap and swipe, output absolute physical pixels from DEVICE_SCREEN_PX. " +
                "The attached image may be resized, so scale its visible positions first."
        ModelCoordinateSpace.NORMALIZED_1000 ->
            "For tap and swipe, reason on a virtual 1000x1000 screen. Output every x and y " +
                "in the inclusive 0..1000 range; the controller performs the final scaling."
    }

    private fun latestObservationNeedsVision(
        history: List<AgentStepRecord>,
    ): Boolean {
        val observation = currentObservation(history)
            ?: return true
        val visibleSemantics = observation.snapshot.nodes.any { node ->
            node.enabled &&
                (
                    node.editable ||
                        node.scrollable ||
                        !node.text.isNullOrBlank() ||
                        !node.contentDescription.isNullOrBlank() ||
                        (
                            node.clickable &&
                                !node.className.orEmpty().contains(
                                    "WebView",
                                    ignoreCase = true,
                                )
                            )
                    )
        }
        val containsWebView = observation.snapshot.nodes.any { node ->
            node.className.orEmpty().contains("WebView", ignoreCase = true)
        }
        // Hybrid apps often expose a few outer layout nodes in addition to one
        // opaque WebView. Those incidental nodes do not describe visual-only
        // dialogs or ad close controls, so WebView presence itself requires the
        // current screenshot.
        return !visibleSemantics || containsWebView
    }

    /**
     * Rich native screens are identified by their accessibility fingerprint so
     * a clock tick, wallpaper animation, or JPEG metadata change does not spend
     * another vision pass. Opaque WebViews deliberately return null and remain
     * image-keyed because their accessibility fingerprint can stay constant
     * while a visual popup opens or closes.
     */
    private fun stableVisionScreenKey(history: List<AgentStepRecord>): String? {
        val observation = currentObservation(history) ?: return null
        val containsWebView = observation.snapshot.nodes.any { node ->
            node.className.orEmpty().contains("WebView", ignoreCase = true)
        }
        if (containsWebView) return null
        val semanticNodeCount = observation.snapshot.nodes.count { node ->
            node.enabled &&
                (
                    node.clickable ||
                        node.editable ||
                        node.scrollable ||
                        !node.text.isNullOrBlank() ||
                        !node.contentDescription.isNullOrBlank()
                    )
        }
        if (semanticNodeCount < MIN_STABLE_SCREEN_SEMANTIC_NODES) return null
        return "${observation.snapshot.packageName}:${observation.snapshot.fingerprint.hash}"
    }

    /**
     * A text-only repair may rely on the prior output only when that output was
     * grounded on this exact rendered screen and proposed the same action. This
     * prevents an image-grounded Back/Home proposal from authorizing a later
     * unrelated coordinate tap.
     */
    private fun priorVisualProposalGrounds(
        proposal: String?,
        proposedCall: DeviceToolCall,
        coordinateSpace: ModelCoordinateSpace,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        val priorParsed = proposal
            ?.let(toolAdapter::parseToolCall)
            ?: return false
        val priorCall = modelCallToDeviceCoordinates(
            call = priorParsed,
            coordinateSpace = coordinateSpace,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        if (priorCall.name != proposedCall.name) return false
        return when (proposedCall.name) {
            TapDeviceTool.NAME -> {
                val priorX = priorCall.arguments.optDouble("x", Double.NaN)
                val priorY = priorCall.arguments.optDouble("y", Double.NaN)
                val proposedX = proposedCall.arguments.optDouble("x", Double.NaN)
                val proposedY = proposedCall.arguments.optDouble("y", Double.NaN)
                if (
                    !priorX.isFinite() ||
                    !priorY.isFinite() ||
                    !proposedX.isFinite() ||
                    !proposedY.isFinite()
                ) {
                    false
                } else {
                    val deltaX = priorX - proposedX
                    val deltaY = priorY - proposedY
                    val tolerance =
                        maxOf(screenWidth, screenHeight) * VISUAL_REPAIR_TOLERANCE_RATIO
                    deltaX * deltaX + deltaY * deltaY <= tolerance * tolerance
                }
            }
            FinishDeviceTool.NAME -> true
            else -> priorCall.arguments.toString() == proposedCall.arguments.toString()
        }
    }

    private fun repeatGuardSignature(
        call: DeviceToolCall,
        history: List<AgentStepRecord>,
        screenshotDigest: String?,
    ): String {
        val state = currentObservation(history)
            ?.snapshot
            ?.let { snapshot ->
                "${snapshot.packageName}:${snapshot.fingerprint.hash}"
            }
            ?: "unobserved"
        return "${toolAdapter.callSignature(call)}@$state@${screenshotDigest.orEmpty()}"
    }

    private fun currentObservation(
        history: List<AgentStepRecord>,
    ): DeviceToolResult.UiObservation? {
        val latestAttempt = history
            .asReversed()
            .firstOrNull { record -> record.call.name == ObserveUiDeviceTool.NAME }
            ?: return null
        return latestAttempt.result as? DeviceToolResult.UiObservation
    }

    private fun currentScreenshot(
        history: List<AgentStepRecord>,
    ): DeviceToolResult.Screenshot? {
        val latestAttempt = history
            .asReversed()
            .firstOrNull { record -> record.call.name == CaptureScreenDeviceTool.NAME }
            ?: return null
        return latestAttempt.result as? DeviceToolResult.Screenshot
    }

    private fun DeviceToolCall.actionHint(): String = when (name) {
        TapDeviceTool.NAME ->
            "tap(${arguments.optDouble("x").toInt()},${arguments.optDouble("y").toInt()})"
        SwipeDeviceTool.NAME ->
            "swipe(" +
                "${arguments.optDouble("start_x").toInt()}," +
                "${arguments.optDouble("start_y").toInt()} -> " +
                "${arguments.optDouble("end_x").toInt()}," +
                "${arguments.optDouble("end_y").toInt()})"
        WaitDeviceTool.NAME ->
            "wait(${arguments.optLong("duration_ms", WaitDeviceTool.DEFAULT_DURATION_MS)}ms)"
        else -> name
    }

    private fun unchangedScreenAfterLatestAction(
        history: List<AgentStepRecord>,
    ): String? {
        val actionIndex = history.indexOfLast { record ->
            !record.automatic &&
                record.call.name in SCREEN_CHANGING_TOOLS &&
                (record.result as? DeviceToolResult.Action)?.success == true
        }
        if (actionIndex < 0) return null
        val before = history
            .take(actionIndex)
            .asReversed()
            .firstNotNullOfOrNull { it.result as? DeviceToolResult.Screenshot }
            ?: return null
        val after = history
            .drop(actionIndex + 1)
            .asReversed()
            .firstNotNullOfOrNull { it.result as? DeviceToolResult.Screenshot }
            ?: return null
        return history[actionIndex].call.name
            .takeIf { before.jpegBytes.contentEquals(after.jpegBytes) }
    }

    private fun coordinateBoundsError(
        call: DeviceToolCall,
        screenWidth: Int,
        screenHeight: Int,
    ): String? {
        val keys = call.arguments.keys()
        while (keys.hasNext()) {
            val field = keys.next()
            val limit = when {
                field == "x" || field.endsWith("_x") -> screenWidth
                field == "y" || field.endsWith("_y") -> screenHeight
                else -> continue
            }
            val value = call.arguments.optDouble(field, Double.NaN)
            if (!value.isFinite() || value < 0.0 || value > limit.toDouble()) {
                return "${call.name}.arguments.$field=$value is outside the current " +
                    "screen bounds ${screenWidth}x$screenHeight. Recalculate coordinates " +
                    "from DEVICE_SCREEN_PX."
            }
        }
        return null
    }

    private fun validateNodeGrounding(
        call: DeviceToolCall,
        goal: String,
        controllerPackage: String,
        history: List<AgentStepRecord>,
    ): String? {
        val nodeId = call.arguments.optString("node_id")
        val observation = history
            .asReversed()
            .firstNotNullOfOrNull { record ->
                record.result as? DeviceToolResult.UiObservation
            }
            ?: return null
        val node = observation.snapshot.nodes.firstOrNull { it.id == nodeId }
            ?: return "tap_node $nodeId is stale or absent from the latest UI observation. " +
                "Choose an exact current node id or use screenshot-grounded tap coordinates."
        if (observation.snapshot.packageName != controllerPackage) return null
        val label = node.label()
        if (
            !node.clickable &&
            normalizedGroundingText(label) == normalizedGroundingText(goal)
        ) {
            return "tap_node $nodeId is the non-clickable copy of ORIGINAL_GOAL shown in the " +
                "agent conversation, not a phone control. Choose a navigation action."
        }
        if (
            isOnLauncherSurface(history, observation) &&
            !followsSuccessfulSetText(history) &&
            node.clickable &&
            label.isNotBlank() &&
            !labelsOverlapGoal(label, goal) &&
            !node.isLikelyNavigationControl()
        ) {
            return "tap_node $nodeId points to unrelated launcher item \"$label\". The target " +
                "is not grounded in ORIGINAL_GOAL and is not a search/navigation control."
        }
        return null
    }

    private fun validateTapGrounding(
        call: DeviceToolCall,
        goal: String,
        history: List<AgentStepRecord>,
    ): String? {
        val x = call.arguments.optDouble("x", Double.NaN)
        val y = call.arguments.optDouble("y", Double.NaN)
        if (!x.isFinite() || !y.isFinite()) return null
        val observation = history
            .asReversed()
            .firstNotNullOfOrNull { record ->
                record.result as? DeviceToolResult.UiObservation
            }
            ?: return null
        val pointX = x.toInt()
        val pointY = y.toInt()
        val nodesAtPoint = observation.snapshot.nodes.filter { node ->
            node.enabled && node.bounds.contains(pointX, pointY)
        }
        val clickableNode = nodesAtPoint
            .filter { node -> node.clickable }
            .minByOrNull { node -> node.bounds.width() * node.bounds.height() }
        if (clickableNode != null) {
            val label = (clickableNode.text ?: clickableNode.contentDescription).orEmpty().trim()
            if (
                isOnLauncherSurface(history, observation) &&
                !followsSuccessfulSetText(history) &&
                label.isNotBlank() &&
                !labelsOverlapGoal(label, goal) &&
                !clickableNode.isLikelyNavigationControl()
            ) {
                return "tap ($pointX,$pointY) points to unrelated launcher item \"$label\". " +
                    "Explore or use a visible search/navigation control instead."
            }
            return null
        }
        val nonInteractiveLabel = nodesAtPoint
            .asSequence()
            .filter { node ->
                !node.clickable &&
                    (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())
            }
            .minByOrNull { node -> node.bounds.width() * node.bounds.height() }
            ?.let { node -> node.text ?: node.contentDescription }
            ?.trim()
            ?.take(MAX_GROUNDING_LABEL_LENGTH)
            ?: return null
        return "tap ($pointX,$pointY) overlaps non-clickable UI text " +
            "\"$nonInteractiveLabel\" and no clickable node. Do not tap conversation, log, " +
            "or status text as though it were a control; choose a grounded navigation action."
    }

    private fun normalizedGroundingText(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private fun guiOwlHintScore(node: UiNode, goal: String): Int {
        val label = node.label()
        val semanticId = node.viewId.orEmpty().lowercase()
        var score = 0
        if (labelsOverlapGoal(label, goal)) score += 2_000
        if (node.editable) score += 1_500
        if ("search" in semanticId) score += 1_200
        if (node.clickable && label.isNotBlank()) score += 800
        if (node.clickable) score += 400
        if (node.scrollable) score += 300
        if (label.isNotBlank()) score += 200
        return score
    }

    private fun normalizeToVirtual(value: Int, screenExtent: Int): Int =
        ((value.toDouble() / screenExtent.coerceAtLeast(1)) * VIRTUAL_SCREEN_EXTENT)
            .toInt()
            .coerceIn(0, VIRTUAL_SCREEN_EXTENT)

    private fun virtualCenter(node: UiNode, screenWidth: Int, screenHeight: Int): String =
        "[${normalizeToVirtual(node.bounds.centerX(), screenWidth)}," +
            "${normalizeToVirtual(node.bounds.centerY(), screenHeight)}]"

    private fun followsSuccessfulHome(history: List<AgentStepRecord>): Boolean {
        val latestExplicitRecord = history.lastOrNull { !it.automatic }
        return latestExplicitRecord?.call?.name == GoHomeDeviceTool.NAME &&
            (latestExplicitRecord.result as? DeviceToolResult.Action)?.success == true
    }

    private fun followsSuccessfulSetText(history: List<AgentStepRecord>): Boolean {
        val latestExplicitRecord = history.lastOrNull { !it.automatic }
        return latestExplicitRecord?.call?.name == SetTextDeviceTool.NAME &&
            (latestExplicitRecord.result as? DeviceToolResult.Action)?.success == true
    }

    private fun isOnLauncherSurface(
        history: List<AgentStepRecord>,
        currentObservation: DeviceToolResult.UiObservation,
    ): Boolean {
        val homeRecordIndex = history.indexOfLast { record ->
            !record.automatic &&
                record.call.name == GoHomeDeviceTool.NAME &&
                (record.result as? DeviceToolResult.Action)?.success == true
        }
        if (homeRecordIndex < 0) return false
        val homeObservation = history
            .drop(homeRecordIndex + 1)
            .firstNotNullOfOrNull { record ->
                record.result as? DeviceToolResult.UiObservation
            }
            ?: return false
        return homeObservation.snapshot.packageName == currentObservation.snapshot.packageName
    }

    private fun UiNode.label(): String =
        (text ?: contentDescription).orEmpty().trim()

    private fun UiNode.isLikelyNavigationControl(): Boolean {
        if (editable || scrollable) return true
        val semanticId = viewId.orEmpty().lowercase()
        return NAVIGATION_ID_HINTS.any(semanticId::contains)
    }

    private fun labelsOverlapGoal(label: String, goal: String): Boolean {
        val normalizedLabel = normalizedGroundingText(label)
        val normalizedGoal = normalizedGroundingText(goal)
        if (
            normalizedLabel.length >= MIN_GROUNDING_TOKEN_LENGTH &&
            normalizedGoal.contains(normalizedLabel)
        ) {
            return true
        }
        val labelTokens = label.lowercase()
            .split(NON_WORD_SEPARATOR)
            .filter { it.length >= MIN_GROUNDING_TOKEN_LENGTH }
        val goalTokens = goal.lowercase()
            .split(NON_WORD_SEPARATOR)
            .filter { it.length >= MIN_GROUNDING_TOKEN_LENGTH }
        return labelTokens.any { labelToken ->
            goalTokens.any { goalToken ->
                labelToken.contains(goalToken) || goalToken.contains(labelToken)
            }
        }
    }

    private fun plannerValidationFeedback(
        output: String,
        modelProfile: OnDeviceModelProfile,
    ): String {
        val requestedTool = toolAdapter.requestedToolName(
            output,
            modelProfile.toolCallProtocol,
        )
        if (modelProfile.plannerPromptStyle == ModelPlannerPromptStyle.GUI_OWL) {
            return when (requestedTool) {
                in GUI_OWL_OUTPUT_TOOL_NAMES ->
                    "\"$requestedTool\" was recognized, but its arguments were incomplete " +
                        "or invalid. Return one complete direct JSON action with every required " +
                        "value grounded in the current screenshot."
                null ->
                    "Return only one complete JSON object with tool set to click, swipe, type, " +
                        "system_button, wait, or terminate. Do not use prose, XML, or placeholders."
                else ->
                    "\"$requestedTool\" is unsupported. Use one declared direct JSON action."
            }
        }
        if (requestedTool != null) {
            return "\"$requestedTool\" is not a registered tool and was not executed. " +
                "Choose one exact name from AVAILABLE_DEVICE_TOOLS."
        }
        val normalized = output.lowercase()
        if (COMPLETION_STATUSES.any { status -> normalized.contains(status) }) {
            return "Completion must call the registered finish tool, and only after the " +
                "current screenshot visibly proves the original goal."
        }
        return if (
            modelProfile.toolCallProtocol == ModelToolCallProtocol.EXAONE_JSON_DSL_FALLBACK
        ) {
            "The response was neither canonical tool-call JSON nor one valid compact " +
                "EXAONE fallback line. Return one action without reasoning or <think> blocks."
        } else {
            "The response was not one valid registered tool-call JSON object. " +
                "Return exactly one tool call and no prose or XML."
        }
    }

    private data class AgentStepRecord(
        val call: DeviceToolCall,
        val result: DeviceToolResult,
        val automatic: Boolean = false,
    )

    companion object {
        private const val TAG = "LocalAgentController"
        private const val MAX_AGENT_STEPS = 18
        private const val MAX_IDENTICAL_ACTIONS = 3
        private const val MAX_HISTORY_RECORDS = 6
        private const val MAX_PLANNER_ATTEMPTS = 3
        // The following automatic observe is the verification step. Keep only
        // a short gesture settle instead of paying a fixed 1.2 s every turn.
        private const val ACTION_SETTLE_MS = 350L
        private const val PLANNER_TOKEN_LIMIT = 256
        private const val GUI_OWL_PLANNER_TOKEN_LIMIT = 128
        private const val EXAONE_PLANNER_TOKEN_LIMIT = 128
        private const val LOG_TEXT_LIMIT = 800
        private const val MAX_VISUAL_PROPOSAL_LENGTH = 400
        private const val MIN_STABLE_SCREEN_SEMANTIC_NODES = 3
        private const val VISUAL_REPAIR_TOLERANCE_RATIO = 0.04
        private const val MAX_GROUNDING_LABEL_LENGTH = 80
        private const val MAX_SEMANTIC_NODE_HINTS = 5
        private const val MAX_GUI_OWL_ACTION_HINTS = 4
        private const val MAX_GUI_OWL_ACCESSIBILITY_HINTS = 12
        private const val VIRTUAL_SCREEN_EXTENT = 1_000
        private val GUI_OWL_OUTPUT_TOOL_NAMES = setOf(
            "mobile_use",
            "click",
            "swipe",
            "type",
            "system_button",
            "wait",
            "terminate",
        )
        private const val REQUIRES_SCREENSHOT_FEEDBACK =
            "requires current screenshot evidence"
        private const val MIN_GROUNDING_TOKEN_LENGTH = 2
        private const val NORMALIZED_COORDINATE_MAX = 1_000.0
        private val NON_WORD_SEPARATOR = Regex("""[^\p{L}\p{N}]+""")
        private val NAVIGATION_ID_HINTS = listOf(
            "search",
            "drawer",
            "page",
            "apps",
            "all_app",
        )
        private val COMPLETION_STATUSES = setOf(
            "complete",
            "completed",
            "success",
            "done",
        )
        private val VISUAL_PROOF_TOOLS = setOf(
            TapDeviceTool.NAME,
            FinishDeviceTool.NAME,
        )
        private val SCREEN_CHANGING_TOOLS = setOf(
            GoBackDeviceTool.NAME,
            GoHomeDeviceTool.NAME,
            TapNodeDeviceTool.NAME,
            SetTextDeviceTool.NAME,
            SubmitTextDeviceTool.NAME,
            TapDeviceTool.NAME,
            SwipeDeviceTool.NAME,
            FillSecretDeviceTool.NAME,
        )
        private val OBSERVATION_REQUIRED_TOOLS = setOf(
            TapNodeDeviceTool.NAME,
            FillSecretDeviceTool.NAME,
        )
        private val NON_RECOVERABLE_ERRORS = setOf(
            "ACCESSIBILITY_NOT_CONNECTED",
            "UNSUPPORTED_ANDROID_VERSION",
        )
    }
}
