package com.example.mobileguiagent.cloud

import com.example.mobileguiagent.model.AgentGoalSpec
import com.example.mobileguiagent.model.AgentSkillBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

data class AgentGoalInterpretationRequest(
    val goal: String,
    val skills: AgentSkillBundle = AgentSkillBundle.EMPTY,
)

data class AgentMeasuredGoalSpec(
    val spec: AgentGoalSpec,
    val latencyMs: Long,
    val requestBytes: Int,
    val promptTokenCount: Int?,
    val candidatesTokenCount: Int?,
    val totalTokenCount: Int?,
)

fun interface AgentGoalInterpreter {
    suspend fun interpret(
        apiKey: String,
        model: GeminiModel,
        request: AgentGoalInterpretationRequest,
    ): AgentMeasuredGoalSpec
}

class RetryingAgentGoalInterpreter(
    private val delegate: AgentGoalInterpreter,
    private val maxAttempts: Int = 3,
    private val initialDelayMs: Long = 500L,
) : AgentGoalInterpreter {
    override suspend fun interpret(
        apiKey: String,
        model: GeminiModel,
        request: AgentGoalInterpretationRequest,
    ): AgentMeasuredGoalSpec {
        var lastFailure: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return delegate.interpret(apiKey, model, request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val retryable = failure is IOException ||
                    (failure is GeminiPlannerException && failure.retryable)
                if (!retryable || attempt == maxAttempts - 1) throw failure
                lastFailure = failure
                delay(initialDelayMs * (1L shl attempt).coerceAtMost(8L))
            }
        }
        throw checkNotNull(lastFailure)
    }
}
