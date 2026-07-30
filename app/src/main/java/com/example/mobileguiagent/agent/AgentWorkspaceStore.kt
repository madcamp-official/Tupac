package com.example.mobileguiagent.agent

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.example.mobileguiagent.model.AgentGoalSpecJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

interface AgentWorkspaceStore {
    fun findResumable(goal: String): AgentWorkspace? {
        val goalKey = AgentWorkspace.goalIdentity(goal)
        return listResumable()
            .asSequence()
            .filter { workspace -> workspace.goalKey == goalKey }
            .maxByOrNull(AgentWorkspace::updatedAt)
    }

    fun findResumable(
        goal: String,
        skillDigests: Map<String, String>,
    ): AgentWorkspace? {
        val goalKey = AgentWorkspace.goalIdentity(goal)
        return listResumable()
            .asSequence()
            .filter { workspace -> workspace.goalKey == goalKey }
            .filter { workspace -> workspace.skillDigests == skillDigests }
            .maxByOrNull(AgentWorkspace::updatedAt)
    }
    fun openOrCreate(
        goal: String,
        skillDigests: Map<String, String>,
        factory: () -> AgentWorkspace,
    ): AgentWorkspace
    fun load(workspaceId: String): AgentWorkspace?
    fun save(workspace: AgentWorkspace, event: String): AgentWorkspace
    fun listResumable(): List<AgentWorkspace>
    fun delete(workspaceId: String): Boolean
}

class AgentWorkspaceRevisionConflict(
    workspaceId: String,
    expectedRevision: Long,
    actualRevision: Long,
) : IllegalStateException(
    "Workspace $workspaceId revision conflict: expected=$expectedRevision actual=$actualRevision",
)

/**
 * App-private, crash-safe workspace persistence.
 *
 * `workspace.json` is replaced atomically. `events.jsonl` is append-only and
 * makes state transitions inspectable without making the audit log executable
 * input. The latest workspace snapshot remains the sole resume source.
 */
class FileAgentWorkspaceStore private constructor(
    private val root: File,
) : AgentWorkspaceStore {
    override fun findResumable(
        goal: String,
        skillDigests: Map<String, String>,
    ): AgentWorkspace? = synchronized(lock) {
        val goalKey = AgentWorkspace.goalIdentity(goal)
        listResumable()
            .asSequence()
            .filter { workspace -> workspace.goalKey.startsWith(GOAL_KEY_PREFIX) }
            .filter { workspace -> workspace.goalKey == goalKey }
            .filter { workspace -> workspace.skillDigests == skillDigests }
            .maxByOrNull(AgentWorkspace::updatedAt)
    }

    override fun openOrCreate(
        goal: String,
        skillDigests: Map<String, String>,
        factory: () -> AgentWorkspace,
    ): AgentWorkspace = synchronized(lock) {
        val goalKey = AgentWorkspace.goalIdentity(goal)
        val resumable = listResumable()
        // Schema 1-6 used redacted goal text as the key, which could collapse
        // distinct private goals. Those workspaces are not safe to auto-resume.
        resumable
            .filterNot { workspace -> workspace.goalKey.startsWith(GOAL_KEY_PREFIX) }
            .forEach { legacy ->
                save(
                    legacy.copy(
                        status = AgentWorkspaceStatus.STALE,
                        currentRunId = null,
                        pendingAction = null,
                        revision = legacy.revision + 1,
                        updatedAt = System.currentTimeMillis(),
                    ),
                    "workspace_stale",
                )
            }
        val matchingGoal = resumable
            .filter { workspace -> workspace.goalKey.startsWith(GOAL_KEY_PREFIX) }
            .filter { it.goalKey == goalKey }
        matchingGoal
            .filter { it.skillDigests == skillDigests }
            .maxByOrNull(AgentWorkspace::updatedAt)
            ?: run {
                matchingGoal.forEach { stale ->
                    save(
                        stale.copy(
                            status = AgentWorkspaceStatus.STALE,
                            currentRunId = null,
                            pendingAction = null,
                            revision = stale.revision + 1,
                            updatedAt = System.currentTimeMillis(),
                        ),
                        "workspace_stale",
                    )
                }
                factory().also { save(it, "workspace_created") }
            }
    }

    override fun load(workspaceId: String): AgentWorkspace? = synchronized(lock) {
        val file = snapshotFile(workspaceId)
        if (file.isFile) {
            runCatching { AgentWorkspaceJson.decode(file.readText()) }
                .getOrNull()
                ?.let { return@synchronized it }
        }
        val checkpointDirectory = File(workspaceDirectory(workspaceId), CHECKPOINT_DIRECTORY)
        checkpointDirectory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::getName)
            .firstNotNullOfOrNull { checkpoint ->
                runCatching { AgentWorkspaceJson.decode(checkpoint.readText()) }.getOrNull()
            }
    }

    override fun save(
        workspace: AgentWorkspace,
        event: String,
    ): AgentWorkspace = synchronized(lock) {
        val directory = workspaceDirectory(workspace.id).apply { mkdirs() }
        val target = File(directory, SNAPSHOT_FILE)
        val persisted = if (target.isFile) load(workspace.id) else null
        if (persisted == null) {
            require(workspace.revision == 0L) {
                "A new workspace must start at revision 0, got ${workspace.revision}."
            }
        } else {
            val expectedRevision = persisted.revision + 1
            if (workspace.revision != expectedRevision) {
                throw AgentWorkspaceRevisionConflict(
                    workspaceId = workspace.id,
                    expectedRevision = expectedRevision,
                    actualRevision = workspace.revision,
                )
            }
        }
        val atomicFile = AtomicFile(target)
        val output = atomicFile.startWrite()
        val encoded = AgentWorkspaceJson.encode(workspace).toString(2)
        try {
            output.write(encoded.toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
        if (event in CHECKPOINT_EVENTS) {
            runCatching {
                writeCheckpoint(directory, workspace.revision, encoded)
            }.onFailure { error ->
                Log.w(TAG, "checkpoint_write_failed workspace=${workspace.id}", error)
            }
        }
        runCatching {
            File(directory, EVENT_FILE).appendText(
                JSONObject()
                    .put("workspace_id", workspace.id)
                    .put("revision", workspace.revision)
                    .put("event", event)
                    .put("timestamp_ms", System.currentTimeMillis())
                    .toString() + "\n",
                StandardCharsets.UTF_8,
            )
        }.onFailure { error ->
            Log.w(TAG, "workspace_event_write_failed workspace=${workspace.id}", error)
        }
        workspace
    }

    private fun writeCheckpoint(
        workspaceDirectory: File,
        revision: Long,
        encoded: String,
    ) {
        val checkpointDirectory = File(workspaceDirectory, CHECKPOINT_DIRECTORY).apply {
            mkdirs()
        }
        val checkpoint = AtomicFile(
            File(checkpointDirectory, "checkpoint-${revision.toString().padStart(8, '0')}.json"),
        )
        val output = checkpoint.startWrite()
        try {
            output.write(encoded.toByteArray(StandardCharsets.UTF_8))
            checkpoint.finishWrite(output)
        } catch (error: Throwable) {
            checkpoint.failWrite(output)
            throw error
        }
        checkpointDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("checkpoint-") }
            .sortedByDescending(File::getName)
            .drop(MAX_CHECKPOINTS)
            .forEach(File::delete)
    }

    override fun listResumable(): List<AgentWorkspace> = synchronized(lock) {
        root.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull { load(it.name) }
            .filter { it.status == AgentWorkspaceStatus.ACTIVE || it.status == AgentWorkspaceStatus.PAUSED }
            .sortedByDescending(AgentWorkspace::updatedAt)
            .toList()
    }

    override fun delete(workspaceId: String): Boolean = synchronized(lock) {
        val directory = workspaceDirectory(workspaceId)
        if (!directory.exists()) return@synchronized false
        directory.walkBottomUp().all(File::delete)
    }

    private fun workspaceDirectory(id: String): File = File(root, id)
    private fun snapshotFile(id: String): File = File(workspaceDirectory(id), SNAPSHOT_FILE)

    companion object {
        private const val DIRECTORY = "agent-workspaces"
        private const val TAG = "AgentWorkspaceStore"
        private const val SNAPSHOT_FILE = "workspace.json"
        private const val EVENT_FILE = "events.jsonl"
        private const val CHECKPOINT_DIRECTORY = "checkpoints"
        private const val MAX_CHECKPOINTS = 20
        private const val GOAL_KEY_PREFIX = "sha256:"
        private val CHECKPOINT_EVENTS = setOf(
            "workspace_created",
            "workspace_stale",
            "goal_spec_migrated",
            "run_started",
            "tool_call",
            "tool_result",
            "action_verification",
            "run_finished",
            "run_cancelled",
            "run_failed",
        )
        private val lock = Any()

        fun create(context: Context): FileAgentWorkspaceStore =
            FileAgentWorkspaceStore(
                File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() },
            )
    }
}

internal object AgentWorkspaceJson {
    fun encode(workspace: AgentWorkspace): JSONObject = JSONObject()
        .put("schema_version", workspace.schemaVersion)
        .put("id", workspace.id)
        .put("goal", workspace.goal)
        .put("goal_key", workspace.goalKey)
        .put("goal_spec", workspace.goalSpec?.let(AgentGoalSpecJson::encode))
        .put("status", workspace.status.name)
        .put(
            "plan",
            JSONArray(
                workspace.plan.map { step ->
                    JSONObject()
                        .put("id", step.id)
                        .put("description", step.description)
                        .put("status", step.status.name)
                        .put("evidence", step.evidence)
                },
            ),
        )
        .put(
            "facts",
            JSONArray(
                workspace.facts.values.map { fact ->
                    JSONObject()
                        .put("key", fact.key)
                        .put("value", fact.value)
                        .put("source", fact.source.name)
                        .put("confirmed", fact.confirmed)
                        .put("observed_at", fact.observedAt)
                },
            ),
        )
        .put(
            "candidates",
            JSONObject().apply {
                workspace.candidates.forEach { (kind, candidates) ->
                    put(
                        kind,
                        JSONArray(
                            candidates.map { candidate ->
                                JSONObject()
                                    .put("kind", candidate.kind)
                                    .put("stable_key", candidate.stableKey)
                                    .put("label", candidate.label)
                                    .put("attributes", JSONObject(candidate.attributes))
                                    .put("observed_at", candidate.observedAt)
                                    .put("screen_fingerprint", candidate.screenFingerprint)
                            },
                        ),
                    )
                }
            },
        )
        .put(
            "candidate_statuses",
            JSONObject(
                workspace.candidateStatuses.mapValues { (_, status) -> status.name },
            ),
        )
        .put(
            "selections",
            JSONArray(
                workspace.selections.values.map { selection ->
                    JSONObject()
                        .put("kind", selection.kind)
                        .put("candidate_key", selection.candidateKey)
                        .put("reason", selection.reason)
                        .put("validated", selection.validated)
                        .put("selected_at", selection.selectedAt)
                },
            ),
        )
        .put(
            "resume_point",
            workspace.resumePoint?.let { point ->
                JSONObject()
                    .put("package_name", point.packageName)
                    .put("screen_fingerprint", point.screenFingerprint)
                    .put("semantic_signature", point.semanticSignature)
                    .put("next_phase", point.nextPhase)
                    .put("observed_at", point.observedAt)
            },
        )
        .put(
            "failures",
            JSONArray(
                workspace.failures.map { failure ->
                    JSONObject()
                        .put("code", failure.code)
                        .put("message", failure.message)
                        .put("step", failure.step)
                        .put("recorded_at", failure.recordedAt)
                },
            ),
        )
        .put(
            "pending_action",
            workspace.pendingAction?.let { pending ->
                JSONObject()
                    .put("call_id", pending.callId)
                    .put("idempotency_key", pending.idempotencyKey)
                    .put("tool", pending.tool)
                    .put("step", pending.step)
                    .put("before_package", pending.beforePackage)
                    .put("before_fingerprint", pending.beforeFingerprint)
                    .put("before_semantic_signature", pending.beforeSemanticSignature)
                    .put("expected_change", pending.expectedChange)
                    .put("target_key", pending.targetKey)
                    .put("staged_at", pending.stagedAt)
            },
        )
        .put("loaded_skills", JSONArray(workspace.loadedSkills))
        .put("skill_digests", JSONObject(workspace.skillDigests))
        .put("run_ids", JSONArray(workspace.runIds))
        .put("completed_actions", workspace.completedActions)
        .put("current_run_id", workspace.currentRunId)
        .put("revision", workspace.revision)
        .put("created_at", workspace.createdAt)
        .put("updated_at", workspace.updatedAt)

    fun decode(text: String): AgentWorkspace {
        val json = JSONObject(text)
        val schemaVersion = json.getInt("schema_version")
        require(schemaVersion in 1..AgentWorkspace.SCHEMA_VERSION) {
            "Unsupported workspace schema: $schemaVersion"
        }
        return AgentWorkspace(
            id = json.getString("id"),
            goal = json.getString("goal"),
            goalKey = json.getString("goal_key"),
            goalSpec = json.optJSONObject("goal_spec")?.let(AgentGoalSpecJson::decode),
            status = enumValueOrDefault(json.optString("status"), AgentWorkspaceStatus.STALE),
            plan = json.optJSONArray("plan").objects().map { item ->
                AgentPlanStep(
                    id = item.getString("id"),
                    description = item.getString("description"),
                    status = enumValueOrDefault(
                        item.optString("status"),
                        AgentPlanStepStatus.PENDING,
                    ),
                    evidence = item.optionalString("evidence"),
                )
            },
            facts = json.optJSONArray("facts").objects()
                .map { item ->
                    AgentFact(
                        key = item.getString("key"),
                        value = item.getString("value"),
                        source = enumValueOrDefault(
                            item.optString("source"),
                            AgentFactSource.RUNTIME,
                        ),
                        confirmed = item.optBoolean("confirmed"),
                        observedAt = item.optLong("observed_at"),
                    )
                }
                .associateBy(AgentFact::key),
            candidates = buildMap {
                val candidatesJson = json.optJSONObject("candidates") ?: JSONObject()
                candidatesJson.keys().forEach { kind ->
                    put(
                        kind,
                        candidatesJson.optJSONArray(kind).objects().map { item ->
                            AgentCandidate(
                                kind = item.optString("kind", kind),
                                stableKey = item.getString("stable_key"),
                                label = item.getString("label"),
                                attributes = item.optJSONObject("attributes").stringMap(),
                                observedAt = item.optLong("observed_at"),
                                screenFingerprint = item.optString("screen_fingerprint"),
                            )
                        },
                    )
                }
            },
            candidateStatuses = buildMap {
                val statuses = json.optJSONObject("candidate_statuses") ?: JSONObject()
                statuses.keys().forEach { kind ->
                    put(
                        kind,
                        enumValueOrDefault(
                            statuses.optString(kind),
                            AgentCandidateCollectionStatus.PARTIAL,
                        ),
                    )
                }
            },
            selections = json.optJSONArray("selections").objects()
                .map { item ->
                    AgentSelection(
                        kind = item.getString("kind"),
                        candidateKey = item.getString("candidate_key"),
                        reason = item.getString("reason"),
                        validated = item.optBoolean("validated"),
                        selectedAt = item.optLong("selected_at"),
                    )
                }
                .associateBy(AgentSelection::kind),
            resumePoint = json.optJSONObject("resume_point")?.let { point ->
                AgentResumePoint(
                    packageName = point.getString("package_name"),
                    screenFingerprint = point.getString("screen_fingerprint"),
                    semanticSignature = point.optString("semantic_signature")
                        .takeIf(String::isNotBlank)
                        ?: point.getString("screen_fingerprint"),
                    nextPhase = point.getString("next_phase"),
                    observedAt = point.optLong("observed_at"),
                )
            },
            failures = json.optJSONArray("failures").objects().map { item ->
                AgentFailure(
                    code = item.getString("code"),
                    message = item.getString("message"),
                    step = item.optInt("step"),
                    recordedAt = item.optLong("recorded_at"),
                )
            },
            pendingAction = json.optJSONObject("pending_action")?.let { pending ->
                val tool = pending.getString("tool")
                val step = pending.optInt("step")
                val callId = pending.optionalString("call_id")
                    ?: "legacy:$tool:$step"
                AgentPendingAction(
                    callId = callId,
                    idempotencyKey = pending.optionalString("idempotency_key")
                        ?: "legacy:$callId",
                    tool = tool,
                    step = step,
                    beforePackage = pending.optionalString("before_package"),
                    beforeFingerprint = pending.optionalString("before_fingerprint"),
                    beforeSemanticSignature =
                        pending.optionalString("before_semantic_signature"),
                    expectedChange = pending.optionalString("expected_change"),
                    targetKey = pending.optionalString("target_key"),
                    stagedAt = pending.optLong("staged_at"),
                )
            },
            loadedSkills = json.optJSONArray("loaded_skills").strings(),
            skillDigests = json.optJSONObject("skill_digests").stringMap(),
            runIds = json.optJSONArray("run_ids").strings(),
            completedActions = json.optInt("completed_actions"),
            currentRunId = json.optionalString("current_run_id"),
            revision = json.optLong("revision"),
            createdAt = json.optLong("created_at"),
            updatedAt = json.optLong("updated_at"),
            schemaVersion = AgentWorkspace.SCHEMA_VERSION,
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        raw: String,
        default: T,
    ): T = enumValues<T>().firstOrNull { it.name == raw } ?: default

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optJSONObject(index)?.let(::add)
        }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun JSONObject?.stringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key -> put(key, optString(key)) }
        }
    }

    private fun JSONObject.optionalString(key: String): String? =
        takeIf { has(key) && !isNull(key) }
            ?.optString(key)
            ?.takeIf(String::isNotBlank)
}
