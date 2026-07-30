package com.example.mobileguiagent.model

/**
 * Resolves the simple audience-count state machine before a planner call.
 *
 * The active skill supplies view ids for the count, increment/decrement, and
 * continue controls. The runtime only parses the explicit requested count and
 * drives the exact observable state toward it.
 */
object RequestedAudienceInterceptor {
    fun intercept(
        contract: TaskContract,
        snapshot: UiSnapshot,
    ): RuntimeInterception? {
        val requestedAdults = contract.constraintValue(ADULT_CONSTRAINT)
            ?.toIntOrNull()
            ?.coerceIn(MIN_AUDIENCE, MAX_AUDIENCE)
            ?: return null
        val countNode = snapshot.findVisibleSlot(contract, ADULT_COUNT) ?: return null
        val currentAdults = countNode.semanticText().trim().toIntOrNull() ?: return null
        val target = when {
            currentAdults < requestedAdults ->
                snapshot.findClickableSlot(contract, ADULT_INCREMENT)
            currentAdults > requestedAdults ->
                snapshot.findClickableSlot(contract, ADULT_DECREMENT)
            else ->
                snapshot.findClickableSlot(contract, SEAT_SELECTION_CONTINUE)
        } ?: return null
        val code = when {
            currentAdults < requestedAdults -> "AUDIENCE_INCREMENT_ADULT"
            currentAdults > requestedAdults -> "AUDIENCE_DECREMENT_ADULT"
            else -> "AUDIENCE_CONFIRMED_CONTINUE"
        }
        return RuntimeInterception(
            code = code,
            message =
                "The requested adult count is $requestedAdults and the current count is " +
                    "$currentAdults. Use the exact skill-declared control ${target.viewId}.",
            automaticAction = RuntimeAction.TapNode(target.id),
        )
    }

    private const val ADULT_CONSTRAINT = "audience.adult"
    private const val MIN_AUDIENCE = 0
    private const val MAX_AUDIENCE = 8
    private const val ADULT_COUNT = "adult_count"
    private const val ADULT_INCREMENT = "adult_increment"
    private const val ADULT_DECREMENT = "adult_decrement"
    private const val SEAT_SELECTION_CONTINUE = "seat_selection_continue"
}

/**
 * Selects and verifies an explicit calendar date before any showtime can be
 * chosen. Candidate view-id patterns are skill-owned so the runtime remains
 * independent of a particular booking app.
 */
object RequestedDateInterceptor {
    fun intercept(
        contract: TaskContract,
        snapshot: UiSnapshot,
        validatedSelectionKeys: Set<String> = emptySet(),
    ): RuntimeInterception? {
        val required = contract.requiredDate ?: return null
        if (required.targetKey in validatedSelectionKeys) return null
        val target = snapshot.nodes.firstOrNull { node ->
            TaskStateConstraintInterceptor.proposedDate(
                contract = contract,
                target = node,
                snapshot = snapshot,
            ) == required
        } ?: return null
        val direct = target.visibleActionTarget(snapshot)
        if (direct != null) {
            return RuntimeInterception(
                code = "SELECT_REQUIRED_DATE",
                message =
                    "Select the exact immutable requested date ${required.compact}.",
                automaticAction = RuntimeAction.TapNode(direct.id),
            )
        }
        val container = snapshot.scrollableAncestorOf(target) ?: return null
        val direction =
            if (target.bounds.left + target.bounds.right >=
                container.bounds.left + container.bounds.right
            ) {
                "right"
            } else {
                "left"
            }
        return RuntimeInterception(
            code = "REVEAL_REQUIRED_DATE",
            message =
                "Reveal the exact immutable requested date ${required.compact}.",
            automaticAction = RuntimeAction.Scroll(container.id, direction),
        )
    }

    private fun UiNode.visibleActionTarget(snapshot: UiSnapshot): UiNode? {
        if (!visibleToUser || !enabled) return null
        return takeIf(UiNode::clickable)
            ?: snapshot.clickableDescendantOf(this)
            ?: UiTreeRelations.clickableAncestor(this, snapshot)
                ?.takeIf { it.visibleToUser && it.enabled }
    }

    private fun UiSnapshot.clickableDescendantOf(root: UiNode): UiNode? {
        val childrenByParent = nodes.groupBy(UiNode::parentId)
        val queue = ArrayDeque<Pair<UiNode, Int>>()
        childrenByParent[root.id].orEmpty().forEach { child -> queue.add(child to 1) }
        val candidates = mutableListOf<Pair<UiNode, Int>>()
        while (queue.isNotEmpty()) {
            val (node, distance) = queue.removeFirst()
            if (node.visibleToUser && node.enabled && node.clickable) {
                candidates += node to distance
            }
            childrenByParent[node.id].orEmpty().forEach { child ->
                queue.add(child to distance + 1)
            }
        }
        return candidates.minWithOrNull(
            compareBy<Pair<UiNode, Int>>(
                Pair<UiNode, Int>::second,
                { (node, _) -> node.bounds.width() * node.bounds.height() },
            ),
        )?.first
    }

    private fun UiSnapshot.scrollableAncestorOf(node: UiNode): UiNode? {
        val byId = nodes.associateBy(UiNode::id)
        var current: UiNode? = node
        while (current != null) {
            if (current.scrollable && current.visibleToUser && current.enabled) {
                return current
            }
            current = current.parentId?.let(byId::get)
        }
        return null
    }
}

/**
 * A date tap is valid only when the requested date itself becomes the active
 * control. WebView date wrappers are not clickable; tapping their large
 * ancestor can hit a neighboring day while still causing generic semantic
 * changes.
 */
object DateSelectionVerificationPolicy {
    fun applies(targetKey: String?, runtimeCode: String?): Boolean =
        runtimeCode == SELECT_REQUIRED_DATE ||
            targetKey?.startsWith(DATE_TARGET_PREFIX) == true

    fun enforce(
        generic: AgentActionVerification,
        contract: TaskContract,
        after: UiSnapshot,
    ): AgentActionVerification {
        val required = contract.requiredDate ?: return generic.copy(
            verified = false,
            message = "Date selection could not be verified because no required date exists.",
        )
        val childrenByParent = after.nodes.groupBy(UiNode::parentId)
        val roots = after.nodes.filter { node ->
            TaskStateConstraintInterceptor.proposedDate(
                contract = contract,
                target = node,
                snapshot = after,
            ) == required
        }
        val active = roots.any { root ->
            root.focused || root.selected ||
                descendantsOf(root, childrenByParent).any { node ->
                    node.focused || node.selected
                }
        }
        if (active) {
            return generic.copy(
                verified = true,
                evidence = generic.evidence + AgentActionEvidence.DATE_SELECTION_CONFIRMED,
                message = "Date selection verified on the exact requested date ${required.compact}.",
            )
        }
        return generic.copy(
            verified = false,
            message =
                "Generic UI changes were ignored for the date action: " +
                    "the exact requested date ${required.compact} is not active.",
        )
    }

    private fun descendantsOf(
        root: UiNode,
        childrenByParent: Map<String?, List<UiNode>>,
    ): Sequence<UiNode> = sequence {
        val queue = ArrayDeque<UiNode>()
        childrenByParent[root.id].orEmpty().forEach(queue::add)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            yield(node)
            childrenByParent[node.id].orEmpty().forEach(queue::add)
        }
    }

    private const val SELECT_REQUIRED_DATE = "SELECT_REQUIRED_DATE"
    private const val DATE_TARGET_PREFIX = "date:"
}

/**
 * Drives a skill-declared hierarchical entity route without spending cloud
 * planner turns on visible labels or bounded list scrolling.
 *
 * The skill owns the route hints (for example, theater "울산" belongs under
 * "부산/대구/경상"). The runtime only matches exact normalized labels, reveals
 * an already grounded off-screen target, and submits a non-zero selection.
 */
object RequiredEntityNavigationInterceptor {
    fun intercept(
        contract: TaskContract,
        snapshot: UiSnapshot,
    ): RuntimeInterception? {
        val completion = snapshot.findVisibleSlot(
            contract = contract,
            kind = ENTITY_SELECTION_COMPLETE,
        ) ?: return null
        val required = contract.requiredEntities[THEATER_KIND]
            .orEmpty()
            .associateBy(::normalizeEntityLabel)
        if (required.isEmpty()) return null

        val selectedCount = SELECTION_COUNT.find(
            completion.semanticText(),
        )?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val requiredLabels = snapshot.nodes.filter { node ->
            normalizeEntityLabel(node.semanticText()) in required
        }
        if (selectedCount > 0 && requiredLabels.any(UiNode::visibleToUser)) {
            val target = completion.takeIf(UiNode::clickable)
                ?: UiTreeRelations.clickableAncestor(completion, snapshot)
                ?: return null
            return RuntimeInterception(
                code = "ENTITY_SELECTION_CONFIRMED_CONTINUE",
                message =
                    "The exact required theater is selected and the selection count is " +
                        "$selectedCount. Submit the skill-declared completion control.",
                automaticAction = RuntimeAction.TapNode(target.id),
            )
        }

        requiredLabels.firstNotNullOfOrNull { node ->
            node.visibleActionTarget(snapshot)
        }?.let { target ->
            return RuntimeInterception(
                code = "SELECT_REQUIRED_ENTITY",
                message =
                    "Select the exact required theater ${target.semanticText()} from the " +
                        "grounded entity list.",
                automaticAction = RuntimeAction.TapNode(target.id),
            )
        }

        requiredLabels.firstNotNullOfOrNull { node ->
            snapshot.scrollableAncestorOf(node)?.let { container ->
                RuntimeInterception(
                    code = "REVEAL_REQUIRED_ENTITY",
                    message =
                        "Reveal the exact required theater ${node.semanticText()} inside its " +
                            "owning list before selection.",
                    automaticAction = RuntimeAction.Scroll(
                        nodeId = container.id,
                        direction = scrollDirection(node, container),
                    ),
                )
            }
        }?.let { return it }

        val route = contract.entityNavigationHints[THEATER_KIND]
            .orEmpty()
            .entries
            .firstOrNull { (entity, _) ->
                normalizeEntityLabel(entity) in required
            }
            ?.value
            .orEmpty()
        // Prefer the deepest currently visible route. A parent tab can remain
        // visible after it reveals the child route that actually advances.
        route.asReversed().forEach { routeLabel ->
            val normalizedRoute = normalizeEntityLabel(routeLabel)
            snapshot.nodes.firstNotNullOfOrNull { node ->
                node.takeIf {
                    normalizeEntityLabel(it.semanticText()) == normalizedRoute
                }?.visibleActionTarget(snapshot)
            }?.let { target ->
                return RuntimeInterception(
                    code = "SELECT_ENTITY_ROUTE_HINT",
                    message =
                        "Select skill-declared parent route $routeLabel before locating the " +
                            "exact required theater.",
                    automaticAction = RuntimeAction.TapNode(target.id),
                )
            }
        }
        return null
    }

    private fun UiNode.visibleActionTarget(snapshot: UiSnapshot): UiNode? {
        if (!visibleToUser || !enabled) return null
        return takeIf(UiNode::clickable)
            ?: UiTreeRelations.clickableAncestor(this, snapshot)
                ?.takeIf { it.visibleToUser && it.enabled }
    }

    private fun UiSnapshot.scrollableAncestorOf(node: UiNode): UiNode? {
        val byId = nodes.associateBy(UiNode::id)
        var current: UiNode? = node
        while (current != null) {
            if (current.scrollable && current.visibleToUser && current.enabled) {
                return current
            }
            current = current.parentId?.let(byId::get)
        }
        return null
    }

    private fun scrollDirection(node: UiNode, container: UiNode): String =
        if (node.bounds.top + node.bounds.bottom >=
            container.bounds.top + container.bounds.bottom
        ) {
            "down"
        } else {
            "up"
        }

    private fun normalizeEntityLabel(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private val SELECTION_COUNT = Regex("""\(([1-9]\d*)\s*/\s*\d+\)""")
    private const val THEATER_KIND = "theater"
    private const val ENTITY_SELECTION_COMPLETE = "entity_selection_complete"
}

/**
 * Selects the chronologically earliest eligible showtime from the complete
 * local UI tree before asking a cloud planner. WebViews often retain
 * off-screen showtimes in accessibility order, so display order is never used
 * as a proxy for time order. When the exact candidate is off-screen, this
 * policy first scrolls its owning container toward it.
 */
object EarliestShowtimeInterceptor {
    fun intercept(
        contract: TaskContract,
        snapshot: UiSnapshot,
        currentMinutes: Int = currentLocalClockMinutes(),
        excludedShowtimeMinutes: Set<Int> = emptySet(),
    ): RuntimeInterception? {
        if (!contract.allows(AgentCapability.ENFORCE_EXACT_SELECTIONS)) return null
        if (AgentSelectionPolicy.EARLIEST_AVAILABLE !in contract.selectionPolicies) return null
        if (contract.showtimeViewIdPatterns.isEmpty()) return null

        // A ticket-count modal may leave the schedule tree visible behind it.
        // Audience reconciliation owns that state and must run first.
        if (snapshot.findVisibleSlot(contract, ADULT_COUNT) != null) return null

        val earliest = TaskStateConstraintInterceptor.eligibleShowtimes(
            contract = contract,
            snapshot = snapshot,
            currentMinutes = currentMinutes,
            excludedShowtimeMinutes = excludedShowtimeMinutes,
        ).firstOrNull() ?: return null
        val directTarget = if (earliest.node.visibleToUser) {
            earliest.node
                .takeIf { node -> node.isVisibleActionTarget() }
                ?: UiTreeRelations.clickableAncestor(
                    node = earliest.node,
                    snapshot = snapshot,
                    maxDistance = MAX_CLICKABLE_ANCESTOR_DISTANCE,
                )?.takeIf { node -> node.isVisibleActionTarget() }
        } else {
            // WebViews may keep off-screen clickable nodes in the tree. Never
            // substitute a distant, screen-sized clickable wrapper for that
            // exact hidden showtime; reveal the candidate first.
            null
        }
        if (directTarget != null) {
            return RuntimeInterception(
                code = "SELECT_EARLIEST_SHOWTIME",
                message =
                    "Select the chronologically earliest eligible showtime " +
                        "${earliest.minutes.toClockText()} from the complete local tree.",
                automaticAction = RuntimeAction.TapNode(directTarget.id),
            )
        }

        val scrollContainer = snapshot.scrollableAncestorOf(earliest.node) ?: return null
        val direction = when {
            earliest.node.bounds.top >= scrollContainer.bounds.bottom -> "down"
            earliest.node.bounds.bottom <= scrollContainer.bounds.top -> "up"
            earliest.node.bounds.top + earliest.node.bounds.bottom >=
                scrollContainer.bounds.top + scrollContainer.bounds.bottom -> "down"
            else -> "up"
        }
        return RuntimeInterception(
            code = "REVEAL_EARLIEST_SHOWTIME",
            message =
                "The earliest eligible showtime ${earliest.minutes.toClockText()} is " +
                    "off-screen. Scroll $direction inside its owning container before " +
                    "selecting it.",
            automaticAction = RuntimeAction.Scroll(scrollContainer.id, direction),
        )
    }

    private fun UiNode.isVisibleActionTarget(): Boolean =
        visibleToUser &&
            enabled &&
            clickable

    private fun UiSnapshot.scrollableAncestorOf(node: UiNode): UiNode? {
        val byId = nodes.associateBy(UiNode::id)
        var current: UiNode? = node
        while (current != null) {
            if (
                current.scrollable &&
                current.visibleToUser &&
                current.enabled
            ) {
                return current
            }
            current = current.parentId?.let(byId::get)
        }
        return null
    }

    private fun Int.toClockText(): String =
        "%02d:%02d".format(this / 60, this % 60)

    private const val ADULT_COUNT = "adult_count"
    private const val MAX_CLICKABLE_ANCESTOR_DISTANCE = 2
}

/**
 * Distinguishes "the planner should choose a showtime" from "every future
 * showtime for the requested movie was already exhausted". Without this
 * terminal check, a planner can keep proposing an excluded earlier showtime
 * after the deterministic fallback has inspected every valid candidate.
 */
object ExhaustedShowtimeFallbackPolicy {
    fun allFutureCandidatesExhausted(
        contract: TaskContract,
        snapshot: UiSnapshot,
        excludedShowtimeMinutes: Set<Int>,
        currentMinutes: Int = currentLocalClockMinutes(),
    ): Boolean {
        if (
            AgentSelectionPolicy.NEXT_CANDIDATE_FALLBACK !in
            contract.selectionPolicies ||
            excludedShowtimeMinutes.isEmpty()
        ) {
            return false
        }
        val allCandidates = TaskStateConstraintInterceptor.eligibleShowtimes(
            contract = contract,
            snapshot = snapshot,
            currentMinutes = currentMinutes,
        )
        if (allCandidates.isEmpty()) return false
        return allCandidates.all { candidate ->
            candidate.minutes in excludedShowtimeMinutes
        }
    }
}

/**
 * Stops a same-day booking before a planner can drift to tomorrow when the
 * app has already rolled its schedule forward, or when today's exact-movie
 * showtimes are all in the past.
 */
object RequestedDayAvailabilityPolicy {
    fun noFutureShowtimeOnRequestedDay(
        contract: TaskContract,
        snapshot: UiSnapshot,
        currentMinutes: Int = currentLocalClockMinutes(),
    ): Boolean {
        if (
            !contract.allows(AgentCapability.ENFORCE_EXACT_SELECTIONS) ||
            AgentSelectionPolicy.TODAY_ONLY !in contract.selectionPolicies ||
            AgentSelectionPolicy.FUTURE_ONLY !in contract.selectionPolicies
        ) {
            return false
        }
        val resolution = ShowtimeCandidateResolver.resolve(contract, snapshot)
        if (resolution.nodes.isEmpty() || !resolution.scopedToRequestedMovie) return false

        val visibleLabels = snapshot.nodes
            .asSequence()
            .filter { node -> node.visibleToUser && node.enabled }
            .map(UiNode::normalizedSemanticText)
            .filter(String::isNotBlank)
            .toList()
        val todayVisible = visibleLabels.any { label -> TODAY_TERMS.any(label::contains) }
        val tomorrowVisible =
            visibleLabels.any { label -> TOMORROW_TERMS.any(label::contains) }
        if (tomorrowVisible && !todayVisible) return true
        if (!todayVisible) return false

        return TaskStateConstraintInterceptor.eligibleShowtimes(
            contract = contract,
            snapshot = snapshot,
            currentMinutes = currentMinutes,
        ).isEmpty()
    }

    private val TODAY_TERMS = setOf("오늘", "today")
    private val TOMORROW_TERMS = setOf("내일", "tomorrow")
}

sealed interface GeometricSeatDecision {
    data class Select(
        val seatKey: String,
        val centerSeatKeys: Set<String>,
        val interception: RuntimeInterception,
    ) : GeometricSeatDecision

    data class Exhausted(
        val showtimeMinutes: Int?,
        val centerSeatKeys: Set<String>,
    ) : GeometricSeatDecision
}

/**
 * Consumes the GEOMETRIC_CENTER policy against the complete, untruncated
 * accessibility snapshot. Only exact geometric-center seats are attempted;
 * failed center attempts never degrade silently to an off-center seat.
 */
object GeometricSeatInterceptor {
    fun evaluate(
        contract: TaskContract,
        snapshot: UiSnapshot,
        attemptedSeatKeys: Set<String>,
        validatedSeat: Boolean,
    ): GeometricSeatDecision? {
        if (AgentSelectionPolicy.GEOMETRIC_CENTER !in contract.selectionPolicies) return null
        if (contract.seatCandidatePatterns.isEmpty() || validatedSeat) return null
        val patterns = contract.seatCandidatePatterns.mapNotNull { source ->
            runCatching { Regex(source, RegexOption.IGNORE_CASE) }.getOrNull()
        }.toSet()
        if (patterns.isEmpty()) return null

        val resolution = SeatSelectionPolicy.resolve(snapshot, patterns)
        if (resolution.allSeats.isEmpty()) return null
        if (resolution.selectedEvidence.isNotEmpty()) return null

        val exactCenter = resolution.geometricCenterSeats
        val centerKeys = exactCenter.map(ParsedSeat::stableKey).toSet()
        val target = exactCenter.firstOrNull { seat ->
            seat.availableGeneral && seat.stableKey !in attemptedSeatKeys
        }
        if (target != null) {
            return GeometricSeatDecision.Select(
                seatKey = target.stableKey,
                centerSeatKeys = centerKeys,
                interception = RuntimeInterception(
                    code = "SELECT_GEOMETRIC_CENTER_SEAT",
                    message =
                        "Select exact geometric-center seat ${target.stableLabel} using the " +
                            "complete local seat grid.",
                    automaticAction = RuntimeAction.TapNode(
                        nodeId = target.node.id,
                        coordinateFallback = true,
                    ),
                ),
            )
        }

        val actionableCenterKeys = exactCenter
            .filter(ParsedSeat::availableGeneral)
            .map(ParsedSeat::stableKey)
            .toSet()
        if (
            actionableCenterKeys.isEmpty() ||
            attemptedSeatKeys.containsAll(actionableCenterKeys)
        ) {
            return GeometricSeatDecision.Exhausted(
                showtimeMinutes = currentShowtimeMinutes(contract, snapshot),
                centerSeatKeys = centerKeys,
            )
        }
        return null
    }

    fun currentShowtimeMinutes(
        contract: TaskContract,
        snapshot: UiSnapshot,
    ): Int? {
        val configuredIds = contract.stateSlotViewIds[TIME_SLOT].orEmpty()
        val configured = snapshot.nodes
            .asSequence()
            .filter { node ->
                node.visibleToUser &&
                    node.enabled &&
                    node.viewId?.let { viewId ->
                        configuredIds.any { configuredId ->
                            viewId == configuredId ||
                                viewId.endsWith("/$configuredId") ||
                                configuredId.endsWith("/$viewId")
                        }
                    } == true
            }
            .firstNotNullOfOrNull { node ->
                TaskTemporalParser.extractClock(node.semanticText())
            }
        return configured ?: snapshot.nodes
            .asSequence()
            .filter { node -> node.visibleToUser && node.enabled }
            .firstNotNullOfOrNull { node ->
                TaskTemporalParser.extractClock(node.semanticText())
            }
    }

    const val EXHAUSTED_SHOWTIME_FACT_PREFIX = "runtime.exhausted_showtime."
    private const val TIME_SLOT = "time"
}

internal class ShowtimeAttemptTracker(
    private val maxUnchangedAttempts: Int = 2,
) {
    private val unchangedAttempts = mutableMapOf<Int, Int>()

    fun recordUnchanged(minutes: Int): Boolean {
        val count = (unchangedAttempts[minutes] ?: 0) + 1
        unchangedAttempts[minutes] = count
        return count >= maxUnchangedAttempts
    }

    fun clear(minutes: Int) {
        unchangedAttempts.remove(minutes)
    }
}

/**
 * Tightens generic action verification for a showtime tap.
 *
 * A WebView can focus a showtime, refresh its remaining-seat text, or reflow
 * the same schedule without entering the booking flow. Those changes must not
 * validate the selection. Progress is accepted only when the next observation
 * exposes a contract-grounded booking stage: an informational blocking
 * surface, audience controls, or a seat-selection surface.
 */
object ShowtimeSelectionVerificationPolicy {
    fun applies(
        contract: TaskContract,
        targetKey: String?,
        runtimeCode: String?,
    ): Boolean {
        if (runtimeCode == SELECT_EARLIEST_SHOWTIME) return true
        val key = targetKey?.trim().orEmpty()
        if (key.startsWith(SHOWTIME_TARGET_PREFIX)) return true
        val viewId = key
            .takeIf { it.startsWith(VIEW_TARGET_PREFIX) }
            ?.removePrefix(VIEW_TARGET_PREFIX)
            ?: return false
        val shortViewId = viewId.substringAfterLast('/')
        return contract.showtimeViewIdPatterns.any { source ->
            runCatching { Regex(source, RegexOption.IGNORE_CASE) }
                .getOrNull()
                ?.let { pattern ->
                    pattern.matches(viewId) || pattern.matches(shortViewId)
                } == true
        }
    }

    fun enforce(
        generic: AgentActionVerification,
        contract: TaskContract,
        before: UiSnapshot,
        after: UiSnapshot,
    ): AgentActionVerification {
        val stages = newlyEnteredStages(
            contract = contract,
            before = before,
            after = after,
        )
        if (stages.isNotEmpty()) {
            return generic.copy(
                verified = true,
                evidence = generic.evidence + AgentActionEvidence.BOOKING_FLOW_ADVANCED,
                message =
                    "Showtime selection verified by booking-flow evidence: " +
                        stages.joinToString(),
            )
        }
        return generic.copy(
            verified = false,
            message =
                "Generic UI changes were ignored for the showtime action: no new " +
                    "informational popup, audience-selection controls, or seat-selection " +
                    "surface was observed.",
        )
    }

    private fun newlyEnteredStages(
        contract: TaskContract,
        before: UiSnapshot,
        after: UiSnapshot,
    ): Set<String> = buildSet {
        if (
            !before.hasAudienceSelectionStage(contract) &&
            after.hasAudienceSelectionStage(contract)
        ) {
            add("audience_selection")
        }
        if (
            !before.hasSeatSelectionStage(contract) &&
            after.hasSeatSelectionStage(contract)
        ) {
            add("seat_selection")
        }
        if (
            BlockingSurfaceInterceptor.intercept(contract, before) == null &&
            BlockingSurfaceInterceptor.intercept(contract, after) != null
        ) {
            add("informational_popup")
        }
    }

    private fun UiSnapshot.hasAudienceSelectionStage(contract: TaskContract): Boolean =
        findVisibleSlot(contract, ADULT_COUNT_SLOT) != null &&
            (
                findVisibleSlot(contract, ADULT_INCREMENT_SLOT) != null ||
                    findVisibleSlot(contract, ADULT_DECREMENT_SLOT) != null ||
                    findVisibleSlot(contract, SEAT_SELECTION_CONTINUE_SLOT) != null
                )

    private fun UiSnapshot.hasSeatSelectionStage(contract: TaskContract): Boolean {
        if (findVisibleSlot(contract, SEAT_PRICE_SLOT) != null) return true
        val patterns = contract.seatCandidatePatterns.mapNotNull { source ->
            runCatching { Regex(source, RegexOption.IGNORE_CASE) }.getOrNull()
        }.toSet()
        return patterns.isNotEmpty() &&
            SeatSelectionPolicy.resolve(this, patterns)
                .allSeats
                .any { seat -> seat.node.visibleToUser && seat.node.enabled }
    }

    private const val SELECT_EARLIEST_SHOWTIME = "SELECT_EARLIEST_SHOWTIME"
    private const val SHOWTIME_TARGET_PREFIX = "showtime:"
    private const val VIEW_TARGET_PREFIX = "view:"
    private const val ADULT_COUNT_SLOT = "adult_count"
    private const val ADULT_INCREMENT_SLOT = "adult_increment"
    private const val ADULT_DECREMENT_SLOT = "adult_decrement"
    private const val SEAT_SELECTION_CONTINUE_SLOT = "seat_selection_continue"
    private const val SEAT_PRICE_SLOT = "seat_price"
}

/**
 * Tightens generic action verification for seat taps.
 *
 * WebView seat buttons can accept focus while rejecting the actual selection
 * (for example an unavailable center seat whose accessibility label still
 * says "판매가능"). Layout or semantic-tree changes are therefore
 * insufficient. A seat action is verified only when the exact seat exposes a
 * selected state, or when the skill-declared seat price transitions from
 * zero/unavailable to a positive amount.
 */
object SeatSelectionVerificationPolicy {
    fun enforce(
        generic: AgentActionVerification,
        contract: TaskContract,
        before: UiSnapshot,
        after: UiSnapshot,
        targetKey: String,
    ): AgentActionVerification {
        if (!targetKey.startsWith(SEAT_TARGET_PREFIX)) return generic
        val patterns = contract.seatCandidatePatterns.mapNotNull { source ->
            runCatching { Regex(source, RegexOption.IGNORE_CASE) }.getOrNull()
        }.toSet()
        val exactSeatSelected = SeatSelectionPolicy.resolve(after, patterns)
            .selectedEvidence
            .any { seat -> seat.stableKey == targetKey }
        val beforePrice = seatPrice(before, contract)
        val afterPrice = seatPrice(after, contract)
        val positivePriceActivated =
            afterPrice != null &&
                afterPrice > 0L &&
                (beforePrice ?: 0L) <= 0L
        if (exactSeatSelected || positivePriceActivated) {
            return generic.copy(
                verified = true,
                evidence = generic.evidence + AgentActionEvidence.SEAT_SELECTION_CONFIRMED,
                message =
                    "Seat action verified by " +
                        if (exactSeatSelected) {
                            "the exact selected-state evidence."
                        } else {
                            "a positive seat-price transition ($beforePrice -> $afterPrice)."
                        },
            )
        }
        return generic.copy(
            verified = false,
            message =
                "Generic UI changes were ignored for $targetKey: the exact seat has no " +
                    "selected-state evidence and its price did not transition to a " +
                    "positive amount (before=$beforePrice, after=$afterPrice).",
        )
    }

    private fun seatPrice(
        snapshot: UiSnapshot,
        contract: TaskContract,
    ): Long? {
        val configuredIds = contract.stateSlotViewIds[SEAT_PRICE_SLOT].orEmpty()
        if (configuredIds.isEmpty()) return null
        val roots = snapshot.nodes.filter { node ->
            node.viewId?.let { viewId ->
                configuredIds.any { configured ->
                    viewId == configured ||
                        viewId.endsWith("/$configured") ||
                        configured.endsWith("/$viewId")
                }
            } == true
        }
        if (roots.isEmpty()) return null
        val childrenByParent = snapshot.nodes.groupBy(UiNode::parentId)
        val subtreeIds = buildSet {
            val pending = ArrayDeque(roots.map(UiNode::id))
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (!add(id)) continue
                childrenByParent[id].orEmpty().forEach { child ->
                    pending.addLast(child.id)
                }
            }
        }
        return snapshot.nodes
            .asSequence()
            .filter { node -> node.id in subtreeIds }
            .flatMap { node -> PRICE_VALUE.findAll(node.semanticText()) }
            .mapNotNull { match ->
                match.value.replace(",", "").toLongOrNull()
            }
            .maxOrNull()
    }

    private val PRICE_VALUE = Regex("""\d[\d,]*""")
    private const val SEAT_TARGET_PREFIX = "seat:"
    private const val SEAT_PRICE_SLOT = "seat_price"
}

private fun UiSnapshot.findVisibleSlot(
    contract: TaskContract,
    kind: String,
): UiNode? {
    val ids = contract.stateSlotViewIds[kind].orEmpty()
    return nodes.firstOrNull { node ->
        node.visibleToUser &&
            node.enabled &&
            node.viewId?.let { viewId ->
                ids.any { configured ->
                    viewId == configured ||
                        viewId.endsWith("/$configured") ||
                        configured.endsWith("/$viewId")
                }
            } == true
    }
}

private fun UiSnapshot.findClickableSlot(
    contract: TaskContract,
    kind: String,
): UiNode? = findVisibleSlot(contract, kind)?.takeIf(UiNode::clickable)
