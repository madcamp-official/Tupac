package com.example.mobileguiagent.model

import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolResult
import java.util.UUID

enum class AgentRunDisposition {
    SUCCEEDED,
    PAUSED,
    FAILED,
}

enum class AgentStopReason {
    GOAL_COMPLETED,
    STEP_LIMIT,
    USER_HANDOFF,
    SAFETY_POLICY,
    PLANNER_EXHAUSTED,
    TOOL_FAILURE,
    STALLED,
    TASK_CONSTRAINT,
}

data class AgentOutcome(
    val message: String,
    val status: String,
    val steps: Int,
    val disposition: AgentRunDisposition,
    val stopReason: AgentStopReason,
)

sealed interface AgentTraceEvent {
    data class PlannerDecision(
        val step: Int,
        val model: String,
        val action: String,
        val reasonCode: String,
        val target: String,
        val expectedChange: String,
        val message: String?,
        val latencyMs: Long,
        val requestBytes: Int,
        val promptTokenCount: Int?,
        val candidatesTokenCount: Int?,
        val totalTokenCount: Int?,
        val plan: List<String>,
        val progressSummary: String,
    ) : AgentTraceEvent

    data class ToolCall(
        val step: Int,
        val call: DeviceToolCall,
        val callId: String = UUID.randomUUID().toString(),
        val expectedChange: String? = null,
        val targetKey: String? = null,
    ) : AgentTraceEvent

    data class ActionVerification(
        val step: Int,
        val tool: String,
        val callId: String?,
        val targetKey: String? = null,
        val verified: Boolean,
        val evidence: List<String>,
        val message: String,
        val resumed: Boolean = false,
    ) : AgentTraceEvent

    data class RuntimeRoute(
        val step: Int,
        val runtime: String,
        val reason: String,
    ) : AgentTraceEvent

    data class RuntimeFact(
        val step: Int,
        val key: String,
        val value: String,
    ) : AgentTraceEvent

    data class LatencySample(
        val step: Int,
        val stage: String,
        val durationMs: Long,
        val expectedMs: Long? = null,
        val operation: String? = null,
        val attempt: Int? = null,
    ) : AgentTraceEvent

    data class ToolResult(
        val step: Int,
        val call: DeviceToolCall,
        val result: DeviceToolResult,
        val callId: String? = null,
        val automatic: Boolean = false,
    ) : AgentTraceEvent
}
