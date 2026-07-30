package com.example.mobileguiagent.agent

import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.model.AgentRunContext
import com.example.mobileguiagent.model.AgentActionVerifier
import com.example.mobileguiagent.model.AgentGoalConstraint
import com.example.mobileguiagent.model.AgentGoalEntity
import com.example.mobileguiagent.model.AgentGoalPreference
import com.example.mobileguiagent.model.AgentGoalSpec
import com.example.mobileguiagent.model.AgentSelectionPolicy
import com.example.mobileguiagent.model.GeometricSeatInterceptor
import com.example.mobileguiagent.model.SeatSelectionPolicy
import com.example.mobileguiagent.model.ShowtimeCandidateResolver
import com.example.mobileguiagent.model.TaskContract
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.model.currentLocalClockMinutes
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class AgentWorkspaceStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    STALE,
}

enum class AgentPlanStepStatus {
    PENDING,
    ACTIVE,
    COMPLETED,
    BLOCKED,
}

enum class AgentFactSource {
    USER,
    SKILL,
    SCREEN,
    TOOL_RESULT,
    MODEL,
    RUNTIME,
}

enum class AgentCandidateCollectionStatus {
    PARTIAL,
    COMPLETE_SNAPSHOT,
    INVALIDATED,
}

data class AgentPlanStep(
    val id: String,
    val description: String,
    val status: AgentPlanStepStatus,
    val evidence: String? = null,
)

data class AgentFact(
    val key: String,
    val value: String,
    val source: AgentFactSource,
    val confirmed: Boolean,
    val observedAt: Long,
)

data class AgentCandidate(
    val kind: String,
    val stableKey: String,
    val label: String,
    val attributes: Map<String, String>,
    val observedAt: Long,
    val screenFingerprint: String,
)

data class AgentSelection(
    val kind: String,
    val candidateKey: String,
    val reason: String,
    val validated: Boolean,
    val selectedAt: Long,
)

data class AgentResumePoint(
    val packageName: String,
    val screenFingerprint: String,
    val semanticSignature: String,
    val nextPhase: String,
    val observedAt: Long,
)

data class AgentFailure(
    val code: String,
    val message: String,
    val step: Int,
    val recordedAt: Long,
)

data class AgentPendingAction(
    val callId: String,
    val idempotencyKey: String,
    val tool: String,
    val step: Int,
    val beforePackage: String?,
    val beforeFingerprint: String?,
    val beforeSemanticSignature: String?,
    val expectedChange: String?,
    val targetKey: String?,
    val stagedAt: Long,
)

/**
 * Durable state for one user goal across one or more agent invocations.
 *
 * Snapshot-local node ids are intentionally absent from selections and facts.
 * They may appear in diagnostic candidate attributes but are never trusted
 * after a fresh observation.
 */
data class AgentWorkspace(
    val id: String,
    val goal: String,
    val goalKey: String,
    val goalSpec: AgentGoalSpec?,
    val status: AgentWorkspaceStatus,
    val plan: List<AgentPlanStep>,
    val facts: Map<String, AgentFact>,
    val candidates: Map<String, List<AgentCandidate>>,
    val candidateStatuses: Map<String, AgentCandidateCollectionStatus>,
    val selections: Map<String, AgentSelection>,
    val resumePoint: AgentResumePoint?,
    val failures: List<AgentFailure>,
    val pendingAction: AgentPendingAction?,
    val loadedSkills: List<String>,
    val skillDigests: Map<String, String>,
    val runIds: List<String>,
    val completedActions: Int,
    val currentRunId: String?,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    fun attachGoalSpec(
        spec: AgentGoalSpec,
        now: Long = System.currentTimeMillis(),
    ): AgentWorkspace =
        if (goalSpec != null) {
            this
        } else {
            copy(
                goalSpec = sanitizedGoalSpec(spec),
                revision = revision + 1,
                updatedAt = now,
                schemaVersion = SCHEMA_VERSION,
            )
        }

    fun promptSection(): String = buildString {
        appendLine("DURABLE_AGENT_WORKSPACE:")
        appendLine("- workspace_id: $id")
        appendLine("- status: $status")
        appendLine("- revision: $revision")
        appendLine("- run_count: ${runIds.size}")
        appendLine(
            "- plan: " + JSONArray(
                plan.map { step ->
                    JSONObject()
                        .put("id", step.id)
                        .put("description", step.description)
                        .put("status", step.status.name)
                        .put("evidence", step.evidence)
                },
            ),
        )
        appendLine(
            "- confirmed_facts: " + JSONObject(
                facts.values
                    .filter(AgentFact::confirmed)
                    .associate { it.key to it.value },
            ),
        )
        appendLine(
            "- candidates: " + JSONObject().apply {
                candidates.forEach { (kind, values) ->
                    put(
                        kind,
                        JSONObject()
                            .put(
                                "status",
                                candidateStatuses[kind]?.name ?: "UNKNOWN",
                            )
                            .put(
                                "values",
                                JSONArray(
                                    values.take(MAX_PROMPT_CANDIDATES).map { candidate ->
                                        JSONObject()
                                            .put("key", candidate.stableKey)
                                            .put("label", candidate.label)
                                            .put(
                                                "attributes",
                                                JSONObject(candidate.attributes),
                                            )
                                    },
                                ),
                            ),
                    )
                }
            },
        )
        appendLine(
            "- selections: " + JSONObject(
                selections.mapValues { (_, selection) ->
                    JSONObject()
                        .put("candidate_key", selection.candidateKey)
                        .put("reason", selection.reason)
                        .put("validated", selection.validated)
                },
            ),
        )
        appendLine(
            "- recent_failures: " + JSONArray(
                failures.takeLast(MAX_PROMPT_FAILURES).map { failure ->
                    JSONObject()
                        .put("code", failure.code)
                        .put("message", failure.message)
                        .put("step", failure.step)
                },
            ),
        )
        appendLine(
            "- pending_action: " + (
                pendingAction?.let {
                    JSONObject()
                        .put("tool", it.tool)
                        .put("call_id", it.callId)
                        .put("idempotency_key", it.idempotencyKey)
                        .put("step", it.step)
                        .put("before_fingerprint", it.beforeFingerprint)
                        .put("expected_change", it.expectedChange)
                } ?: "none"
                ),
        )
        append(
            "Use this workspace as durable task state. Revalidate screen-derived " +
                "facts after resuming, never reuse an old node id, and revise the " +
                "plan when evidence contradicts it.",
        )
    }

    companion object {
        const val SCHEMA_VERSION = 8
        private const val MAX_PROMPT_CANDIDATES = 24
        private const val MAX_PROMPT_FAILURES = 5

        fun create(
            goal: String,
            runContext: AgentRunContext,
            now: Long = System.currentTimeMillis(),
        ): AgentWorkspace {
            val persistedGoal = AgentDataSanitizer.text(goal).orEmpty()
            return AgentWorkspace(
                id = UUID.randomUUID().toString(),
                goal = persistedGoal,
                // Match resumable workspaces without persisting the raw goal.
                // Hash the original normalized text so two different emails
                // or phone numbers do not collapse to the same redacted goal.
                goalKey = goalIdentity(goal),
                goalSpec = sanitizedGoalSpec(runContext.goalSpec),
                status = AgentWorkspaceStatus.ACTIVE,
                plan = defaultPlan(),
                facts = initialFacts(runContext),
                candidates = emptyMap(),
                candidateStatuses = emptyMap(),
                selections = emptyMap(),
                resumePoint = null,
                failures = emptyList(),
                pendingAction = null,
                loadedSkills = runContext.skills.all.map { it.id },
                skillDigests = runContext.skills.all.associate { it.id to it.digest },
                runIds = emptyList(),
                completedActions = 0,
                currentRunId = null,
                revision = 0,
                createdAt = now,
                updatedAt = now,
            )
        }

        internal fun normalizeGoal(goal: String): String =
            goal.lowercase(Locale.ROOT)
                .replace(Regex("""\s+"""), " ")
                .trim()

        fun goalIdentity(goal: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalizeGoal(goal).toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
            return "sha256:$digest"
        }

        private fun defaultPlan(): List<AgentPlanStep> = listOf(
            AgentPlanStep(
                id = "ground",
                description = "Observe the current app state and ground the goal",
                status = AgentPlanStepStatus.ACTIVE,
            ),
            AgentPlanStep(
                id = "execute",
                description = "Use safe tools to make measurable progress",
                status = AgentPlanStepStatus.PENDING,
            ),
            AgentPlanStep(
                id = "verify",
                description = "Verify the requested completion boundary",
                status = AgentPlanStepStatus.PENDING,
            ),
        )

        private fun initialFacts(runContext: AgentRunContext): Map<String, AgentFact> {
            val now = System.currentTimeMillis()
            return buildMap {
                runContext.taskContract.requiredEntities.forEach { (kind, values) ->
                    values.sorted().forEachIndexed { index, value ->
                        val key = if (values.size == 1) "goal.$kind" else "goal.$kind.$index"
                        put(
                            key,
                            AgentFact(
                                key = key,
                                value = AgentDataSanitizer.text(value).orEmpty(),
                                source = AgentFactSource.USER,
                                confirmed = true,
                                observedAt = now,
                            ),
                        )
                    }
                }
            }
        }

        private fun sanitizedGoalSpec(spec: AgentGoalSpec): AgentGoalSpec =
            spec.copy(
                objective = AgentDataSanitizer.text(spec.objective).orEmpty(),
                entities = spec.entities.map { entity ->
                    AgentGoalEntity(
                        name = entity.name,
                        value = AgentDataSanitizer.text(entity.value).orEmpty(),
                        required = entity.required,
                    )
                },
                constraints = spec.constraints.map { constraint ->
                    AgentGoalConstraint(
                        subject = constraint.subject,
                        operator = constraint.operator,
                        value = AgentDataSanitizer.text(constraint.value).orEmpty(),
                        hard = constraint.hard,
                    )
                },
                preferences = spec.preferences.map { preference ->
                    AgentGoalPreference(
                        subject = preference.subject,
                        operator = preference.operator,
                        value = AgentDataSanitizer.text(preference.value).orEmpty(),
                        fallback = AgentDataSanitizer.text(preference.fallback),
                    )
                },
                successCriteria = spec.successCriteria.mapNotNull(AgentDataSanitizer::text),
                assumptions = spec.assumptions.mapNotNull(AgentDataSanitizer::text),
            )
    }
}

/**
 * Pure reducer. Every persistent state transition flows through this object so
 * model output cannot overwrite immutable user facts or arbitrary JSON paths.
 */
object AgentWorkspaceReducer {
    fun beginRun(
        workspace: AgentWorkspace,
        runId: String,
        now: Long = System.currentTimeMillis(),
    ): AgentWorkspace {
        // A showtime exhaustion result is valid only inside the run that
        // observed and attempted those seats. Availability can change, and a
        // failed device interaction must not become a permanent fact that
        // prevents a later run from revalidating the same showtime.
        val refreshedFacts = workspace.facts.filterKeys { key ->
            !key.startsWith(GeometricSeatInterceptor.EXHAUSTED_SHOWTIME_FACT_PREFIX) &&
                key != "derived.earliest_showtime"
        }
        return workspace.copy(
            status = AgentWorkspaceStatus.ACTIVE,
            currentRunId = runId,
            runIds = (workspace.runIds + runId).distinct().takeLast(MAX_RUN_IDS),
            facts = refreshedFacts,
            updatedAt = now,
            revision = workspace.revision + 1,
        )
    }

    fun observe(
        workspace: AgentWorkspace,
        snapshot: UiSnapshot,
        contract: TaskContract,
        now: Long = System.currentTimeMillis(),
    ): AgentWorkspace {
        val observedFacts = extractStateFacts(snapshot, contract, now)
        val showtimes = extractShowtimes(snapshot, contract, now)
        val seats = extractSeats(snapshot, contract, now)
        val exhaustedShowtimes = workspace.facts
            .keys
            .asSequence()
            .filter { key ->
                key.startsWith(GeometricSeatInterceptor.EXHAUSTED_SHOWTIME_FACT_PREFIX)
            }
            .mapNotNull { key ->
                key.removePrefix(GeometricSeatInterceptor.EXHAUSTED_SHOWTIME_FACT_PREFIX)
                    .toIntOrNull()
            }
            .toSet()
        val nextFacts = workspace.facts.toMutableMap().apply {
            observedFacts.forEach { fact ->
                val existing = get(fact.key)
                if (existing?.source != AgentFactSource.USER) put(fact.key, fact)
            }
            showtimes
                .filter { candidate ->
                    candidate.attributes["minutes"]
                        ?.toIntOrNull()
                        ?.let { minutes -> minutes !in exhaustedShowtimes }
                        ?: false
                }
                .minByOrNull {
                    it.attributes["minutes"]?.toIntOrNull() ?: Int.MAX_VALUE
                }
                ?.let { earliest ->
                    put(
                        "derived.earliest_showtime",
                        AgentFact(
                            key = "derived.earliest_showtime",
                            value = earliest.attributes.getValue("time"),
                            source = AgentFactSource.RUNTIME,
                            confirmed = true,
                            observedAt = now,
                        ),
                    )
                }
        }
        val nextCandidates = workspace.candidates.toMutableMap()
        val nextCandidateStatuses = workspace.candidateStatuses.toMutableMap()
        val nextSelections = workspace.selections.toMutableMap()
        if (contract.showtimeViewIdPatterns.isNotEmpty()) {
            if (showtimes.isNotEmpty()) {
                nextCandidates["showtime"] = showtimes
                nextCandidateStatuses["showtime"] =
                    AgentCandidateCollectionStatus.COMPLETE_SNAPSHOT
            } else {
                if (nextCandidates["showtime"].isNullOrEmpty()) {
                    nextCandidateStatuses["showtime"] =
                        AgentCandidateCollectionStatus.INVALIDATED
                    nextFacts.remove("derived.earliest_showtime")
                } else {
                    nextCandidateStatuses["showtime"] =
                        AgentCandidateCollectionStatus.PARTIAL
                }
            }
        }
        if (contract.seatCandidatePatterns.isNotEmpty()) {
            if (seats.isNotEmpty()) {
                nextCandidates["seat"] = seats
                nextCandidateStatuses["seat"] =
                    AgentCandidateCollectionStatus.COMPLETE_SNAPSHOT
            } else if (showtimes.isNotEmpty()) {
                nextCandidates.remove("seat")
                nextSelections.remove("seat")
                nextCandidateStatuses["seat"] =
                    AgentCandidateCollectionStatus.INVALIDATED
            }
        }
        return workspace.copy(
            plan = advanceGroundingStep(workspace.plan, snapshot),
            facts = nextFacts,
            candidates = nextCandidates,
            candidateStatuses = nextCandidateStatuses,
            selections = nextSelections,
            resumePoint = AgentResumePoint(
                packageName = snapshot.packageName,
                screenFingerprint = snapshot.fingerprint.hash,
                semanticSignature = AgentActionVerifier.semanticSignature(snapshot),
                nextPhase = "PLAN",
                observedAt = now,
            ),
            updatedAt = now,
            revision = workspace.revision + 1,
        )
    }

    fun recordDecision(
        workspace: AgentWorkspace,
        proposedPlan: List<String>,
        progressSummary: String,
        now: Long = System.currentTimeMillis(),
    ): AgentWorkspace {
        val revisedPlan = revisePlan(workspace.plan, proposedPlan)
        return workspace.copy(
            plan = revisedPlan,
            facts = workspace.facts + AgentFact(
                key = "model.progress",
                value = AgentDataSanitizer.text(progressSummary).orEmpty(),
                source = AgentFactSource.MODEL,
                confirmed = false,
                observedAt = now,
            ).let { it.key to it },
            resumePoint = workspace.resumePoint?.copy(
                nextPhase = "ACT",
                observedAt = now,
            ),
            updatedAt = now,
            revision = workspace.revision + 1,
        )
    }

    fun recordRuntimeFact(
        workspace: AgentWorkspace,
        key: String,
        value: String,
        now: Long = System.currentTimeMillis(),
    ): AgentWorkspace {
        val safeKey = key.trim().takeIf(String::isNotBlank) ?: return workspace
        val fact = AgentFact(
            key = safeKey,
            value = AgentDataSanitizer.text(value).orEmpty(),
            source = AgentFactSource.RUNTIME,
            confirmed = true,
            observedAt = now,
        )
        return workspace.copy(
            facts = workspace.facts + (safeKey to fact),
            updatedAt = now,
            revision = workspace.revision + 1,
        )
    }

    fun recordToolResult(
        workspace: AgentWorkspace,
        call: DeviceToolCall,
        result: DeviceToolResult,
        step: Int,
        callId: String?,
        now: Long = System.currentTimeMillis(),
    ): AgentWorkspace {
        val failure = (result as? DeviceToolResult.Error)?.let {
            AgentFailure(
                it.code,
                AgentDataSanitizer.text(it.message).orEmpty(),
                step,
                now,
            )
        }
        val pendingMatches = workspace.pendingAction?.let { pending ->
            callId == null || pending.callId == callId
        } ?: false
        return workspace.copy(
            completedActions = workspace.completedActions + 1,
            failures = (workspace.failures + listOfNotNull(failure)).takeLast(MAX_FAILURES),
            pendingAction = when {
                !pendingMatches -> workspace.pendingAction
                result.awaitsVerification(call) -> workspace.pendingAction
                else -> null
            },
            resumePoint = workspace.resumePoint?.copy(
                nextPhase = if (result.succeeded()) "VERIFY" else "PLAN",
                observedAt = now,
            ),
            facts = workspace.facts + AgentFact(
                key = "tool.last",
                value = "${call.name}:${if (result.succeeded()) "success" else "failure"}",
                source = AgentFactSource.TOOL_RESULT,
                confirmed = true,
                observedAt = now,
            ).let { it.key to it },
            updatedAt = now,
            revision = workspace.revision + 1,
        )
    }

    fun stageToolIntent(
        workspace: AgentWorkspace,
        call: DeviceToolCall,
        step: Int,
        callId: String,
        expectedChange: String?,
        targetKey: String?,
        now: Long = System.currentTimeMillis(),
    ): AgentWorkspace = workspace.copy(
        pendingAction = AgentPendingAction(
            callId = callId,
            idempotencyKey =
                "${workspace.id}:${workspace.currentRunId.orEmpty()}:$callId",
            tool = call.name,
            step = step,
            beforePackage = workspace.resumePoint?.packageName,
            beforeFingerprint = workspace.resumePoint?.screenFingerprint,
            beforeSemanticSignature = workspace.resumePoint?.semanticSignature,
            expectedChange = AgentDataSanitizer.text(expectedChange),
            targetKey = AgentDataSanitizer.text(targetKey),
            stagedAt = now,
        ),
        resumePoint = workspace.resumePoint?.copy(
            nextPhase = "EXECUTE_OR_RECONCILE",
            observedAt = now,
        ),
        updatedAt = now,
        revision = workspace.revision + 1,
    )

    fun recordVerification(
        workspace: AgentWorkspace,
        callId: String?,
        targetKey: String? = null,
        verified: Boolean,
        evidence: List<String>,
        message: String,
        step: Int,
        now: Long = System.currentTimeMillis(),
    ): AgentWorkspace {
        if (
            workspace.pendingAction != null &&
            callId != null &&
            workspace.pendingAction.callId != callId
        ) {
            return workspace
        }
        val failure = if (verified) {
            null
        } else {
            AgentFailure(
                code = "ACTION_EFFECT_UNVERIFIED",
                message = AgentDataSanitizer.text(message).orEmpty(),
                step = step,
                recordedAt = now,
            )
        }
        val pending = workspace.pendingAction
        val verifiedTargetKey = pending?.targetKey ?: targetKey
        val verifiedSelection = if (verified && verifiedTargetKey != null) {
            workspace.candidates.entries.firstNotNullOfOrNull { (kind, candidates) ->
                candidates.firstOrNull { candidate ->
                    verifiedTargetKey == candidate.stableKey ||
                        verifiedTargetKey == "view:${candidate.attributes["view_id"]}"
                }?.let { candidate ->
                    kind to AgentSelection(
                        kind = kind,
                        candidateKey = candidate.stableKey,
                        reason = pending?.expectedChange ?: "runtime_verified",
                        validated = true,
                        selectedAt = now,
                    )
                }
            } ?: verifiedTargetKey
                // A successfully selected WebView seat commonly disappears
                // from the next "available seats" snapshot. The verified,
                // stable seat key is stronger evidence than its absence from
                // that refreshed candidate set, so persist it directly.
                .takeIf { targetKey -> targetKey.startsWith(SEAT_TARGET_PREFIX) }
                ?.let { targetKey ->
                    SEAT_SELECTION_KIND to AgentSelection(
                        kind = SEAT_SELECTION_KIND,
                        candidateKey = targetKey,
                        reason = pending?.expectedChange ?: "runtime_verified",
                        validated = true,
                        selectedAt = now,
                    )
                }
            ?: verifiedTargetKey
                .takeIf { targetKey -> targetKey.startsWith(DATE_TARGET_PREFIX) }
                ?.let { targetKey ->
                    DATE_SELECTION_KIND to AgentSelection(
                        kind = DATE_SELECTION_KIND,
                        candidateKey = targetKey,
                        reason = pending?.expectedChange ?: "runtime_verified",
                        validated = true,
                        selectedAt = now,
                    )
                }
        } else {
            null
        }
        return workspace.copy(
            pendingAction = null,
            selections = verifiedSelection?.let { workspace.selections + it }
                ?: workspace.selections,
            failures = (workspace.failures + listOfNotNull(failure)).takeLast(MAX_FAILURES),
            facts = workspace.facts + AgentFact(
                key = "tool.last_verification",
                value = if (verified) {
                    "verified:${evidence.joinToString(",")}"
                } else {
                    "unverified"
                },
                source = AgentFactSource.RUNTIME,
                confirmed = true,
                observedAt = now,
            ).let { it.key to it },
            resumePoint = workspace.resumePoint?.copy(
                nextPhase = "PLAN",
                observedAt = now,
            ),
            updatedAt = now,
            revision = workspace.revision + 1,
        )
    }

    fun finish(
        workspace: AgentWorkspace,
        status: AgentWorkspaceStatus,
        message: String,
        now: Long = System.currentTimeMillis(),
    ): AgentWorkspace {
        val finalPlan = if (status == AgentWorkspaceStatus.COMPLETED) {
            workspace.plan.map { it.copy(status = AgentPlanStepStatus.COMPLETED) }
        } else {
            workspace.plan
        }
        return workspace.copy(
            status = status,
            plan = finalPlan,
            facts = workspace.facts + AgentFact(
                key = "run.outcome",
                value = AgentDataSanitizer.text(message).orEmpty(),
                source = AgentFactSource.RUNTIME,
                confirmed = true,
                observedAt = now,
            ).let { it.key to it },
            currentRunId = null,
            // A pause/failure can race with a blocking Android side effect.
            // Preserve PREPARED state so the next run reconciles rather than
            // blindly issuing the action again.
            pendingAction = if (status == AgentWorkspaceStatus.COMPLETED) {
                null
            } else {
                workspace.pendingAction
            },
            updatedAt = now,
            revision = workspace.revision + 1,
        )
    }

    private fun extractStateFacts(
        snapshot: UiSnapshot,
        contract: TaskContract,
        now: Long,
    ): List<AgentFact> = buildList {
        contract.stateSlotViewIds.forEach { (kind, ids) ->
            snapshot.nodes
                .filter { node ->
                    node.visibleToUser && node.enabled &&
                        node.viewId?.let { viewId ->
                            viewId in ids || ids.any { viewId.endsWith("/$it") }
                        } == true
                }
                .mapNotNull { node -> node.semanticLabel() }
                .distinct()
                .forEachIndexed { index, value ->
                    val key = if (index == 0) "screen.$kind" else "screen.$kind.$index"
                    add(AgentFact(key, value, AgentFactSource.SCREEN, true, now))
                }
        }
    }

    private fun extractShowtimes(
        snapshot: UiSnapshot,
        contract: TaskContract,
        now: Long,
    ): List<AgentCandidate> {
        if (contract.showtimeViewIdPatterns.isEmpty()) return emptyList()
        val patterns = contract.showtimeViewIdPatterns.mapNotNull {
            runCatching { Regex(it) }.getOrNull()
        }
        val currentMinutes = currentLocalClockMinutes(now)
        return ShowtimeCandidateResolver.resolve(contract, snapshot).nodes.mapNotNull { node ->
            val viewId = node.viewId ?: return@mapNotNull null
            val compact = patterns.firstNotNullOfOrNull { pattern ->
                pattern.find(viewId)?.groupValues?.getOrNull(1)
            } ?: return@mapNotNull null
            val minutes = compactClockMinutes(compact) ?: return@mapNotNull null
            if (
                AgentSelectionPolicy.FUTURE_ONLY in contract.selectionPolicies &&
                minutes <= currentMinutes
            ) {
                return@mapNotNull null
            }
            val time = "%02d:%02d".format(minutes / 60, minutes % 60)
            AgentCandidate(
                kind = "showtime",
                stableKey = "showtime:$viewId",
                label = node.semanticLabel() ?: time,
                attributes = mapOf(
                    "time" to time,
                    "minutes" to minutes.toString(),
                    "view_id" to viewId,
                ),
                observedAt = now,
                screenFingerprint = snapshot.fingerprint.hash,
            )
        }.distinctBy(AgentCandidate::stableKey)
            .sortedWith(
                compareBy<AgentCandidate> {
                    it.attributes["minutes"]?.toIntOrNull() ?: Int.MAX_VALUE
                }.thenBy(AgentCandidate::stableKey),
            )
    }

    private fun extractSeats(
        snapshot: UiSnapshot,
        contract: TaskContract,
        now: Long,
    ): List<AgentCandidate> {
        val patterns = contract.seatCandidatePatterns.mapNotNull { source ->
            runCatching { Regex(source, RegexOption.IGNORE_CASE) }.getOrNull()
        }.toSet()
        if (patterns.isEmpty()) return emptyList()
        val resolution = SeatSelectionPolicy.resolve(snapshot, patterns)
        val centerKeys = resolution.geometricCenterSeats
            .map { seat -> seat.stableKey }
            .toSet()
        return resolution.allSeats.map { seat ->
            AgentCandidate(
                kind = "seat",
                stableKey = seat.stableKey,
                label = seat.label,
                attributes = mapOf(
                    "row" to seat.row,
                    "number" to seat.number.toString(),
                    "available_general" to seat.availableGeneral.toString(),
                    "geometric_center" to (seat.stableKey in centerKeys).toString(),
                ),
                observedAt = now,
                screenFingerprint = snapshot.fingerprint.hash,
            )
        }
    }

    private fun revisePlan(
        previous: List<AgentPlanStep>,
        proposed: List<String>,
    ): List<AgentPlanStep> {
        if (proposed.isEmpty()) return previous
        val normalized = proposed
            .map { item -> AgentDataSanitizer.text(item).orEmpty().trim() }
            .filter(String::isNotBlank)
            .distinct()
        if (normalized.isEmpty()) return previous
        val completed = previous.filter { it.status == AgentPlanStepStatus.COMPLETED }
        val activeDescription = normalized.first()
        return completed + normalized.mapIndexed { index, description ->
            AgentPlanStep(
                id = stableStepId(description, index),
                description = description,
                status = if (description == activeDescription) {
                    AgentPlanStepStatus.ACTIVE
                } else {
                    AgentPlanStepStatus.PENDING
                },
            )
        }
    }

    private fun advanceGroundingStep(
        plan: List<AgentPlanStep>,
        snapshot: UiSnapshot,
    ): List<AgentPlanStep> {
        if (plan.none { it.id == "ground" && it.status == AgentPlanStepStatus.ACTIVE }) {
            return plan
        }
        return plan.map { step ->
            when {
                step.id == "ground" -> step.copy(
                    status = AgentPlanStepStatus.COMPLETED,
                    evidence = "Observed ${snapshot.packageName} (${snapshot.nodes.size} nodes)",
                )
                step.id == "execute" && step.status == AgentPlanStepStatus.PENDING ->
                    step.copy(status = AgentPlanStepStatus.ACTIVE)
                else -> step
            }
        }
    }

    private fun stableStepId(description: String, index: Int): String =
        "step_${index}_${description.lowercase(Locale.ROOT).hashCode().toUInt().toString(16)}"

    private fun compactClockMinutes(raw: String): Int? {
        if (!raw.matches(Regex("""\d{4}"""))) return null
        val hour = raw.take(2).toIntOrNull() ?: return null
        val minute = raw.takeLast(2).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun UiNode.semanticLabel(): String? =
        listOfNotNull(text, contentDescription, hint)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun DeviceToolResult.succeeded(): Boolean = when (this) {
        is DeviceToolResult.Action -> success
        is DeviceToolResult.Error -> false
        is DeviceToolResult.Screenshot,
        is DeviceToolResult.Success,
        is DeviceToolResult.UiObservation,
        -> true
    }

    private fun DeviceToolResult.awaitsVerification(call: DeviceToolCall): Boolean = when (this) {
        is DeviceToolResult.Action -> success && call.name != "wait"
        is DeviceToolResult.Success -> call.name == "launch_app"
        is DeviceToolResult.Error,
        is DeviceToolResult.Screenshot,
        is DeviceToolResult.UiObservation,
        -> false
    }

    private const val MAX_FAILURES = 20
    private const val MAX_RUN_IDS = 50
        private const val SEAT_SELECTION_KIND = "seat"
        private const val DATE_SELECTION_KIND = "date"
        private const val DATE_TARGET_PREFIX = "date:"
    private const val SEAT_TARGET_PREFIX = "seat:"
}
