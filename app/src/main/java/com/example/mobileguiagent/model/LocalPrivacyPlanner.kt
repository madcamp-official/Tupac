package com.example.mobileguiagent.model

import android.content.Context
import android.util.Log
import com.example.minicpm_v_demo.LlamaEngine
import com.example.mobileguiagent.agent.PrivateScreenActionPolicy
import com.example.mobileguiagent.agent.ShoppingActionPolicy
import com.example.mobileguiagent.credentials.PublicCredentialDescriptor
import com.example.mobileguiagent.device.CaptureScreenDeviceTool
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolExecutor
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.FillSecretDeviceTool
import com.example.mobileguiagent.device.GoBackDeviceTool
import com.example.mobileguiagent.device.SwipeDeviceTool
import com.example.mobileguiagent.device.TapNodeDeviceTool
import com.example.mobileguiagent.device.WaitDeviceTool
import org.json.JSONArray
import org.json.JSONObject

sealed interface LocalPrivacyPlan {
    data class Tool(val call: DeviceToolCall) : LocalPrivacyPlan
    data class Handoff(val message: String) : LocalPrivacyPlan
}

/**
 * One-step local planner used only after the on-device privacy router blocks a
 * cloud call. It does not own a second agent loop: after its one reversible
 * action, the shared controller observes the new screen and routes again.
 */
class LocalPrivacyPlanner(
    private val toolAdapter: LocalDeviceToolAdapter,
    private val deviceTools: DeviceToolExecutor,
) {
    @Volatile
    private var activeEngine: LlamaEngine? = null

    suspend fun decide(
        context: Context,
        goal: String,
        snapshot: UiSnapshot,
        allowedResources: List<PublicCredentialDescriptor> = emptyList(),
    ): LocalPrivacyPlan {
        val resolved = OnDeviceModelProfileResolver.resolve(context)
        if (!resolved.ready) {
            return LocalPrivacyPlan.Handoff(
                "개인정보 화면을 감지했지만 로컬 모델이 준비되지 않았습니다. " +
                    "이 화면부터 직접 진행해 주세요.",
            )
        }

        val engine = LlamaEngine.getInstance(context.applicationContext)
        activeEngine = engine
        return try {
            // Private screens often expose a large WebView tree containing
            // credentials, banners, and hidden controls. Give the small local
            // model only reversible navigation candidates. The controller
            // validates the selected node against the complete live snapshot
            // again immediately before execution.
            val candidateNodes = snapshot.nodes
                .asSequence()
                .filter { node ->
                    node.enabled &&
                        node.clickable &&
                        !node.editable &&
                        !node.password &&
                        node.isMeaningfulForAgent() &&
                        !goalExplicitlyRejects(node, goal) &&
                        PrivateScreenActionPolicy.blockedLocalTapReason(node, goal) == null &&
                        ShoppingActionPolicy.blockedTapReason(node) == null
                }
                .sortedByDescending { node -> privateCandidateScore(node, goal) }
                .take(MAX_LOCAL_NODES)
                .toList()
            val nodes = candidateNodes
                .asSequence()
                .map { node ->
                    JSONObject()
                        .put("id", node.id)
                        .put("text", node.text.orEmpty())
                        .put("description", node.contentDescription.orEmpty())
                        .put("view_id", node.viewId.orEmpty())
                        .put("clickable", node.clickable)
                        .put("editable", node.editable)
                        .put("password", node.password)
                        .put(
                            "bounds",
                            JSONArray(
                                listOf(
                                    node.bounds.left,
                                    node.bounds.top,
                                    node.bounds.right,
                                    node.bounds.bottom,
                                ),
                            ),
                        )
                }
                .toList()
            val editableNodes = snapshot.nodes
                .asSequence()
                .filter { node ->
                    node.enabled &&
                        node.visibleToUser &&
                        node.editable
                }
                .take(MAX_LOCAL_EDITABLE_NODES)
                .map { node ->
                    JSONObject()
                        .put("id", node.id)
                        .put("text", node.text.orEmpty())
                        .put("description", node.contentDescription.orEmpty())
                        .put("view_id", node.viewId.orEmpty())
                        .put("password", node.password)
                        .put("input_type", node.inputType)
                }
                .toList()
            val bestGoalCandidate = candidateNodes.firstOrNull()
                ?.takeIf { node ->
                    privateCandidateScore(node, goal) >= STRONG_GOAL_MATCH_SCORE
                }
            val prompt = buildPrompt(
                goal = goal,
                packageName = snapshot.packageName,
                nodes = nodes,
                editableNodes = editableNodes,
                allowedResources = allowedResources,
                bestGoalCandidate = bestGoalCandidate,
            )
            val screenshot = captureOnlyWhenTreeIsInsufficient(
                snapshot = snapshot,
                visionAvailable = resolved.visionAvailable,
                maxDimension = resolved.profile.screenshotMaxDimension,
            )
            // Load the vision projector only when this particular observation
            // actually needs an image. Text-grounded private screens avoid the
            // expensive mmproj load and image-prefill path entirely.
            engine.loadModel(
                modelFile = resolved.modelFile,
                visionProjectorFile = if (screenshot != null) {
                    resolved.visionProjectorFile
                } else {
                    null
                },
                modelFamilyHint = resolved.profile.nativeModelFamilyHint,
                imageMaxSliceNums = resolved.profile.imageMaxSliceNums,
                modelDisplayName = resolved.profile.displayName,
                disableThinking = resolved.profile.disableThinking,
            )
            val output = if (screenshot != null) {
                engine.generateWithImage(
                    systemPrompt = SYSTEM_PROMPT,
                    userPrompt = prompt + "\nThe current local screenshot is attached.",
                    imageBytes = screenshot.jpegBytes,
                    predictLength = RESPONSE_TOKENS,
                )
            } else {
                engine.generate(
                    systemPrompt = SYSTEM_PROMPT,
                    userPrompt = prompt,
                    predictLength = RESPONSE_TOKENS,
                )
            }.trim()
            // The private planner is constrained to a single tool JSON object.
            // Logging that bounded decision makes device QA auditable without
            // logging the private UI tree or screenshot that produced it.
            Log.i(TAG, "local_plan=${output.replace('\n', ' ').take(MAX_LOGGED_OUTPUT)}")
            val parsed = parsePlan(output)
            if (
                bestGoalCandidate != null &&
                !parsed.tapsNode(bestGoalCandidate.id)
            ) {
                // A tiny local model can describe the right visible control in
                // prose yet fail to emit the strict tool envelope. Correct only
                // to a policy-approved candidate whose visible label is
                // explicitly present (and not negated) in the user's goal.
                Log.w(
                    TAG,
                    "local_plan_corrected target=${bestGoalCandidate.id} " +
                        "label=${listOfNotNull(
                            bestGoalCandidate.text,
                            bestGoalCandidate.contentDescription,
                        ).joinToString(" ").take(MAX_LOGGED_LABEL)} " +
                        "model_plan=${parsed::class.java.simpleName}",
                )
                LocalPrivacyPlan.Tool(
                    DeviceToolCall(
                        name = TapNodeDeviceTool.NAME,
                        arguments = JSONObject().put("node_id", bestGoalCandidate.id),
                    ),
                )
            } else {
                parsed
            }
        } catch (error: Throwable) {
            LocalPrivacyPlan.Handoff(
                "로컬 개인정보 처리 중 오류가 발생했습니다. 직접 진행해 주세요. " +
                    "(${error.message ?: error::class.java.simpleName})",
            )
        } finally {
            activeEngine = null
        }
    }

    fun cancel() {
        activeEngine?.cancelGeneration()
    }

    private fun captureOnlyWhenTreeIsInsufficient(
        snapshot: UiSnapshot,
        visionAvailable: Boolean,
        maxDimension: Int,
    ): DeviceToolResult.Screenshot? {
        if (!visionAvailable) return null
        val meaningfulCount = snapshot.nodes.count { node -> node.isMeaningfulForAgent() }
        // WebView alone is not evidence that the accessibility tree is poor.
        // When it already exposes enough semantic nodes, sending a screenshot
        // adds a costly mmproj/image-prefill pass without improving grounding.
        if (meaningfulCount >= MIN_NODES_WITHOUT_IMAGE) return null
        return deviceTools.execute(
            DeviceToolCall(
                CaptureScreenDeviceTool.NAME,
                JSONObject().put("max_dimension", maxDimension),
            ),
        ) as? DeviceToolResult.Screenshot
    }

    private fun parsePlan(output: String): LocalPrivacyPlan {
        val jsonText = firstJsonObject(output)
            ?: return LocalPrivacyPlan.Handoff("로컬 모델의 안전한 행동을 확인하지 못했습니다.")
        val json = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return LocalPrivacyPlan.Handoff("로컬 모델 응답 형식을 확인하지 못했습니다.")
        val handoffMessage = json.optString("handoff")
            .trim()
            .takeIf(String::isNotBlank)
        if (handoffMessage != null) {
            return LocalPrivacyPlan.Handoff(handoffMessage)
        }

        val call = toolAdapter.parseToolCall(jsonText)
            ?: return LocalPrivacyPlan.Handoff("개인정보 화면에서 실행할 안전한 행동이 없습니다.")
        if (call.name !in ALLOWED_LOCAL_TOOLS) {
            return LocalPrivacyPlan.Handoff(
                "개인정보 화면에서 ${call.name} 동작은 자동 실행하지 않습니다.",
            )
        }
        toolAdapter.validationError(call)?.let { error ->
            return LocalPrivacyPlan.Handoff("로컬 모델 행동 검증 실패: $error")
        }
        return LocalPrivacyPlan.Tool(call)
    }

    private fun buildPrompt(
        goal: String,
        packageName: String,
        nodes: List<JSONObject>,
        editableNodes: List<JSONObject>,
        allowedResources: List<PublicCredentialDescriptor>,
        bestGoalCandidate: UiNode?,
    ): String = buildString {
        appendLine("USER_GOAL: $goal")
        appendLine("FOREGROUND_PACKAGE: $packageName")
        appendLine("SAFE_REVERSIBLE_NAVIGATION_CANDIDATES: ${JSONArray(nodes)}")
        appendLine("EDITABLE_FIELDS: ${JSONArray(editableNodes)}")
        appendLine(
            "AVAILABLE_LOCAL_RESOURCES: " +
                JSONArray(allowedResources.map(PublicCredentialDescriptor::toPlannerJson)),
        )
        if (bestGoalCandidate != null) {
            appendLine(
                "BEST_EXPLICIT_GOAL_MATCH: " +
                    JSONObject()
                        .put("id", bestGoalCandidate.id)
                        .put(
                            "label",
                            listOfNotNull(
                                bestGoalCandidate.text,
                                bestGoalCandidate.contentDescription,
                            ).joinToString(" "),
                        ),
            )
        }
        appendLine()
        appendLine("Choose one next action using only a candidate above.")
        appendLine(
            "Match the action to the user's direction: starting or continuing a booking is " +
                "not the same as opening reservation/order history or booking confirmation. " +
                "When offered, prefer a guest/nonmember login or continue-as-guest tab.",
        )
        appendLine(
            "Never reveal or invent a credential. To fill an exact field, use only " +
                "fill_secret with a resource ID from AVAILABLE_LOCAL_RESOURCES. " +
                "The Android broker enforces package, field role, and one-time user approval.",
        )
        appendLine("Never submit an order, confirm a purchase, start payment, or expose a value.")
        appendLine(
            "A guest/nonmember login tab only opens a form; it does not authenticate. " +
                "If the goal asks for guest/nonmember mode and that tab is a candidate, " +
                "tap it before handing off.",
        )
        if (bestGoalCandidate != null) {
            appendLine(
                "The best explicit goal match above is already policy-approved and reversible. " +
                    "Do not hand off. Return a tap_node call for that exact id.",
            )
        } else {
            appendLine("If user confirmation, authentication, or payment is needed, return:")
            appendLine("""{"handoff":"사용자에게 보여줄 짧은 한국어 안내"}""")
        }
        appendLine("Return exactly one of these tool JSON shapes:")
        appendLine("""{"tool":"tap_node","arguments":{"node_id":"node_1"}}""")
        appendLine(
            """{"tool":"fill_secret","arguments":{"node_id":"node_2","secret_ref":"R_..."}}""",
        )
        appendLine(
            """{"tool":"swipe","arguments":{"start_x":500,"start_y":1500,""" +
                """"end_x":500,"end_y":500,"duration_ms":400}}""",
        )
        appendLine("""{"tool":"go_back","arguments":{}}""")
        appendLine("""{"tool":"wait","arguments":{"duration_ms":500}}""")
    }

    private fun privateCandidateScore(
        node: UiNode,
        goal: String,
    ): Int {
        val label = listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ")
            .lowercase()
            .trim()
        if (label.isBlank()) return 0
        val normalizedGoal = goal.lowercase()
        var score = 0
        // Prefer the most specific explicit label. Without the length bonus,
        // "회원 로그인" ties with "비회원 로그인" because both are substrings
        // of the latter phrase, and traversal order can select the wrong tab.
        if (label in normalizedGoal) {
            score += EXACT_LABEL_MATCH_SCORE + label.length * LABEL_SPECIFICITY_WEIGHT
        }
        val labelTerms = label.split(NON_WORD_SEPARATOR)
            .filter { term -> term.length >= MIN_MATCH_TERM_LENGTH }
        score += labelTerms.count(normalizedGoal::contains) * 1_000
        if (node.viewId?.isNotBlank() == true) score += 10
        return score
    }

    private fun goalExplicitlyRejects(
        node: UiNode,
        goal: String,
    ): Boolean {
        val labels = listOfNotNull(node.text, node.contentDescription)
            .map(String::trim)
            .filter(String::isNotBlank)
        val normalizedGoal = goal.lowercase()
        return labels.any { rawLabel ->
            val label = rawLabel.lowercase()
            var index = normalizedGoal.indexOf(label)
            while (index >= 0) {
                val from = (index - NEGATION_CONTEXT_CHARS).coerceAtLeast(0)
                val to = (index + label.length + NEGATION_CONTEXT_CHARS)
                    .coerceAtMost(normalizedGoal.length)
                val context = normalizedGoal.substring(from, to)
                if (NEGATION_MARKERS.any(context::contains)) return true
                index = normalizedGoal.indexOf(label, startIndex = index + label.length)
            }
            false
        }
    }

    private fun LocalPrivacyPlan.tapsNode(nodeId: String): Boolean =
        this is LocalPrivacyPlan.Tool &&
            call.name == TapNodeDeviceTool.NAME &&
            call.arguments.optString("node_id") == nodeId

    private fun firstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (character == '\\' && quoted) {
                escaped = true
                continue
            }
            if (character == '"') quoted = !quoted
            if (quoted) continue
            if (character == '{') depth += 1
            if (character == '}') {
                depth -= 1
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
        return null
    }

    private companion object {
        const val MAX_LOCAL_NODES = 48
        const val MAX_LOCAL_EDITABLE_NODES = 12
        const val MIN_NODES_WITHOUT_IMAGE = 6
        const val RESPONSE_TOKENS = 160
        const val MAX_LOGGED_OUTPUT = 500
        const val MAX_LOGGED_LABEL = 120
        const val MIN_MATCH_TERM_LENGTH = 2
        const val STRONG_GOAL_MATCH_SCORE = 1_000
        const val EXACT_LABEL_MATCH_SCORE = 10_000
        const val LABEL_SPECIFICITY_WEIGHT = 100
        const val NEGATION_CONTEXT_CHARS = 20
        val NON_WORD_SEPARATOR = Regex("""[^\p{L}\p{N}]+""")
        val NEGATION_MARKERS = listOf(
            "누르지",
            "선택하지",
            "열지",
            "하지 마",
            "금지",
            "do not",
            "don't",
            "must not",
        )
        val ALLOWED_LOCAL_TOOLS = setOf(
            TapNodeDeviceTool.NAME,
            SwipeDeviceTool.NAME,
            GoBackDeviceTool.NAME,
            WaitDeviceTool.NAME,
            FillSecretDeviceTool.NAME,
        )
        const val SYSTEM_PROMPT =
            "You are the private on-device step planner for an Android shopping assistant. " +
                "All content stays on this device. Return only one JSON object. " +
                "You may navigate reversible UI and request a brokered fill_secret action, " +
                "but never place an order, pay, submit authentication, or reveal private values."
        const val TAG = "LocalPrivacyPlanner"
    }
}
