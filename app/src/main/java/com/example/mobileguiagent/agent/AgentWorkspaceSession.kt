package com.example.mobileguiagent.agent

import com.example.mobileguiagent.model.AgentOutcome
import com.example.mobileguiagent.model.AgentRunDisposition
import com.example.mobileguiagent.model.AgentRunContext
import com.example.mobileguiagent.model.AgentTraceEvent

/**
 * Invocation-scoped facade over durable workspace state.
 *
 * The runner emits events; this session reduces and checkpoints them before
 * the next planner turn. This mirrors an event-loop agent runtime without
 * coupling persistence to Gemini or Android tool implementations.
 */
class AgentWorkspaceSession(
    private val store: AgentWorkspaceStore,
    initial: AgentWorkspace,
    private val runContext: AgentRunContext,
) {
    @Volatile
    private var current: AgentWorkspace = initial

    val workspace: AgentWorkspace
        get() = current

    fun begin(runId: String): AgentWorkspace =
        commit(AgentWorkspaceReducer.beginRun(current, runId), "run_started")

    fun consume(event: AgentTraceEvent): AgentWorkspace {
        val next = when (event) {
            is AgentTraceEvent.PlannerDecision ->
                AgentWorkspaceReducer.recordDecision(
                    current,
                    proposedPlan = event.plan,
                    progressSummary = event.progressSummary,
                )

            is AgentTraceEvent.ToolResult -> when {
                event.result is com.example.mobileguiagent.device.DeviceToolResult.UiObservation ->
                    AgentWorkspaceReducer.observe(
                        current,
                        AgentDataSanitizer.observation(event.result.snapshot),
                        runContext.taskContract,
                    )

                event.call.name == "capture_screen" -> current
                else -> AgentWorkspaceReducer.recordToolResult(
                    current,
                    event.call,
                    event.result,
                    event.step,
                    event.callId,
                )
            }

            is AgentTraceEvent.ToolCall ->
                AgentWorkspaceReducer.stageToolIntent(
                    current,
                    event.call,
                    event.step,
                    event.callId,
                    event.expectedChange,
                    event.targetKey,
                )

            is AgentTraceEvent.ActionVerification ->
                AgentWorkspaceReducer.recordVerification(
                    workspace = current,
                    callId = event.callId,
                    targetKey = event.targetKey,
                    verified = event.verified,
                    evidence = event.evidence,
                    message = event.message,
                    step = event.step,
                )

            is AgentTraceEvent.RuntimeRoute -> current

            is AgentTraceEvent.LatencySample -> current

            is AgentTraceEvent.RuntimeFact ->
                AgentWorkspaceReducer.recordRuntimeFact(
                    workspace = current,
                    key = event.key,
                    value = event.value,
                )
        }
        return if (next === current) current else commit(next, event.eventName())
    }

    fun finish(outcome: AgentOutcome): AgentWorkspace {
        val status = when (outcome.disposition) {
            AgentRunDisposition.SUCCEEDED -> AgentWorkspaceStatus.COMPLETED
            AgentRunDisposition.PAUSED -> AgentWorkspaceStatus.PAUSED
            AgentRunDisposition.FAILED -> AgentWorkspaceStatus.FAILED
        }
        return commit(
            AgentWorkspaceReducer.finish(current, status, outcome.message),
            "run_finished",
        )
    }

    fun cancel(message: String): AgentWorkspace =
        commit(
            AgentWorkspaceReducer.finish(
                current,
                AgentWorkspaceStatus.PAUSED,
                message,
            ),
            "run_cancelled",
        )

    fun fail(message: String): AgentWorkspace =
        commit(
            AgentWorkspaceReducer.finish(
                current,
                if (current.pendingAction == null) {
                    AgentWorkspaceStatus.FAILED
                } else {
                    AgentWorkspaceStatus.PAUSED
                },
                message,
            ),
            "run_failed",
        )

    private fun commit(workspace: AgentWorkspace, event: String): AgentWorkspace {
        current = store.save(workspace, event)
        return current
    }

    private fun AgentTraceEvent.eventName(): String = when (this) {
        is AgentTraceEvent.PlannerDecision -> "planner_decision"
        is AgentTraceEvent.ActionVerification -> "action_verification"
        is AgentTraceEvent.RuntimeRoute -> "runtime_route"
        is AgentTraceEvent.RuntimeFact -> "runtime_fact"
        is AgentTraceEvent.LatencySample -> "latency_sample"
        is AgentTraceEvent.ToolCall -> "tool_call"
        is AgentTraceEvent.ToolResult -> "tool_result"
    }
}
