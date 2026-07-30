package com.example.mobileguiagent.model

import java.security.MessageDigest

/**
 * Observable, model-independent evidence that a dispatched action changed the
 * task state.
 *
 * Bounds, traversal-local node ids, parent ids, node order and focus are
 * intentionally excluded. Those values often change during layout or
 * accessibility refreshes without any user-visible progress.
 */
enum class AgentActionEvidence {
    PACKAGE_CHANGED,
    SEMANTIC_UI_CHANGED,
    CONTROL_STATE_CHANGED,
    EXPECTED_CHANGE_OBSERVED,
    STATE_SLOT_CHANGED,
    BOOKING_FLOW_ADVANCED,
    DATE_SELECTION_CONFIRMED,
    SEAT_SELECTION_CONFIRMED,
}

data class AgentActionVerification(
    val verified: Boolean,
    val evidence: Set<AgentActionEvidence>,
    val changedStateSlots: Set<String> = emptySet(),
    val matchedExpectedKeywords: Set<String> = emptySet(),
    val ignoredTransientNodeCount: Int = 0,
    val message: String,
)

/**
 * Verifies an action from two accessibility snapshots without relying on the
 * raw [UiSnapshot.fingerprint]. The fingerprint includes bounds, so treating a
 * hash change as progress produces false positives during relayouts.
 */
object AgentActionVerifier {
    /**
     * Compact resume/checkpoint signature. It excludes bounds, traversal ids,
     * ordering, focus and transient surfaces for the same reasons as [verify].
     */
    fun semanticSignature(snapshot: UiSnapshot): String {
        val projection = SemanticProjection.from(snapshot)
        val canonical = buildString {
            append(snapshot.packageName)
            projection.nodes
                .groupingBy(SemanticNode::signature)
                .eachCount()
                .toSortedMap()
                .forEach { (signature, count) ->
                    append('|')
                    append(signature)
                    append('#')
                    append(count)
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun verify(
        before: UiSnapshot,
        after: UiSnapshot,
        expectedChange: String? = null,
        stateSlotViewIds: Map<String, Set<String>> = emptyMap(),
    ): AgentActionVerification {
        val evidence = linkedSetOf<AgentActionEvidence>()
        if (before.packageName != after.packageName) {
            evidence += AgentActionEvidence.PACKAGE_CHANGED
        }

        val beforeProjection = SemanticProjection.from(before)
        val afterProjection = SemanticProjection.from(after)

        val controlStateChanged = controlStates(beforeProjection.nodes) !=
            controlStates(afterProjection.nodes)
        if (controlStateChanged) {
            evidence += AgentActionEvidence.CONTROL_STATE_CHANGED
        }

        if (hasDurableSemanticChange(beforeProjection.nodes, afterProjection.nodes)) {
            evidence += AgentActionEvidence.SEMANTIC_UI_CHANGED
        }

        val changedStateSlots = changedStateSlots(
            before = beforeProjection.nodes,
            after = afterProjection.nodes,
            configuredViewIds = stateSlotViewIds,
        )
        if (changedStateSlots.isNotEmpty()) {
            evidence += AgentActionEvidence.STATE_SLOT_CHANGED
        }

        val matchedExpectedKeywords = newlyObservedExpectedKeywords(
            expectedChange = expectedChange,
            before = beforeProjection.nodes,
            after = afterProjection.nodes,
        )
        if (matchedExpectedKeywords.isNotEmpty()) {
            evidence += AgentActionEvidence.EXPECTED_CHANGE_OBSERVED
        }

        val ignoredTransientNodeCount =
            beforeProjection.ignoredTransientNodes + afterProjection.ignoredTransientNodes
        val verified = evidence.isNotEmpty()
        return AgentActionVerification(
            verified = verified,
            evidence = evidence,
            changedStateSlots = changedStateSlots,
            matchedExpectedKeywords = matchedExpectedKeywords,
            ignoredTransientNodeCount = ignoredTransientNodeCount,
            message = when {
                verified ->
                    "Action verified by ${evidence.joinToString()}."
                else ->
                    "No semantic progress was found; only layout, ordering, focus, " +
                        "transient UI, or no observable change occurred."
            },
        )
    }

    fun verify(
        before: UiSnapshot,
        after: UiSnapshot,
        expectedChange: String?,
        taskContract: TaskContract,
    ): AgentActionVerification = verify(
        before = before,
        after = after,
        expectedChange = expectedChange,
        stateSlotViewIds = taskContract.stateSlotViewIds,
    )

    private fun hasDurableSemanticChange(
        before: List<SemanticNode>,
        after: List<SemanticNode>,
    ): Boolean {
        val beforeCounts = before.signatureCounts()
        val afterCounts = after.signatureCounts()
        if (beforeCounts == afterCounts) return false

        val removed = positiveDifference(beforeCounts, afterCounts)
        val added = positiveDifference(afterCounts, beforeCounts)
        if (removed.isNotEmpty()) return true

        // A short-lived custom banner is sometimes exposed as one or more
        // label-only TextViews without a snackbar/toast class. Conservatively
        // ignore a pure addition of such weak labels. A caller can still verify
        // an intentional text-only result through expectedChange.
        val descriptorsBySignature = after.associateBy(SemanticNode::signature)
        return added.keys.any { signature ->
            descriptorsBySignature[signature]?.strong == true
        }
    }

    private fun changedStateSlots(
        before: List<SemanticNode>,
        after: List<SemanticNode>,
        configuredViewIds: Map<String, Set<String>>,
    ): Set<String> = configuredViewIds
        .mapNotNullTo(linkedSetOf()) { (slot, viewIds) ->
            val beforeValues = slotValues(before, viewIds)
            val afterValues = slotValues(after, viewIds)
            slot.takeIf {
                (beforeValues.isNotEmpty() || afterValues.isNotEmpty()) &&
                    beforeValues != afterValues
            }
        }

    private fun slotValues(
        nodes: List<SemanticNode>,
        configuredViewIds: Set<String>,
    ): List<String> = nodes
        .asSequence()
        .filter { node ->
            node.viewId?.let { viewId ->
                configuredViewIds.any { configured ->
                    val normalized = configured.lowercase().trim()
                    viewId == normalized ||
                        viewId.endsWith("/$normalized") ||
                        normalized.endsWith("/$viewId")
                }
            } == true
        }
        .map(SemanticNode::semanticText)
        .filter(String::isNotBlank)
        .sorted()
        .toList()

    private fun newlyObservedExpectedKeywords(
        expectedChange: String?,
        before: List<SemanticNode>,
        after: List<SemanticNode>,
    ): Set<String> {
        val keywords = expectedKeywords(expectedChange)
        if (keywords.isEmpty()) return emptySet()
        val beforeTexts = before.map(SemanticNode::searchableText)
        val afterTexts = after.map(SemanticNode::searchableText)
        return keywords.filterTo(linkedSetOf()) { keyword ->
            afterTexts.any { text -> keyword in text } &&
                beforeTexts.none { text -> keyword in text }
        }
    }

    private fun expectedKeywords(expectedChange: String?): Set<String> {
        val source = expectedChange?.lowercase()?.trim().orEmpty()
        if (source.isEmpty()) return emptySet()
        val clockValues = CLOCK_PATTERN.findAll(source)
            .map { match -> normalize(match.value) }
            .filter(String::isNotBlank)
        val words = WORD_PATTERN.findAll(source)
            .map { match -> normalize(match.value) }
            .filter { word ->
                word.length >= MIN_EXPECTED_KEYWORD_LENGTH &&
                    word !in EXPECTED_CHANGE_STOP_WORDS
            }
        return (clockValues + words).toCollection(linkedSetOf())
    }

    private fun controlStates(nodes: List<SemanticNode>): Map<String, List<ControlState>> =
        nodes.asSequence()
            .filter { node -> node.checked != null || node.selected }
            .groupBy(SemanticNode::identity)
            .mapValues { (_, matchingNodes) ->
                matchingNodes
                    .map { node ->
                        ControlState(
                            checked = node.checked,
                            selected = node.selected,
                        )
                    }
                    .sortedWith(
                        compareBy<ControlState>(
                            { it.checked?.toString().orEmpty() },
                            ControlState::selected,
                        ),
                    )
            }

    private fun List<SemanticNode>.signatureCounts(): Map<String, Int> =
        groupingBy(SemanticNode::signature).eachCount()

    private fun positiveDifference(
        minuend: Map<String, Int>,
        subtrahend: Map<String, Int>,
    ): Map<String, Int> = minuend.mapNotNull { (signature, count) ->
        val difference = count - (subtrahend[signature] ?: 0)
        signature.takeIf { difference > 0 }?.let { it to difference }
    }.toMap()

    private data class SemanticProjection(
        val nodes: List<SemanticNode>,
        val ignoredTransientNodes: Int,
    ) {
        companion object {
            fun from(snapshot: UiSnapshot): SemanticProjection {
                val byId = snapshot.nodes.associateBy(UiNode::id)
                val transientRoots = snapshot.nodes
                    .filter { node -> node.isTransientSurface() }
                    .mapTo(hashSetOf(), UiNode::id)
                val semanticNodes = snapshot.nodes
                    .asSequence()
                    .filter(UiNode::visibleToUser)
                    .filterNot { node -> node.isInSubtree(transientRoots, byId) }
                    // WebView showtime cards can expose a live remaining-seat
                    // counter as an anonymous value such as "1154" (11/54).
                    // It can change without any navigation or control-state
                    // progress, so it must not perturb action verification or
                    // durable resume signatures.
                    .filterNot { node -> node.isAnonymousNumericLabel() }
                    .mapNotNull { node -> node.toSemanticNode() }
                    .toList()
                return SemanticProjection(
                    nodes = semanticNodes,
                    ignoredTransientNodes = snapshot.nodes.count { node ->
                        node.visibleToUser && node.isInSubtree(transientRoots, byId)
                    },
                )
            }
        }
    }

    private data class SemanticNode(
        val viewId: String?,
        val semanticText: String,
        val className: String,
        val roleDescription: String,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val enabled: Boolean,
        val checked: Boolean?,
        val selected: Boolean,
        val range: UiRange?,
    ) {
        val identity: String = listOf(
            viewId.orEmpty(),
            semanticText,
            className,
            roleDescription,
            clickable,
            editable,
            scrollable,
        ).joinToString("|")

        val signature: String = listOf(
            identity,
            enabled,
            range?.min,
            range?.max,
            range?.current,
        ).joinToString("|")

        val searchableText: String = normalize(
            listOfNotNull(semanticText, viewId, roleDescription)
                .joinToString(" "),
        )

        val strong: Boolean =
            !viewId.isNullOrBlank() ||
                clickable ||
                editable ||
                scrollable ||
                checked != null ||
                selected ||
                range != null ||
                roleDescription.isNotBlank() ||
                (className.isNotBlank() && !className.endsWith("textview"))
    }

    private data class ControlState(
        val checked: Boolean?,
        val selected: Boolean,
    )

    private fun UiNode.toSemanticNode(): SemanticNode? {
        val semanticText = normalize(
            listOfNotNull(text, contentDescription, hint)
                .joinToString(" "),
        )
        val normalizedViewId = viewId?.lowercase()?.trim()?.takeIf(String::isNotBlank)
        val normalizedClass = className?.lowercase()?.trim().orEmpty()
        val normalizedRole = normalize(roleDescription.orEmpty())
        val meaningful =
            semanticText.isNotBlank() ||
                !normalizedViewId.isNullOrBlank() ||
                clickable ||
                editable ||
                scrollable ||
                checked != null ||
                selected ||
                range != null
        if (!meaningful) return null
        return SemanticNode(
            viewId = normalizedViewId,
            semanticText = semanticText,
            className = normalizedClass,
            roleDescription = normalizedRole,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            enabled = enabled,
            checked = checked,
            selected = selected,
            range = range,
        )
    }

    private fun UiNode.isTransientSurface(): Boolean {
        val structuralLabel = listOfNotNull(className, roleDescription, viewId)
            .joinToString(" ")
            .lowercase()
            .filter(Char::isLetterOrDigit)
        return TRANSIENT_SURFACE_MARKERS.any(structuralLabel::contains)
    }

    private fun UiNode.isAnonymousNumericLabel(): Boolean {
        if (!viewId.isNullOrBlank()) return false
        if (
            clickable ||
            editable ||
            scrollable ||
            checked != null ||
            selected ||
            range != null ||
            !roleDescription.isNullOrBlank()
        ) {
            return false
        }
        val value = listOfNotNull(text, contentDescription, hint)
            .joinToString("")
            .filterNot(Char::isWhitespace)
        return value.isNotEmpty() && value.all(Char::isDigit)
    }

    private fun UiNode.isInSubtree(
        roots: Set<String>,
        byId: Map<String, UiNode>,
    ): Boolean {
        var current: UiNode? = this
        val visited = hashSetOf<String>()
        while (current != null && visited.add(current.id)) {
            if (current.id in roots) return true
            current = current.parentId?.let(byId::get)
        }
        return false
    }

    private fun normalize(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private const val MIN_EXPECTED_KEYWORD_LENGTH = 2
    private val CLOCK_PATTERN = Regex("""(?<!\d)(?:[01]?\d|2[0-3]):[0-5]\d(?!\d)""")
    private val WORD_PATTERN = Regex("""[\p{L}\p{N}]+""")
    private val TRANSIENT_SURFACE_MARKERS = setOf(
        "toast",
        "snackbar",
        "temporarymessage",
        "transientmessage",
    )
    private val EXPECTED_CHANGE_STOP_WORDS = setOf(
        "the",
        "and",
        "button",
        "screen",
        "state",
        "change",
        "changed",
        "appears",
        "visible",
        "opens",
        "opened",
        "success",
        "화면",
        "상태",
        "버튼",
        "변경",
        "변경됨",
        "이동",
        "표시",
        "표시됨",
        "나타남",
        "열림",
        "성공",
    )
}
