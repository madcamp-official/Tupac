package com.example.mobileguiagent.agent

import com.example.mobileguiagent.model.LocalAgentOutcome

/**
 * Request passed from a higher-level orchestrator to the on-device agent.
 *
 * This is intentionally separate from DeviceToolCall. A Device Tool performs
 * one deterministic Android action; this tool delegates a complete goal to a
 * bounded local observe-decide-act loop.
 */
data class LocalAgentRequest(
    val goal: String,
    val controllerVisible: Boolean,
)

/**
 * Narrow boundary implemented by the on-device VLM runtime.
 *
 * Cloud orchestration can depend on this interface without importing model
 * loading, llama.cpp, prompt parsing, or UI-state repository details.
 */
fun interface LocalAgentGateway {
    suspend fun run(request: LocalAgentRequest): LocalAgentOutcome
}

/**
 * Orchestration-level tool for invoking the local GUI agent when offline
 * execution or on-device processing is preferred.
 *
 * It is not registered in DeviceToolRegistry because it is an agent call, not
 * an Android framework primitive. MCP may expose it later through a dedicated
 * adapter without changing the local implementation.
 */
class CallLocalAgentTool(
    private val gateway: LocalAgentGateway,
) {
    suspend fun execute(request: LocalAgentRequest): LocalAgentOutcome {
        require(request.goal.isNotBlank()) {
            "로컬 에이전트에 전달할 목표가 비어 있습니다."
        }
        return gateway.run(request.copy(goal = request.goal.trim()))
    }
}
