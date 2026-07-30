package com.example.mobileguiagent.model

/**
 * Detects a two-state action loop such as A -> B -> A -> B.
 *
 * Callers should build semantic signatures from stable screen state plus the
 * visible target, rather than traversal-local accessibility node IDs.
 */
internal class AgentActionCycleGuard(
    private val capacity: Int = 6,
) {
    private val accepted = ArrayDeque<String>()

    fun wouldRepeatAlternatingCycle(candidate: String): Boolean {
        if (accepted.size < 3) return false
        val recent = accepted.takeLast(3)
        return recent[0] == recent[2] &&
            recent[1] == candidate &&
            recent[0] != recent[1]
    }

    /**
     * Catches longer cycles (for example A -> B -> C -> A -> B -> C) that do
     * not fit the strict alternating pattern. A third visit to the exact same
     * screen-state/action pair is rejected.
     */
    fun wouldRepeatStateAction(
        candidate: String,
        maxPreviousOccurrences: Int = 2,
    ): Boolean = accepted.count(candidate::equals) >= maxPreviousOccurrences

    fun record(signature: String) {
        accepted.addLast(signature)
        while (accepted.size > capacity) {
            accepted.removeFirst()
        }
    }

    fun reset() {
        accepted.clear()
    }
}

/**
 * Prevents a planner from deselecting the requested item after a multi-select
 * UI already exposes a nonzero completion control such as "선택 완료 (1/5)".
 */
internal object SelectionProgressGuard {
    fun correction(
        goal: String,
        targetLabel: String?,
        visibleLabels: Sequence<String>,
    ): String? {
        val target = targetLabel
            ?.normalizeForSelection()
            ?.takeIf { it.length >= 2 }
            ?: return null
        if (target !in goal.normalizeForSelection()) return null
        val completion = visibleLabels
            .map(String::trim)
            .firstOrNull { label -> NONZERO_COMPLETION.containsMatchIn(label) }
            ?: return null
        return "The requested target \"$targetLabel\" is already selected: " +
            "\"$completion\" is visible with a nonzero count. Do not tap the " +
            "target again because that deselects it. Use the visible completion control."
    }

    private fun String.normalizeForSelection(): String =
        lowercase().filter(Char::isLetterOrDigit)

    private val NONZERO_COMPLETION = Regex(
        pattern =
            """(?:선택\s*완료|complete|done).*?\(([1-9]\d*)\s*/\s*\d+\)""",
        option = RegexOption.IGNORE_CASE,
    )
}

/**
 * Keeps actions inside a semantic modal when the accessibility tree also
 * exposes clickable background content.
 */
internal object ModalProgressGuard {
    fun correction(
        target: UiNode?,
        snapshot: UiSnapshot,
    ): String? {
        val nodes = snapshot.nodes.filter { node -> node.visibleToUser && node.enabled }
        val longMessages = nodes.filter { node ->
            node.label().length >= MIN_NOTICE_LENGTH && !node.clickable
        }
        if (longMessages.isEmpty()) return null

        val modalAction = nodes.firstOrNull { node ->
            node.normalizedLabels().any(MODAL_ACTIONS::contains)
        } ?: return null
        if (target == null) return null
        if (
            target.id == modalAction.id ||
            longMessages.any { message ->
                UiTreeRelations.shareNearbyAncestor(message, target, snapshot)
            }
        ) {
            return null
        }

        val modalActionLabel = modalAction.normalizedLabels().firstOrNull()
            ?: modalAction.label()
        return "A long notice/modal is active and exposes \"$modalActionLabel\". " +
            "Do not interact with background content such as \"${target.label()}\". " +
            "Use the modal action first, then inspect the newer screen."
    }

    private const val MIN_NOTICE_LENGTH = 60
    private val MODAL_ACTIONS = setOf(
        "확인",
        "닫기",
        "동의",
        "계속",
        "ok",
        "close",
        "continue",
    )
}
