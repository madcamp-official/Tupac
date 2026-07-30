package com.example.mobileguiagent.agent

import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolExecutor
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.model.AgentTraceEvent
import com.example.mobileguiagent.model.UiSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class DispatchedAgentAction(
    val callId: String,
    val call: DeviceToolCall,
    val result: DeviceToolResult,
)

/**
 * One path for every side-effecting Android tool invocation.
 *
 * The intent event is awaited before dispatch, then the correlated result event
 * is awaited afterward. The workspace session therefore commits PREPARED state
 * before an Android side effect can occur.
 */
class AgentActionDispatcher(
    private val deviceTools: DeviceToolExecutor,
) {
    suspend fun dispatch(
        step: Int,
        call: DeviceToolCall,
        snapshot: UiSnapshot,
        expectedChange: String? = null,
        targetKey: String? = null,
        automatic: Boolean = false,
        onTrace: suspend (AgentTraceEvent) -> Unit,
    ): DispatchedAgentAction {
        val callId = UUID.randomUUID().toString()
        call.arguments.put("snapshot_id", snapshot.fingerprint.hash)
        onTrace(
            AgentTraceEvent.ToolCall(
                step = step,
                call = call,
                callId = callId,
                expectedChange = expectedChange,
                targetKey = targetKey,
            ),
        )
        val result = withContext(Dispatchers.IO) {
            deviceTools.execute(call)
        }
        onTrace(
            AgentTraceEvent.ToolResult(
                step = step,
                call = call,
                result = result,
                callId = callId,
                automatic = automatic,
            ),
        )
        return DispatchedAgentAction(
            callId = callId,
            call = call,
            result = result,
        )
    }
}
