package com.example.mobileguiagent.model

import com.example.mobileguiagent.credentials.CredentialFieldRole
import com.example.mobileguiagent.credentials.PublicCredentialDescriptor
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.FillSecretDeviceTool
import com.example.mobileguiagent.device.GoBackDeviceTool
import com.example.mobileguiagent.device.ScrollDeviceTool
import com.example.mobileguiagent.device.TapNodeDeviceTool
import org.json.JSONObject
import java.util.Calendar

/**
 * Capabilities granted by the task contract, independently of model wording.
 *
 * A task skill may authorize a reversible capability such as using a
 * package-bound credential, but the runtime still verifies the current screen
 * and exact target before it performs anything.
 */
enum class AgentCapability {
    USE_STORED_CREDENTIALS,
    ACKNOWLEDGE_INFORMATION,
    ENFORCE_EXACT_SELECTIONS,
}

enum class AgentSelectionPolicy {
    EARLIEST_AVAILABLE,
    FUTURE_ONLY,
    TODAY_ONLY,
    GEOMETRIC_CENTER,
    NEXT_CANDIDATE_FALLBACK,
}

data class TaskContract(
    val originalGoal: String,
    val capabilities: Set<AgentCapability>,
    val requiredSelections: Set<String>,
    val goalSpec: AgentGoalSpec = AgentGoalSpec.minimal(originalGoal),
    val preferredViewIds: Set<String> = emptySet(),
    val forbiddenViewIds: Set<String> = emptySet(),
    val requiredEntities: Map<String, Set<String>> = emptyMap(),
    val entityNavigationHints: Map<String, Map<String, List<String>>> = emptyMap(),
    val requiredDate: TaskDate? = null,
    val timeWindow: TaskTimeWindow? = null,
    val stateSlotViewIds: Map<String, Set<String>> = emptyMap(),
    val dateViewIdPatterns: Set<String> = emptySet(),
    val showtimeViewIdPatterns: Set<String> = emptySet(),
    val seatCandidatePatterns: Set<String> = emptySet(),
    val selectionPolicies: Set<AgentSelectionPolicy> = emptySet(),
    val completionLabels: Set<String> = emptySet(),
) {
    fun allows(capability: AgentCapability): Boolean = capability in capabilities

    fun constraintValue(subject: String): String? =
        goalSpec.constraint(subject)
            ?.takeIf { it.hard && it.operator.lowercase() in EQUALITY_OPERATORS }
            ?.value

    fun promptSection(): String = buildString {
        appendLine("RUNTIME_TASK_CONTRACT:")
        appendLine("- immutable_goal: $originalGoal")
        appendLine(goalSpec.promptSection())
        appendLine("- capabilities: ${capabilities.joinToString()}")
        appendLine("- required_selections: ${requiredSelections.joinToString()}")
        appendLine(
            "- required_entities: " +
                requiredEntities.entries.joinToString { (kind, values) ->
                    "$kind=${values.joinToString("|")}"
                },
        )
        appendLine(
            "- entity_navigation_hints: " +
                entityNavigationHints.entries.joinToString { (kind, hints) ->
                    "$kind=" + hints.entries.joinToString("|") { (entity, route) ->
                        "$entity:${route.joinToString(">")}"
                    }
                },
        )
        appendLine("- required_date: ${requiredDate?.compact ?: "none"}")
        appendLine("- time_window: ${timeWindow?.promptValue() ?: "none"}")
        appendLine("- selection_policies: ${selectionPolicies.joinToString()}")
        appendLine("- completion_evidence: ${completionLabels.joinToString()}")
        appendLine("- seat_candidate_patterns: ${seatCandidatePatterns.joinToString()}")
        appendLine("- preferred_view_ids: ${preferredViewIds.joinToString()}")
        appendLine("- forbidden_view_ids: ${forbiddenViewIds.joinToString()}")
        append(
            "The runtime rejects actions whose observable preconditions conflict " +
                "with this contract. Resolve a reported mismatch before progressing.",
        )
    }

    private companion object {
        val EQUALITY_OPERATORS = setOf("equals", "eq", "is")
    }
}

data class TaskDate(
    val year: Int,
    val month: Int,
    val day: Int,
) {
    val compact: String = "%04d%02d%02d".format(year, month, day)
    val targetKey: String = "date:$compact"
}

data class TaskTimeWindow(
    val startMinutes: Int,
    val endMinutes: Int,
) {
    init {
        require(startMinutes in 0 until MINUTES_PER_DAY)
        require(endMinutes in 0 until MINUTES_PER_DAY)
    }

    fun contains(minutes: Int): Boolean =
        if (startMinutes <= endMinutes) {
            minutes in startMinutes..endMinutes
        } else {
            minutes >= startMinutes || minutes <= endMinutes
        }

    fun promptValue(): String =
        "${startMinutes.toClockText()}-${endMinutes.toClockText()}"

    private fun Int.toClockText(): String =
        "%02d:%02d".format(this / 60, this % 60)

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/**
 * Enforces both the product-wide irreversible boundary and model-authored
 * forbidden actions against the concrete control about to be activated.
 */
object AgentActionBoundaryPolicy {
    fun blockedTapReason(node: UiNode?): String? =
        blockedLabelReason(node.labelForBoundaryCheck())

    fun blockedTapReason(contract: TaskContract, node: UiNode?): String? =
        blockedLabelReason(contract, node.labelForBoundaryCheck())

    fun blockedLabelReason(label: String): String? {
        val normalized = label.normalizedBoundaryText()
        return if (IRREVERSIBLE_TERMS.any(normalized::contains)) {
            "주문 확정 또는 결제 행동은 사용자가 직접 확인해야 합니다."
        } else {
            null
        }
    }

    fun blockedLabelReason(contract: TaskContract, label: String): String? {
        blockedLabelReason(label)?.let { return it }
        val normalized = label.normalizedBoundaryText()
        val compact = normalized.replace(" ", "")
        val forbidden = contract.goalSpec.forbiddenActions.firstOrNull { action ->
            actionTerms(action).any { term ->
                val normalizedTerm = term.normalizedBoundaryText()
                normalizedTerm.length >= MIN_FORBIDDEN_TERM_LENGTH &&
                    (
                        normalized.contains(normalizedTerm) ||
                            compact.contains(normalizedTerm.replace(" ", ""))
                        )
            }
        }
        return forbidden?.let {
            "목표 명세에서 금지된 '$it' 동작은 자동으로 실행하지 않습니다."
        }
    }

    private fun UiNode?.labelForBoundaryCheck(): String =
        listOfNotNull(this?.text, this?.contentDescription, this?.viewId)
            .joinToString(" ")

    private fun String.normalizedBoundaryText(): String =
        lowercase()
            .replace(Regex("""[_./-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun actionTerms(action: String): List<String> =
        FORBIDDEN_ACTION_ALIASES[action.lowercase()]
            .orEmpty() + action.replace('_', ' ').replace('.', ' ')

    private const val MIN_FORBIDDEN_TERM_LENGTH = 4
    private val IRREVERSIBLE_TERMS = listOf(
        "결제하기",
        "결제 및 주문",
        "주문 및 결제",
        "주문 완료",
        "구매 확정",
        "구매확정",
        "place order",
        "pay now",
    )
    private val FORBIDDEN_ACTION_ALIASES = mapOf(
        "execute_payment" to listOf("결제하기", "pay now", "place order"),
        "send_message" to listOf("메시지 보내기", "send message"),
        "submit_form" to listOf("양식 제출", "submit form"),
        "publish" to listOf("게시하기", "publish"),
    )
}

/**
 * Compiles a model-authored semantic goal into runtime accelerators.
 *
 * The model owns meaning. Skills may contribute optional UI hints and
 * reversible capabilities, while this compiler only maps already-structured
 * subjects to generic runtime concepts.
 */
object GoalSpecTaskContractCompiler {
    fun compile(
        goal: String,
        spec: AgentGoalSpec,
        skills: AgentSkillBundle,
    ): TaskContract {
        val validated = AgentGoalSpecValidator.validate(spec)
        val skillCapabilities = skills.taskSkills
            .flatMap { skill -> skill.runtimePolicy.capabilities }
            .toSet()
        val requiredEntities = validated.entities
            .filter(AgentGoalEntity::required)
            .groupBy { entity -> entity.name.lowercase() }
            .mapValues { (_, entities) -> entities.map(AgentGoalEntity::value).toSet() }
        val capabilities = buildSet {
            addAll(skillCapabilities)
            if (validated.constraints.any(AgentGoalConstraint::hard)) {
                add(AgentCapability.ENFORCE_EXACT_SELECTIONS)
            }
        }
        return TaskContract(
            originalGoal = goal,
            capabilities = capabilities,
            goalSpec = validated,
            requiredSelections = requiredEntities["theater"].orEmpty(),
            preferredViewIds = skills.taskSkills
                .flatMap { skill -> skill.runtimePolicy.preferredViewIds }
                .toSet(),
            forbiddenViewIds = skills.taskSkills
                .flatMap { skill -> skill.runtimePolicy.forbiddenViewIds }
                .toSet(),
            requiredEntities = requiredEntities,
            entityNavigationHints = skills.taskSkills
                .flatMap { skill -> skill.runtimePolicy.entityNavigationHints.entries }
                .groupBy(Map.Entry<String, Map<String, List<String>>>::key)
                .mapValues { (_, entries) ->
                    buildMap {
                        entries.forEach { entry ->
                            entry.value.forEach { (entity, route) ->
                                put(entity, route)
                            }
                        }
                    }
                },
            requiredDate = validated.constraint("date")
                ?.takeIf { it.hard && it.operator.isEquality() }
                ?.value
                ?.let(TaskDateParser::extract),
            timeWindow = compileTimeWindow(validated),
            stateSlotViewIds = skills.taskSkills
                .flatMap { skill -> skill.runtimePolicy.stateSlotViewIds.entries }
                .groupBy(Map.Entry<String, Set<String>>::key)
                .mapValues { (_, entries) ->
                    entries.flatMap(Map.Entry<String, Set<String>>::value).toSet()
                },
            dateViewIdPatterns = skills.taskSkills
                .flatMap { skill -> skill.runtimePolicy.dateViewIdPatterns }
                .toSet(),
            showtimeViewIdPatterns = skills.taskSkills
                .flatMap { skill -> skill.runtimePolicy.showtimeViewIdPatterns }
                .toSet(),
            seatCandidatePatterns = skills.taskSkills
                .flatMap { skill -> skill.runtimePolicy.seatCandidatePatterns }
                .toSet(),
            selectionPolicies = selectionPolicies(validated),
            completionLabels = skills.taskSkills
                .flatMap { skill -> skill.runtimePolicy.completionLabels }
                .toSet(),
        )
    }

    private fun selectionPolicies(spec: AgentGoalSpec): Set<AgentSelectionPolicy> {
        return buildSet {
            val preferences = spec.preferences.map { preference ->
                Triple(
                    preference.subject.lowercase(),
                    preference.operator.lowercase(),
                    preference.value.lowercase(),
                )
            }
            if (preferences.any { (subject, operator, value) ->
                    operator in setOf("earliest", "minimize") &&
                        (
                            subject in setOf("time", "showtime", "start_time") ||
                                value in setOf("time", "start_time", "available_time")
                            )
                }
            ) {
                add(AgentSelectionPolicy.EARLIEST_AVAILABLE)
            }
            if (spec.constraints.any { constraint ->
                    constraint.subject.equals("time", true) &&
                        constraint.operator.lowercase() in setOf("after", "not_before") &&
                        constraint.value.lowercase() in setOf("now", "current_time")
                }
            ) {
                add(AgentSelectionPolicy.FUTURE_ONLY)
            }
            if (spec.constraint("date")?.value.equals("today", true)) {
                add(AgentSelectionPolicy.TODAY_ONLY)
            }
            if (preferences.any { (subject, _, value) ->
                    subject in setOf("seat.position", "position") &&
                        value in setOf("geometric_center", "center", "centre")
                }
            ) {
                add(AgentSelectionPolicy.GEOMETRIC_CENTER)
            }
            if (spec.preferences.any { preference ->
                    !preference.fallback.isNullOrBlank()
                }
            ) {
                add(AgentSelectionPolicy.NEXT_CANDIDATE_FALLBACK)
            }
        }
    }

    private fun compileTimeWindow(spec: AgentGoalSpec): TaskTimeWindow? {
        val start = spec.constraint("time.start")?.value
            ?.let(TaskTemporalParser::extractClock)
        val end = spec.constraint("time.end")?.value
            ?.let(TaskTemporalParser::extractClock)
        return if (start != null && end != null) TaskTimeWindow(start, end) else null
    }

    private fun String.isEquality(): Boolean =
        lowercase() in setOf("equals", "eq", "is")
}

object TaskDateParser {
    fun extract(
        text: String,
        calendar: Calendar = Calendar.getInstance(),
    ): TaskDate? {
        if (TODAY.containsMatchIn(text)) {
            return TaskDate(
                year = calendar.get(Calendar.YEAR),
                month = calendar.get(Calendar.MONTH) + 1,
                day = calendar.get(Calendar.DAY_OF_MONTH),
            )
        }
        val match = KOREAN_DATE.find(text) ?: NUMERIC_DATE.find(text) ?: return null
        val values = match.groupValues
        val year = values[1].toIntOrNull() ?: calendar.get(Calendar.YEAR)
        val month = values[2].toIntOrNull() ?: return null
        val day = values[3].toIntOrNull() ?: return null
        val validator = Calendar.getInstance().apply {
            isLenient = false
            clear()
            set(year, month - 1, day)
        }
        if (runCatching { validator.timeInMillis }.isFailure) return null
        return TaskDate(year, month, day)
    }

    private val TODAY = Regex("""(?:오늘|\btoday\b)""", RegexOption.IGNORE_CASE)
    private val KOREAN_DATE =
        Regex("""(?:(\d{4})년\s*)?(\d{1,2})월\s*(\d{1,2})일""")
    private val NUMERIC_DATE =
        Regex("""(?:(\d{4})[./-])?(\d{1,2})[./-](\d{1,2})(?!\d)""")
}

internal object TaskTemporalParser {
    fun extractWindow(text: String): TaskTimeWindow? {
        KOREAN_OR_NUMERIC_RANGE.find(text)?.let { match ->
            val startMarker = match.groupValues[1].ifBlank { null }
            val startHour = match.groupValues[2].toIntOrNull() ?: return@let
            val startMinute = match.groupValues[3].toIntOrNull() ?: 0
            val endMarker = match.groupValues[4].ifBlank { null }
            val endHour = match.groupValues[5].toIntOrNull() ?: return@let
            val endMinute = match.groupValues[6].toIntOrNull() ?: 0
            val inheritedStartMarker = startMarker ?: endMarker
            val inheritedEndMarker = endMarker ?: startMarker
            val start = clockMinutes(startHour, startMinute, inheritedStartMarker)
                ?: return@let
            val end = clockMinutes(endHour, endMinute, inheritedEndMarker)
                ?: return@let
            return TaskTimeWindow(start, end)
        }
        ENGLISH_RANGE.find(text)?.let { match ->
            val startHour = match.groupValues[1].toIntOrNull() ?: return@let
            val startMinute = match.groupValues[2].toIntOrNull() ?: 0
            val endHour = match.groupValues[3].toIntOrNull() ?: return@let
            val endMinute = match.groupValues[4].toIntOrNull() ?: 0
            val marker = match.groupValues[5].lowercase().ifBlank { null }
            val start = clockMinutes(startHour, startMinute, marker) ?: return@let
            val end = clockMinutes(endHour, endMinute, marker) ?: return@let
            return TaskTimeWindow(start, end)
        }
        return null
    }

    fun extractClock(text: String): Int? {
        CLOCK_WITH_COLON.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: return@let
            return clockMinutes(hour, minute, null)
        }
        return null
    }

    private fun clockMinutes(
        hour: Int,
        minute: Int,
        marker: String?,
    ): Int? {
        if (minute !in 0..59) return null
        val normalizedMarker = marker?.lowercase()
        val normalizedHour = when (normalizedMarker) {
            "오전", "am" -> {
                if (hour !in 1..12) return null
                if (hour == 12) 0 else hour
            }
            "오후", "pm" -> {
                if (hour !in 1..12) return null
                if (hour == 12) 12 else hour + 12
            }
            else -> {
                if (hour !in 0..23) return null
                hour
            }
        }
        return normalizedHour * 60 + minute
    }

    private val KOREAN_OR_NUMERIC_RANGE = Regex(
        """(?:(오전|오후)\s*)?(\d{1,2})(?:\s*시|:(\d{2}))?\s*(?:~|〜|–|—|-|부터)\s*(?:(오전|오후)\s*)?(\d{1,2})(?:\s*시|:(\d{2}))?""",
        RegexOption.IGNORE_CASE,
    )
    private val ENGLISH_RANGE = Regex(
        """\b(\d{1,2})(?::(\d{2}))?\s*(?:-|to)\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CLOCK_WITH_COLON = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)""")
}

data class RuntimeInterception(
    val code: String,
    val message: String,
    val automaticAction: RuntimeAction? = null,
)

sealed interface RuntimeAction {
    data class TapNode(
        val nodeId: String,
        val coordinateFallback: Boolean = false,
    ) : RuntimeAction

    data class FillCredential(
        val nodeId: String,
        val resourceId: String,
    ) : RuntimeAction

    data class Scroll(
        val nodeId: String,
        val direction: String,
    ) : RuntimeAction

    data object GoBack : RuntimeAction
}

fun RuntimeAction.toDeviceToolCall(): DeviceToolCall = when (this) {
    is RuntimeAction.TapNode -> DeviceToolCall(
        name = TapNodeDeviceTool.NAME,
        arguments = JSONObject()
            .put("node_id", nodeId)
            .apply {
                if (coordinateFallback) put("coordinate_fallback", true)
            },
    )
    is RuntimeAction.FillCredential -> DeviceToolCall(
        name = FillSecretDeviceTool.NAME,
        arguments = JSONObject()
            .put("node_id", nodeId)
            .put("secret_ref", resourceId),
    )
    is RuntimeAction.Scroll -> DeviceToolCall(
        name = ScrollDeviceTool.NAME,
        arguments = JSONObject()
            .put("node_id", nodeId)
            .put("direction", direction),
    )
    RuntimeAction.GoBack -> DeviceToolCall(
        name = GoBackDeviceTool.NAME,
        arguments = JSONObject(),
    )
}

internal object UiTreeRelations {
    fun clickableAncestor(
        node: UiNode,
        snapshot: UiSnapshot,
        maxDistance: Int = DEFAULT_MAX_ANCESTOR_DISTANCE,
    ): UiNode? {
        val byId = snapshot.nodes.associateBy(UiNode::id)
        var current = node.parentId?.let(byId::get)
        repeat(maxDistance) {
            if (current == null) return null
            if (current?.clickable == true && current?.enabled == true) return current
            current = current?.parentId?.let(byId::get)
        }
        return null
    }

    fun shareNearbyAncestor(
        first: UiNode,
        second: UiNode,
        snapshot: UiSnapshot,
        maxDistance: Int = DEFAULT_MAX_ANCESTOR_DISTANCE,
        maxCombinedDistance: Int = DEFAULT_MAX_COMBINED_DISTANCE,
    ): Boolean {
        val byId = snapshot.nodes.associateBy(UiNode::id)
        val firstAncestors = ancestorDistances(first, byId, maxDistance)
        val secondAncestors = ancestorDistances(second, byId, maxDistance)
        return firstAncestors.any { (id, firstDistance) ->
            val secondDistance = secondAncestors[id] ?: return@any false
            val ancestor = byId[id] ?: return@any false
            ancestor.depth > 0 &&
                firstDistance + secondDistance <= maxCombinedDistance
        }
    }

    private fun ancestorDistances(
        node: UiNode,
        byId: Map<String, UiNode>,
        maxDistance: Int,
    ): Map<String, Int> {
        val output = linkedMapOf<String, Int>()
        var current: UiNode? = node
        var distance = 0
        while (current != null && distance <= maxDistance) {
            output[current.id] = distance
            current = current.parentId?.let(byId::get)
            distance += 1
        }
        return output
    }

    private const val DEFAULT_MAX_ANCESTOR_DISTANCE = 5
    private const val DEFAULT_MAX_COMBINED_DISTANCE = 6
}

/**
 * Resolves a structurally grounded, reversible action on a blocking surface.
 *
 * A notice (or a short promotional "do not show again" affordance) and its
 * action must share a nearby ancestor. This prevents a background "확인" or
 * "닫기" from being mistaken for the modal action.
 */
object BlockingSurfaceInterceptor {
    fun intercept(
        contract: TaskContract,
        snapshot: UiSnapshot,
    ): RuntimeInterception? {
        if (!contract.allows(AgentCapability.ACKNOWLEDGE_INFORMATION)) return null
        val nodes = snapshot.nodes.filter { it.visibleToUser && it.enabled }
        val longMessages = nodes.filter { node ->
            node.label().length >= MIN_NOTICE_LENGTH && !node.clickable
        }
        val informationalNoticeAnchors = nodes.filter { node ->
            !node.clickable &&
                node.normalizedLabels().any(INFORMATIONAL_NOTICE_ANCHORS::contains)
        }
        val promotionalDismissAnchors = nodes.filter { node ->
            node.normalizedLabels().any(PROMOTIONAL_DISMISS_ANCHORS::contains)
        }
        val action = resolveAction(
            nodes = nodes,
            anchors = longMessages + informationalNoticeAnchors,
            safeActions = NOTICE_ACTIONS,
            snapshot = snapshot,
        ) ?: resolveAction(
            nodes = nodes,
            anchors = promotionalDismissAnchors,
            safeActions = PROMOTIONAL_CLOSE_ACTIONS,
            snapshot = snapshot,
        ) ?: return null

        return RuntimeInterception(
            code = "BLOCKING_SURFACE_SAFE_ACTION",
            message = "A blocking informational surface exposes a safe action.",
            automaticAction = RuntimeAction.TapNode(action.id),
        )
    }

    private fun resolveAction(
        nodes: List<UiNode>,
        anchors: List<UiNode>,
        safeActions: Set<String>,
        snapshot: UiSnapshot,
    ): UiNode? {
        if (anchors.isEmpty()) return null
        return nodes.firstOrNull { node ->
            node.clickable &&
                node.normalizedLabels().any(safeActions::contains) &&
                anchors.any { anchor ->
                    UiTreeRelations.shareNearbyAncestor(anchor, node, snapshot)
                }
        } ?: nodes
            .asSequence()
            .filter { node ->
                node.normalizedLabels().any(safeActions::contains)
            }
            .mapNotNull { labelNode ->
                UiTreeRelations.clickableAncestor(labelNode, snapshot)?.takeIf { clickable ->
                    anchors.any { anchor ->
                        UiTreeRelations.shareNearbyAncestor(anchor, clickable, snapshot)
                    }
                }
            }
            .firstOrNull()
    }

    private const val MIN_NOTICE_LENGTH = 60
    private val NOTICE_ACTIONS = setOf(
        "확인",
        "ok",
        "gotit",
    )
    private val INFORMATIONAL_NOTICE_ANCHORS = setOf(
        "예매안내",
        "관람안내",
        "이용안내",
        "bookinginformation",
        "bookingnotice",
        "information",
        "notice",
    )
    private val PROMOTIONAL_CLOSE_ACTIONS = setOf("닫기", "close")
    private val PROMOTIONAL_DISMISS_ANCHORS = setOf(
        "오늘그만보기",
        "다시보지않기",
        "일주일간보지않기",
        "dontshowagain",
        "donotshowagain",
        "nottoday",
    )
}

/**
 * Screen-driven authentication. It does not depend on the word "login"
 * appearing in the user's goal.
 */
object AuthenticationInterceptor {
    fun intercept(
        contract: TaskContract,
        snapshot: UiSnapshot,
        resources: List<PublicCredentialDescriptor>,
    ): RuntimeInterception? {
        if (!contract.allows(AgentCapability.USE_STORED_CREDENTIALS)) return null
        val editors = snapshot.nodes.filter { node ->
            node.visibleToUser && node.enabled && node.editable
        }
        val passwordEditor = editors.firstOrNull(UiNode::password) ?: return null
        val identifierEditor = editors.firstOrNull { !it.password } ?: return null
        val username = resources.firstOrNull { it.role == CredentialFieldRole.USERNAME }
        val password = resources.firstOrNull {
            it.role == CredentialFieldRole.PASSWORD ||
                it.role == CredentialFieldRole.GENERIC_SECRET
        }

        if (identifierEditor.text.isNullOrBlank() && username != null) {
            return fill(identifierEditor, username)
        }
        if (passwordEditor.text.isNullOrBlank() && password != null) {
            return fill(passwordEditor, password)
        }

        val submit = snapshot.nodes.firstOrNull { node ->
                node.visibleToUser &&
                node.enabled &&
                node.clickable &&
                node.normalizedLabels().any(AUTH_SUBMIT_ACTIONS::contains)
        } ?: return null
        if (
            identifierEditor.text.isNullOrBlank() ||
            passwordEditor.text.isNullOrBlank()
        ) {
            return null
        }
        return RuntimeInterception(
            code = "AUTH_SUBMIT_READY",
            message = "Package-bound credentials are filled and the verified auth submit control is ready.",
            automaticAction = RuntimeAction.TapNode(submit.id),
        )
    }

    private fun fill(
        target: UiNode,
        resource: PublicCredentialDescriptor,
    ) = RuntimeInterception(
        code = "AUTH_FILL_${resource.role.name}",
        message = "Fill the next package-bound authentication field locally.",
        automaticAction = RuntimeAction.FillCredential(
            nodeId = target.id,
            resourceId = resource.id,
        ),
    )

    private val AUTH_SUBMIT_ACTIONS = setOf(
        "로그인",
        "signin",
        "login",
        "logon",
    )
}

/**
 * Prevents progress actions while a visible current-value slot conflicts with
 * an explicit immutable selection in the task contract.
 */
object GoalInvariantInterceptor {
    fun correction(
        contract: TaskContract,
        snapshot: UiSnapshot,
        proposedTarget: UiNode?,
    ): RuntimeInterception? {
        if (
            proposedTarget?.viewId != null &&
            proposedTarget.viewId in contract.forbiddenViewIds
        ) {
            return RuntimeInterception(
                code = "FORBIDDEN_AFFORDANCE",
                message =
                    "The proposed control ${proposedTarget.viewId} is forbidden by the " +
                        "active task skill. Prefer one of ${contract.preferredViewIds}.",
            )
        }
        if (!contract.allows(AgentCapability.ENFORCE_EXACT_SELECTIONS)) return null
        if (contract.requiredSelections.isEmpty()) return null
        val visibleSemanticValues = snapshot.nodes
            .asSequence()
            .filter { node -> node.visibleToUser && node.enabled }
            .map(UiNode::normalizedSemanticText)
            .filter(String::isNotBlank)
            .toSet()
        if (
            contract.requiredSelections.any { required ->
                visibleSemanticValues.any { visible ->
                    visible == required
                }
            }
        ) {
            return null
        }
        val slots = ChangeableStateResolver.resolve(snapshot)
        val mismatch = slots.firstOrNull { slot ->
            contract.requiredSelections.none { required ->
                slot.currentValue == required
            }
        } ?: return null
        if (
            proposedTarget?.id == mismatch.changeNodeId ||
            proposedTarget?.normalizedLabel()?.contains("변경") == true ||
            proposedTarget?.normalizedLabel()?.contains("change") == true
        ) {
            return null
        }
        return RuntimeInterception(
            code = "GOAL_SELECTION_MISMATCH",
            message =
                "The visible current selection \"${mismatch.currentValue}\" conflicts with " +
                    "required selection(s) ${contract.requiredSelections}. Use the associated " +
                    "change control before any progress action.",
        )
    }
}

/**
 * Executes an exact reversible entry control declared by the active task skill
 * before a planner can substitute a nearby unlabeled affordance.
 *
 * The policy remains app-independent: view ids come from the selected skill,
 * and the action is available only while the exact visible control exists.
 */
object PreferredAffordanceInterceptor {
    fun isPresentButNotActionable(
        contract: TaskContract,
        snapshot: UiSnapshot,
    ): Boolean {
        if (contract.preferredViewIds.isEmpty()) return false
        return snapshot.nodes.any { node ->
            node.visibleToUser &&
                node.enabled &&
                !node.clickable &&
                node.matchesPreferredViewId(contract.preferredViewIds)
        }
    }

    fun intercept(
        contract: TaskContract,
        snapshot: UiSnapshot,
    ): RuntimeInterception? {
        if (contract.preferredViewIds.isEmpty()) return null
        val target = snapshot.nodes.firstOrNull { node ->
            node.visibleToUser &&
                node.enabled &&
                node.clickable &&
                node.matchesPreferredViewId(contract.preferredViewIds)
        } ?: return null
        return RuntimeInterception(
            code = "PREFERRED_AFFORDANCE_AVAILABLE",
            message =
                "The active task skill declares ${target.viewId} as the exact reversible " +
                    "entry control. Use it before model-selected neighboring controls.",
            automaticAction = RuntimeAction.TapNode(target.id),
        )
    }

    private fun UiNode.matchesPreferredViewId(preferredViewIds: Set<String>): Boolean =
        viewId?.let { viewId ->
            viewId in preferredViewIds ||
                preferredViewIds.any { preferred ->
                    viewId.endsWith("/$preferred") ||
                        preferred.endsWith("/$viewId")
                }
        } == true
}

internal data class ShowtimeCandidateResolution(
    val nodes: List<UiNode>,
    val scopedToRequestedMovie: Boolean,
)

/**
 * Limits structured showtime candidates to the section structurally closest
 * to the requested movie label.
 *
 * Movie and showtime view ids do not necessarily share a public identifier.
 * Accessibility does preserve their tree structure, though: candidates in
 * the requested movie card have a deeper lowest common ancestor with its
 * title than candidates in adjacent cards. If an exact movie is required but
 * its title is not exposed, the resolver fails closed. Treating every
 * showtime as eligible in that state can silently book the first movie on the
 * page.
 */
internal object ShowtimeCandidateResolver {
    fun resolve(
        contract: TaskContract,
        snapshot: UiSnapshot,
    ): ShowtimeCandidateResolution {
        val patterns = contract.showtimeViewIdPatterns.mapNotNull { source ->
            runCatching { Regex(source) }.getOrNull()
        }
        val allCandidates = snapshot.nodes.filter { node ->
            node.viewId?.let { viewId ->
                patterns.any { pattern -> pattern.containsMatchIn(viewId) }
            } == true
        }
        if (allCandidates.isEmpty()) {
            return ShowtimeCandidateResolution(emptyList(), scopedToRequestedMovie = false)
        }

        val requiredMovies = contract.requiredEntities[MOVIE_KIND]
            .orEmpty()
            .map(::normalizeEntity)
            .filter(String::isNotBlank)
            .toSet()
        if (requiredMovies.isEmpty()) {
            return ShowtimeCandidateResolution(allCandidates, scopedToRequestedMovie = false)
        }
        val movieLabels = snapshot.nodes.filter { node ->
            val actual = node.normalizedSemanticText()
            actual.isNotBlank() && requiredMovies.any { required ->
                actual == required || actual.contains(required) || required.contains(actual)
            }
        }
        if (movieLabels.isEmpty()) {
            return ShowtimeCandidateResolution(emptyList(), scopedToRequestedMovie = true)
        }

        val byId = snapshot.nodes.associateBy(UiNode::id)
        val scored = allCandidates.map { candidate ->
            candidate to movieLabels.maxOfOrNull { label ->
                lowestCommonAncestorDepth(candidate, label, byId)
            }.let { depth -> depth ?: NO_COMMON_ANCESTOR }
        }
        val bestDepth = scored.maxOf { (_, depth) -> depth }
        if (bestDepth == NO_COMMON_ANCESTOR) {
            return ShowtimeCandidateResolution(allCandidates, scopedToRequestedMovie = false)
        }
        return ShowtimeCandidateResolution(
            nodes = scored.filter { (_, depth) -> depth == bestDepth }.map { (node, _) -> node },
            scopedToRequestedMovie = true,
        )
    }

    private fun lowestCommonAncestorDepth(
        first: UiNode,
        second: UiNode,
        byId: Map<String, UiNode>,
    ): Int {
        val firstAncestors = ancestorDepths(first, byId)
        return ancestorDepths(second, byId)
            .asSequence()
            .filter { (id, _) -> id in firstAncestors }
            .maxOfOrNull { (_, depth) -> depth }
            ?: NO_COMMON_ANCESTOR
    }

    private fun ancestorDepths(
        node: UiNode,
        byId: Map<String, UiNode>,
    ): Map<String, Int> = buildMap {
        var current: UiNode? = node
        while (true) {
            val value = current ?: break
            if (containsKey(value.id)) break
            put(value.id, value.depth)
            current = value.parentId?.let(byId::get)
        }
    }

    private fun normalizeEntity(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private const val MOVIE_KIND = "movie"
    private const val NO_COMMON_ANCESTOR = -1
}

/**
 * Enforces typed task constraints at the two places where model-only
 * instruction following is weakest:
 *
 * 1. before selecting a temporal target, and
 * 2. after an app exposes a structured summary of the selected state.
 *
 * View IDs and target patterns are declared by the skill. The runtime remains
 * app-independent and only evaluates exact entities and time windows.
 */
object TaskStateConstraintInterceptor {
    internal data class EligibleShowtime(
        val node: UiNode,
        val minutes: Int,
    )

    internal fun eligibleShowtimes(
        contract: TaskContract,
        snapshot: UiSnapshot,
        currentMinutes: Int = currentLocalClockMinutes(),
        excludedShowtimeMinutes: Set<Int> = emptySet(),
    ): List<EligibleShowtime> {
        val window = contract.timeWindow
        return ShowtimeCandidateResolver.resolve(contract, snapshot)
            .nodes
            .asSequence()
            .mapNotNull { node ->
                proposedShowtime(contract, node, snapshot)
                    ?.let { minutes -> EligibleShowtime(node, minutes) }
            }
            .filter { candidate -> window?.contains(candidate.minutes) != false }
            .filter { candidate ->
                AgentSelectionPolicy.FUTURE_ONLY !in contract.selectionPolicies ||
                    candidate.minutes > currentMinutes
            }
            .filterNot { candidate -> candidate.minutes in excludedShowtimeMinutes }
            .sortedWith(
                compareBy<EligibleShowtime>(
                    EligibleShowtime::minutes,
                    { candidate -> !candidate.node.visibleToUser },
                    { candidate -> !candidate.node.clickable },
                    { candidate -> candidate.node.bounds.top },
                ),
            )
            .toList()
    }

    fun correction(
        contract: TaskContract,
        snapshot: UiSnapshot,
        proposedTarget: UiNode?,
        currentMinutes: Int = currentLocalClockMinutes(),
        excludedShowtimeMinutes: Set<Int> = emptySet(),
        validatedSelectionKeys: Set<String> = emptySet(),
    ): RuntimeInterception? {
        if (!contract.allows(AgentCapability.ENFORCE_EXACT_SELECTIONS)) return null

        proposedTarget?.let { target ->
            proposedDate(contract, target, snapshot)?.let { date ->
                val required = contract.requiredDate
                if (required != null && date != required) {
                    return RuntimeInterception(
                        code = "DATE_OUTSIDE_REQUESTED_DAY",
                        message =
                            "The proposed date ${date.compact} conflicts with the immutable " +
                                "requested date ${required.compact}.",
                    )
                }
            }
            if (
                AgentSelectionPolicy.TODAY_ONLY in contract.selectionPolicies &&
                target.normalizedSemanticText().let { label ->
                    TOMORROW_TERMS.any(label::contains)
                }
            ) {
                return RuntimeInterception(
                    code = "DATE_OUTSIDE_REQUESTED_DAY",
                    message =
                        "The proposed date is tomorrow, but the immutable task requires today.",
                )
            }
            proposedSeat(contract, target, snapshot)?.let { seat ->
                val validatedSeatKeys = validatedSelectionKeys
                    .filter { key -> key.startsWith("seat:") }
                    .toSet()
                if (validatedSeatKeys.isNotEmpty()) {
                    return RuntimeInterception(
                        code = "SEAT_ALREADY_VALIDATED",
                        message =
                            "A center seat is already validated as " +
                                "${validatedSeatKeys.joinToString()}. Do not change it.",
                    )
                }
                val centerKeys = geometricCenterSeatKeys(contract, snapshot)
                if (
                    AgentSelectionPolicy.GEOMETRIC_CENTER in contract.selectionPolicies &&
                    seat.stableKey !in centerKeys
                ) {
                    return RuntimeInterception(
                        code = "SEAT_NOT_GEOMETRIC_CENTER",
                        message =
                            "The proposed seat ${seat.stableLabel} is not an exact " +
                                "geometric-center seat. Exact center candidates are " +
                                centerKeys.joinToString(),
                    )
                }
            }
            proposedShowtime(contract, target, snapshot)?.let { minutes ->
                contract.requiredDate?.let { requiredDate ->
                    if (requiredDate.targetKey !in validatedSelectionKeys) {
                        return RuntimeInterception(
                            code = "REQUIRED_DATE_NOT_CONFIRMED",
                            message =
                                "Do not select a showtime until the exact requested date " +
                                    "${requiredDate.compact} has been selected and verified.",
                        )
                    }
                }
                if (minutes in excludedShowtimeMinutes) {
                    return RuntimeInterception(
                        code = "SHOWTIME_EXHAUSTED",
                        message =
                            "The proposed showtime ${minutes.toClockText()} was already " +
                                "exhausted because its exact center seats could not be " +
                                "validated. Select the next eligible showtime.",
                    )
                }
                val candidateResolution = ShowtimeCandidateResolver.resolve(contract, snapshot)
                val groundedTarget = targetWithStableViewId(target, snapshot)
                if (
                    candidateResolution.scopedToRequestedMovie &&
                    candidateResolution.nodes.none { candidate ->
                        candidate.id == groundedTarget.id ||
                            candidate.viewId == groundedTarget.viewId
                    }
                ) {
                    return RuntimeInterception(
                        code = "SHOWTIME_WRONG_MOVIE",
                        message =
                            "The proposed showtime ${minutes.toClockText()} belongs to a " +
                                "different movie section. Select a showtime grouped under " +
                                "the immutable requested movie.",
                    )
                }
                if (
                    AgentSelectionPolicy.FUTURE_ONLY in contract.selectionPolicies &&
                    minutes <= currentMinutes
                ) {
                    return RuntimeInterception(
                        code = "SHOWTIME_NOT_IN_FUTURE",
                        message =
                            "The proposed showtime ${minutes.toClockText()} is not after " +
                                "the current time ${currentMinutes.toClockText()}. Select " +
                                "a future showtime from the requested movie section.",
                    )
                }
                val window = contract.timeWindow
                if (window != null && !window.contains(minutes)) {
                    return RuntimeInterception(
                        code = "SHOWTIME_OUTSIDE_WINDOW",
                        message =
                            "The proposed showtime ${minutes.toClockText()} is outside " +
                            "the required ${window.promptValue()} window.",
                    )
                }
                if (AgentSelectionPolicy.EARLIEST_AVAILABLE in contract.selectionPolicies) {
                    val earliest = eligibleShowtimes(
                        contract = contract,
                        snapshot = snapshot,
                        currentMinutes = currentMinutes,
                        excludedShowtimeMinutes = excludedShowtimeMinutes,
                    ).firstOrNull()?.minutes
                    if (earliest != null && minutes != earliest) {
                        return RuntimeInterception(
                            code = "SHOWTIME_NOT_EARLIEST",
                            message =
                                "The proposed showtime ${minutes.toClockText()} is not the " +
                                    "earliest eligible showtime ${earliest.toClockText()}. " +
                                    "Display order is not chronological; select the actual minimum.",
                        )
                    }
                }
            }
        }

        summaryMismatch(contract, snapshot)?.let { mismatch ->
            if (proposedTarget != null && proposedTarget.isCorrectiveAction()) {
                return null
            }
            return mismatch
        }
        return null
    }

    private fun proposedSeat(
        contract: TaskContract,
        target: UiNode,
        snapshot: UiSnapshot,
    ): ParsedSeat? {
        val patterns = contract.seatCandidatePatterns.mapNotNull { source ->
            runCatching { Regex(source, RegexOption.IGNORE_CASE) }.getOrNull()
        }.toSet()
        if (patterns.isEmpty()) return null
        return SeatSelectionPolicy.resolve(snapshot, patterns)
            .allSeats
            .firstOrNull { seat -> seat.node.id == target.id }
    }

    private fun geometricCenterSeatKeys(
        contract: TaskContract,
        snapshot: UiSnapshot,
    ): Set<String> {
        val patterns = contract.seatCandidatePatterns.mapNotNull { source ->
            runCatching { Regex(source, RegexOption.IGNORE_CASE) }.getOrNull()
        }.toSet()
        if (patterns.isEmpty()) return emptySet()
        return SeatSelectionPolicy.resolve(snapshot, patterns)
            .geometricCenterSeats
            .map(ParsedSeat::stableKey)
            .toSet()
    }

    fun summaryMismatch(
        contract: TaskContract,
        snapshot: UiSnapshot,
    ): RuntimeInterception? {
        contract.requiredEntities.forEach { (kind, requiredValues) ->
            val actualNodes = stateSlotNodes(contract, snapshot, kind)
            if (actualNodes.isEmpty()) return@forEach
            val actualValues = actualNodes
                .map(UiNode::normalizedSemanticText)
                .filter(String::isNotBlank)
            val matches = requiredValues.any { required ->
                actualValues.any { actual ->
                    if (kind == THEATER_KIND) {
                        theaterSummaryMatches(actual, required)
                    } else {
                        actual == required ||
                            actual.contains(required) ||
                            required.contains(actual)
                    }
                }
            }
            if (!matches) {
                return RuntimeInterception(
                    code = "SUMMARY_${kind.uppercase()}_MISMATCH",
                    message =
                        "The app summary reports ${actualNodes.joinToString { it.semanticText() }} " +
                            "for $kind, but the immutable task requires $requiredValues. " +
                            "Back out or change the selection before progressing.",
                )
            }
        }

        val window = contract.timeWindow
        if (window != null) {
            val timeNodes = stateSlotNodes(contract, snapshot, TIME_KIND)
            val actual = timeNodes.firstNotNullOfOrNull { node ->
                TaskTemporalParser.extractClock(node.semanticText())
            }
            if (actual != null && !window.contains(actual)) {
                return RuntimeInterception(
                    code = "SUMMARY_TIME_MISMATCH",
                    message =
                        "The app summary reports ${actual.toClockText()}, outside the " +
                            "immutable ${window.promptValue()} window. Back out or change " +
                            "the showtime before progressing.",
                )
            }
        }
        return null
    }

    private fun theaterSummaryMatches(actual: String, required: String): Boolean {
        if (actual == required) return true
        if (!actual.startsWith(required)) return false
        val suffix = actual.removePrefix(required)
        return suffix == "점" || THEATER_AUDITORIUM_SUFFIX.matches(suffix)
    }

    fun stableTargetKey(
        contract: TaskContract,
        target: UiNode?,
        snapshot: UiSnapshot,
    ): String? {
        target ?: return null
        proposedDate(contract, target, snapshot)?.let { date ->
            return date.targetKey
        }
        val grounded = targetWithStableViewId(target, snapshot)
        grounded.viewId?.takeIf(String::isNotBlank)?.let { return "view:$it" }
        proposedShowtime(contract, target, snapshot)?.let { minutes ->
            return "showtime:${minutes.toClockText()}:${target.normalizedLabel()}"
        }
        return target.normalizedLabel()
            .takeIf(String::isNotBlank)
            ?.let { "label:$it" }
    }

    internal fun showtimeMinutes(
        contract: TaskContract,
        target: UiNode?,
        snapshot: UiSnapshot,
    ): Int? = target?.let { proposedShowtime(contract, it, snapshot) }

    internal fun proposedDate(
        contract: TaskContract,
        target: UiNode,
        snapshot: UiSnapshot,
    ): TaskDate? {
        val grounded = targetWithStableViewId(target, snapshot)
        val viewId = grounded.viewId.orEmpty()
        contract.dateViewIdPatterns.forEach { source ->
            val pattern = runCatching { Regex(source) }.getOrNull() ?: return@forEach
            val compact =
                pattern.find(viewId)?.groupValues?.getOrNull(1) ?: return@forEach
            if (!COMPACT_DATE.matches(compact)) return@forEach
            val year = compact.take(4).toIntOrNull() ?: return@forEach
            val month = compact.substring(4, 6).toIntOrNull() ?: return@forEach
            val day = compact.takeLast(2).toIntOrNull() ?: return@forEach
            return TaskDateParser.extract("$year-$month-$day")
        }
        return null
    }

    private fun stateSlotNodes(
        contract: TaskContract,
        snapshot: UiSnapshot,
        kind: String,
    ): List<UiNode> {
        val ids = contract.stateSlotViewIds[kind].orEmpty()
        if (ids.isEmpty()) return emptyList()
        return snapshot.nodes.filter { node ->
            node.visibleToUser &&
                node.enabled &&
                node.viewId?.let { viewId ->
                    viewId in ids || ids.any { id -> viewId.endsWith("/$id") }
                } == true
        }
    }

    private fun proposedShowtime(
        contract: TaskContract,
        target: UiNode,
        snapshot: UiSnapshot,
    ): Int? {
        val grounded = targetWithStableViewId(target, snapshot)
        val viewId = grounded.viewId.orEmpty()
        contract.showtimeViewIdPatterns.forEach { source ->
            val pattern = runCatching { Regex(source) }.getOrNull() ?: return@forEach
            val raw = pattern.find(viewId)?.groupValues?.getOrNull(1) ?: return@forEach
            parseCompactClock(raw)?.let { return it }
        }
        return TaskTemporalParser.extractClock(target.semanticText())
            ?: TaskTemporalParser.extractClock(grounded.semanticText())
    }

    private fun targetWithStableViewId(
        target: UiNode,
        snapshot: UiSnapshot,
    ): UiNode {
        if (!target.viewId.isNullOrBlank()) return target
        val byId = snapshot.nodes.associateBy(UiNode::id)
        var current = target.parentId?.let(byId::get)
        repeat(MAX_TARGET_ANCESTOR_DISTANCE) {
            if (current == null) return target
            if (!current?.viewId.isNullOrBlank()) return current!!
            current = current?.parentId?.let(byId::get)
        }
        return target
    }

    private fun parseCompactClock(raw: String): Int? {
        if (!COMPACT_CLOCK.matches(raw)) return null
        val hour = raw.take(2).toIntOrNull() ?: return null
        val minute = raw.takeLast(2).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun UiNode.isCorrectiveAction(): Boolean {
        val label = normalizedLabel()
        return CORRECTIVE_TERMS.any(label::contains)
    }

    private fun Int.toClockText(): String =
        "%02d:%02d".format(this / 60, this % 60)

    private const val TIME_KIND = "time"
    private const val THEATER_KIND = "theater"
    private const val MAX_TARGET_ANCESTOR_DISTANCE = 4
    private val COMPACT_CLOCK = Regex("""\d{4}""")
    private val COMPACT_DATE = Regex("""\d{8}""")
    private val THEATER_AUDITORIUM_SUFFIX = Regex("""\d+관.*""")
    private val CORRECTIVE_TERMS = setOf(
        "닫기",
        "취소",
        "뒤로",
        "변경",
        "close",
        "cancel",
        "back",
        "change",
    )
    private val TOMORROW_TERMS = setOf("내일", "tomorrow")
}

data class CompletionEvaluation(
    val satisfied: Boolean,
    val evidence: List<String>,
    val missing: Set<String>,
)

/**
 * A model may propose completion, but only observable runtime evidence can
 * finish either a skill-accelerated or generic task.
 */
object AgentCompletionEvaluator {
    fun evaluate(
        contract: TaskContract,
        snapshot: UiSnapshot,
        validatedSelections: Set<String> = emptySet(),
        claimedEvidenceLabels: Set<String> = emptySet(),
    ): CompletionEvaluation {
        val visibleLabels = snapshot.nodes
            .asSequence()
            .filter { it.visibleToUser && it.enabled }
            .map { node ->
                listOfNotNull(node.text, node.contentDescription, node.viewId)
                    .joinToString(" ")
            }
            .filter(String::isNotBlank)
            .toList()
        if (contract.completionLabels.isEmpty()) {
            val groundedClaims = claimedEvidenceLabels
                .map(String::trim)
                .filter { it.length >= MIN_GENERIC_EVIDENCE_LENGTH }
                .filter { claim ->
                    visibleLabels.any { visible ->
                        visible.contains(claim, ignoreCase = true) ||
                            claim.contains(visible, ignoreCase = true)
                    }
                }
            return CompletionEvaluation(
                satisfied = groundedClaims.isNotEmpty(),
                evidence = groundedClaims,
                missing = if (groundedClaims.isEmpty()) {
                    setOf("visible_completion_evidence")
                } else {
                    emptySet()
                },
            )
        }
        val matched = contract.completionLabels.filter { required ->
            visibleLabels.any { visible ->
                visible.contains(required, ignoreCase = true)
            }
        }
        val requiredValidatedSelections = buildSet {
            if (AgentSelectionPolicy.GEOMETRIC_CENTER in contract.selectionPolicies) {
                add(SEAT_SELECTION_KIND)
            }
        }
        val missingValidatedSelections =
            requiredValidatedSelections - validatedSelections
        val missingLabels = contract.completionLabels - matched.toSet()
        return CompletionEvaluation(
            satisfied = missingLabels.isEmpty() && missingValidatedSelections.isEmpty(),
            evidence = visibleLabels.filter { visible ->
                matched.any { required -> visible.contains(required, ignoreCase = true) }
            } + requiredValidatedSelections
                .intersect(validatedSelections)
                .map { kind -> "validated:$kind" },
            missing = missingLabels +
                missingValidatedSelections.map { kind -> "validated:$kind" },
        )
    }

    private const val SEAT_SELECTION_KIND = "seat"
    private const val MIN_GENERIC_EVIDENCE_LENGTH = 2
}

data class ChangeableStateSlot(
    val currentValue: String,
    val changeNodeId: String,
)

internal object ChangeableStateResolver {
    fun resolve(snapshot: UiSnapshot): List<ChangeableStateSlot> {
        val nodes = snapshot.nodes
        return nodes.mapNotNull { change ->
            if (
                !change.visibleToUser ||
                !change.enabled ||
                CHANGE_TERMS.none(change.normalizedLabel()::contains)
            ) {
                return@mapNotNull null
            }
            val candidate = nearbyCurrentValue(change, nodes) ?: return@mapNotNull null
            ChangeableStateSlot(
                currentValue = candidate.normalizedSemanticText(),
                changeNodeId = change.id,
            )
        }.distinct()
    }

    private fun nearbyCurrentValue(
        change: UiNode,
        nodes: List<UiNode>,
    ): UiNode? {
        val changeIndex = nodes.indexOfFirst { it.id == change.id }
        if (changeIndex < 0) return null
        val parentId = change.parentId
        return nodes
            .asSequence()
            .drop(changeIndex + 1)
            .take(MAX_NEARBY_NODES)
            .filter { node ->
                node.visibleToUser &&
                    node.enabled &&
                    node.semanticText().isNotBlank() &&
                    node.semanticText().length <= MAX_VALUE_LENGTH &&
                    node.id != change.id &&
                    !isDescendantOf(node, change.id, nodes) &&
                    (
                        node.parentId == parentId ||
                            commonParentWithin(node, change, nodes, distance = 2)
                        )
            }
            .filterNot { node ->
                val label = node.normalizedSemanticText()
                CHANGE_TERMS.any(label::contains) ||
                    NON_VALUE_PATTERN.containsMatchIn(label)
            }
            .minByOrNull { node ->
                kotlin.math.abs(node.depth - change.depth)
            }
    }

    private fun isDescendantOf(
        node: UiNode,
        ancestorId: String,
        nodes: List<UiNode>,
    ): Boolean {
        val byId = nodes.associateBy(UiNode::id)
        var parent = node.parentId
        repeat(4) {
            if (parent == ancestorId) return true
            parent = parent?.let(byId::get)?.parentId
        }
        return false
    }

    private fun commonParentWithin(
        first: UiNode,
        second: UiNode,
        nodes: List<UiNode>,
        distance: Int,
    ): Boolean {
        val byId = nodes.associateBy(UiNode::id)
        fun ancestors(node: UiNode): Set<String> {
            val output = mutableSetOf<String>()
            var current = node.parentId
            repeat(distance) {
                current?.let(output::add)
                current = current?.let(byId::get)?.parentId
            }
            return output
        }
        return ancestors(first).intersect(ancestors(second)).isNotEmpty()
    }

    private const val MAX_NEARBY_NODES = 12
    private const val MAX_VALUE_LENGTH = 30
    private val CHANGE_TERMS = setOf("변경", "change", "edit", "switch")
    private val NON_VALUE_PATTERN = Regex(
        """^(?:\d{1,2}(?::\d{2})?|오늘|내일|확인|닫기|선택|완료)$""",
        RegexOption.IGNORE_CASE,
    )
}

/**
 * Rejections are attempts too. Counting only dispatched actions lets a policy
 * rejection consume the entire model step budget.
 */
class AgentAttemptHistory(
    private val maxSameRejections: Int = 2,
) {
    private val rejections = mutableMapOf<String, Int>()

    fun recordRejection(
        screenFingerprint: String,
        code: String,
    ): Boolean {
        val key = "$screenFingerprint:$code"
        val count = (rejections[key] ?: 0) + 1
        rejections[key] = count
        return count >= maxSameRejections
    }

    fun onScreenChanged() {
        rejections.clear()
    }
}

internal fun currentLocalClockMinutes(
    nowMillis: Long = System.currentTimeMillis(),
): Int = Calendar.getInstance().run {
    timeInMillis = nowMillis
    get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
}

internal fun UiNode.label(): String =
    listOfNotNull(text, contentDescription, hint, viewId)
        .joinToString(" ")
        .trim()

internal fun UiNode.normalizedLabel(): String =
    label().lowercase().filter(Char::isLetterOrDigit)

internal fun UiNode.normalizedLabels(): Set<String> =
    listOfNotNull(text, contentDescription, hint, viewId)
        .map { value -> value.lowercase().filter(Char::isLetterOrDigit) }
        .filter(String::isNotBlank)
        .toSet()

internal fun UiNode.semanticText(): String =
    listOfNotNull(text, contentDescription, hint)
        .joinToString(" ")
        .trim()

internal fun UiNode.normalizedSemanticText(): String =
    semanticText().lowercase().filter(Char::isLetterOrDigit)
