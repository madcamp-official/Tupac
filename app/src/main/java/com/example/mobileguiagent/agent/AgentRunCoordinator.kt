package com.example.mobileguiagent.agent

import android.content.Context
import android.util.Log
import com.example.mobileguiagent.cloud.GeminiAgentRunner
import com.example.mobileguiagent.cloud.GeminiModel
import com.example.mobileguiagent.device.DeviceToolExecutor
import com.example.mobileguiagent.model.AgentOutcome
import com.example.mobileguiagent.model.AgentTraceEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class CoordinatedAgentRun(
    val outcome: AgentOutcome,
    val workspaceId: String,
    val runId: String,
    val logPath: String,
)

/**
 * Owns invocation lifecycle while the UI repository only renders progress.
 *
 * Workspace commits happen before trace events are forwarded, matching the
 * runner/event/session service contract used by durable agent runtimes.
 */
internal class AgentRunCoordinator(
    deviceTools: DeviceToolExecutor,
    private val bootstrapper: AgentBootstrapper = AgentBootstrapper(),
    private val runner: GeminiAgentRunner = GeminiAgentRunner(deviceTools),
) {
    suspend fun execute(
        context: Context,
        apiKey: String,
        model: GeminiModel,
        goal: String,
        onProgress: (String) -> Unit,
        onTrace: (AgentTraceEvent) -> Unit,
    ): CoordinatedAgentRun {
        val appContext = context.applicationContext
        onProgress("에이전트 실행 환경 준비 중…")
        val prepared = when (
            val result = bootstrapper.bootstrap(
                context = appContext,
                apiKey = apiKey,
                model = model,
                goal = goal,
            )
        ) {
            is AgentBootstrapResult.Ready -> result
            is AgentBootstrapResult.SetupRequired ->
                throw AgentBootstrapException(
                    code = result.code,
                    setupRequired = true,
                    message = result.message,
                )

            is AgentBootstrapResult.Failed ->
                throw AgentBootstrapException(
                    code = result.code,
                    setupRequired = false,
                    message = result.message,
                    cause = result.cause,
                )
        }
        val session = prepared.session
        val runLog = prepared.runLog
        val runContext = prepared.runContext

        try {
            onProgress(
                if (prepared.mode == AgentBootstrapMode.RESUMED) {
                    "중단된 에이전트 작업을 복구하는 중…"
                } else {
                    "새 에이전트 작업을 시작하는 중…"
                },
            )
            val outcome = runner.run(
                context = appContext,
                apiKey = apiKey,
                model = model,
                goal = goal,
                runContext = runContext,
                workspaceProvider = { session.workspace },
                onProgress = onProgress,
                onTrace = { event ->
                    withContext(Dispatchers.IO) {
                        session.consume(event)
                        runLog.recordTrace(event)
                    }
                    onTrace(event)
                },
            )
            withContext(NonCancellable + Dispatchers.IO) {
                session.finish(outcome)
                runCatching { runLog.recordEnd(outcome) }
                    .onFailure { error ->
                        Log.e(
                            TAG,
                            "run_end_log_failed workspace=${session.workspace.id}",
                            error,
                        )
                    }
                runCatching { runLog.close() }
            }
            return CoordinatedAgentRun(
                outcome = outcome,
                workspaceId = session.workspace.id,
                runId = runLog.runId,
                logPath = runLog.file.absolutePath,
            )
        } catch (error: CancellationException) {
            finalizeAbnormalRun(session, runLog, error, cancelled = true)
            throw error
        } catch (error: Throwable) {
            finalizeAbnormalRun(session, runLog, error, cancelled = false)
            throw error
        }
    }

    private suspend fun finalizeAbnormalRun(
        session: AgentWorkspaceSession,
        runLog: com.example.mobileguiagent.model.AgentRunLogStore,
        error: Throwable,
        cancelled: Boolean,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            fun preserveCleanupFailure(block: () -> Unit) {
                runCatching(block).exceptionOrNull()?.let(error::addSuppressed)
            }
            if (cancelled) {
                preserveCleanupFailure {
                    session.cancel("사용자가 실행을 중단했습니다.")
                }
            } else {
                preserveCleanupFailure {
                    session.fail(error.message ?: error::class.java.simpleName)
                }
            }
            preserveCleanupFailure {
                runLog.recordError(error, cancelled = cancelled)
            }
            preserveCleanupFailure { runLog.close() }
        }
    }

    private companion object {
        const val TAG = "AgentRunCoordinator"
    }
}
