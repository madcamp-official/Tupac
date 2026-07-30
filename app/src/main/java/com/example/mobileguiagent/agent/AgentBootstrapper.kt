package com.example.mobileguiagent.agent

import android.content.Context
import com.example.mobileguiagent.cloud.AgentGoalInterpretationRequest
import com.example.mobileguiagent.cloud.AgentGoalInterpreter
import com.example.mobileguiagent.cloud.GeminiModel
import com.example.mobileguiagent.cloud.GeminiApiClient
import com.example.mobileguiagent.cloud.AgentMeasuredGoalSpec
import com.example.mobileguiagent.cloud.RetryingAgentGoalInterpreter
import com.example.mobileguiagent.model.AgentRunContext
import com.example.mobileguiagent.model.AgentRunContextResolver
import com.example.mobileguiagent.model.AgentRunLogStore
import com.example.mobileguiagent.model.AgentSkillLoader
import com.example.mobileguiagent.model.GoalSpecTaskContractCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal enum class AgentBootstrapMode {
    NEW,
    RESUMED,
}

internal enum class AgentBootstrapIssueCode {
    EMPTY_GOAL,
    API_KEY_MISSING,
    INITIALIZATION_FAILED,
}

internal sealed interface AgentBootstrapResult {
    data class Ready(
        val mode: AgentBootstrapMode,
        val session: AgentWorkspaceSession,
        val runLog: AgentRunLogStore,
        val runContext: AgentRunContext,
    ) : AgentBootstrapResult

    data class SetupRequired(
        val code: AgentBootstrapIssueCode,
        val message: String,
    ) : AgentBootstrapResult

    data class Failed(
        val code: AgentBootstrapIssueCode,
        val message: String,
        val cause: Throwable,
    ) : AgentBootstrapResult
}

internal class AgentBootstrapException(
    val code: AgentBootstrapIssueCode,
    val setupRequired: Boolean,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Builds one internally consistent invocation before the planner loop starts.
 *
 * Bootstrap owns configuration validation, goal-to-skill resolution, durable
 * workspace selection, run identity, session start, and the first audit event.
 * Live UI readiness remains the first authoritative observe in the runner so
 * bootstrap never creates a duplicate or already-stale device observation.
 */
internal class AgentBootstrapper(
    private val loadSkillCatalog: (Context) -> com.example.mobileguiagent.model.AgentSkillBundle =
        { context ->
            AgentRunContextResolver.loadSkillCatalog(
                context = context,
                navigationSkillId = AgentSkillLoader.GUI_APP_NAVIGATION,
            )
        },
    private val goalInterpreter: AgentGoalInterpreter =
        RetryingAgentGoalInterpreter(GeminiApiClient()),
    private val workspaceStoreFactory: (Context) -> AgentWorkspaceStore =
        FileAgentWorkspaceStore::create,
    private val runLogFactory: (Context, String) -> AgentRunLogStore =
        AgentRunLogStore::create,
) {
    suspend fun bootstrap(
        context: Context,
        apiKey: String,
        model: GeminiModel,
        goal: String,
    ): AgentBootstrapResult {
        val normalizedGoal = goal.trim()
        if (normalizedGoal.isEmpty()) {
            return AgentBootstrapResult.SetupRequired(
                code = AgentBootstrapIssueCode.EMPTY_GOAL,
                message = "실행할 목표가 비어 있습니다.",
            )
        }
        if (apiKey.isBlank()) {
            return AgentBootstrapResult.SetupRequired(
                code = AgentBootstrapIssueCode.API_KEY_MISSING,
                message = "Gemini API 키가 없습니다. 프로젝트 local.properties에 " +
                    "GEMINI_API_KEY=... 형식으로 추가하세요.",
            )
        }

        return withContext(Dispatchers.IO) {
            initialize(
                context = context.applicationContext,
                apiKey = apiKey,
                model = model,
                goal = normalizedGoal,
            )
        }
    }

    private suspend fun initialize(
        context: Context,
        apiKey: String,
        model: GeminiModel,
        goal: String,
    ): AgentBootstrapResult {
        var session: AgentWorkspaceSession? = null
        var runLog: AgentRunLogStore? = null
        var store: AgentWorkspaceStore? = null
        var createdWorkspaceId: String? = null
        var runStarted = false
        return try {
            val catalog = loadSkillCatalog(context)
            store = workspaceStoreFactory(context)
            val resumable = store.findResumable(goal)
            val interpreted: AgentMeasuredGoalSpec? = if (resumable?.goalSpec == null) {
                goalInterpreter.interpret(
                    apiKey = apiKey,
                    model = model,
                    request = AgentGoalInterpretationRequest(goal, catalog),
                )
            } else {
                null
            }
            val goalSpec = resumable?.goalSpec ?: checkNotNull(interpreted).spec
            val skills = AgentRunContextResolver.selectSkills(catalog, goalSpec)
            val skillDigests = skills.all.associate { it.id to it.digest }
            val runContext = AgentRunContext(
                goal = goal,
                skills = skills,
                goalSpec = goalSpec,
                taskContract = GoalSpecTaskContractCompiler.compile(
                    goal = goal,
                    spec = goalSpec,
                    skills = skills,
                ),
            )
            val openedWorkspace = store.openOrCreate(
                goal = goal,
                skillDigests = skillDigests,
            ) {
                AgentWorkspace.create(goal, runContext).also { created ->
                    createdWorkspaceId = created.id
                }
            }
            val workspace = if (openedWorkspace.goalSpec == null) {
                store.save(
                    openedWorkspace.attachGoalSpec(goalSpec),
                    "goal_spec_migrated",
                )
            } else {
                openedWorkspace
            }
            val mode = if (createdWorkspaceId != null) {
                AgentBootstrapMode.NEW
            } else {
                AgentBootstrapMode.RESUMED
            }
            runLog = runLogFactory(context, workspace.id)
            session = AgentWorkspaceSession(
                store = store,
                initial = workspace,
                runContext = runContext,
            )
            session.begin(runLog.runId)
            runStarted = true
            runLog.recordStart(
                goal = goal,
                model = model,
                runContext = runContext,
                workspace = session.workspace,
                bootstrapMode = mode,
                goalInterpretation = interpreted,
            )
            AgentBootstrapResult.Ready(
                mode = mode,
                session = session,
                runLog = runLog,
                runContext = runContext,
            )
        } catch (error: CancellationException) {
            cleanupPartialRun(
                session = session,
                runLog = runLog,
                store = store,
                createdWorkspaceId = createdWorkspaceId,
                runStarted = runStarted,
                error = error,
            )
            throw error
        } catch (error: Throwable) {
            cleanupPartialRun(
                session = session,
                runLog = runLog,
                store = store,
                createdWorkspaceId = createdWorkspaceId,
                runStarted = runStarted,
                error = error,
            )
            AgentBootstrapResult.Failed(
                code = AgentBootstrapIssueCode.INITIALIZATION_FAILED,
                message = "에이전트 실행 환경을 준비하지 못했습니다.",
                cause = error,
            )
        }
    }

    private suspend fun cleanupPartialRun(
        session: AgentWorkspaceSession?,
        runLog: AgentRunLogStore?,
        store: AgentWorkspaceStore?,
        createdWorkspaceId: String?,
        runStarted: Boolean,
        error: Throwable,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            fun preserveCleanupFailure(block: () -> Unit) {
                runCatching(block).exceptionOrNull()?.let(error::addSuppressed)
            }
            if (runStarted && session != null) {
                if (error is CancellationException) {
                    preserveCleanupFailure {
                        session.cancel("실행 준비 중 취소되었습니다.")
                    }
                } else {
                    preserveCleanupFailure {
                        session.fail(
                            error.message ?: error::class.java.simpleName,
                        )
                    }
                }
                preserveCleanupFailure {
                    runLog?.recordError(
                        error = error,
                        cancelled = error is CancellationException,
                    )
                }
                preserveCleanupFailure { runLog?.close() }
            } else if (runLog != null) {
                preserveCleanupFailure { runLog.close() }
                preserveCleanupFailure { runLog.file.delete() }
            }
            if (!runStarted && store != null && createdWorkspaceId != null) {
                preserveCleanupFailure { store.delete(createdWorkspaceId) }
            }
        }
    }
}
