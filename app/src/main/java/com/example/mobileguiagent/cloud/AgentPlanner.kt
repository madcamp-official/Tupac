package com.example.mobileguiagent.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Model-independent boundary owned by the agent runtime.
 *
 * A planner proposes one structured next action. It never executes Android
 * tools or mutates durable state; the runner validates and dispatches the
 * proposal after this method returns.
 */
fun interface AgentPlanner {
    suspend fun decide(
        apiKey: String,
        model: GeminiModel,
        request: GeminiPlannerRequest,
    ): GeminiMeasuredDecision
}

class GeminiPlannerException(
    val httpStatus: Int,
    message: String,
) : IllegalStateException(message) {
    val retryable: Boolean
        get() = httpStatus == 429 || httpStatus in 500..599
}

/**
 * Bounded transient retry around a stateless planning turn. Android actions
 * are outside this boundary and are never retried here.
 */
class RetryingAgentPlanner(
    private val delegate: AgentPlanner,
    private val maxAttempts: Int = 3,
    private val initialDelayMs: Long = 500L,
) : AgentPlanner {
    init {
        require(maxAttempts >= 1)
        require(initialDelayMs >= 0)
    }

    override suspend fun decide(
        apiKey: String,
        model: GeminiModel,
        request: GeminiPlannerRequest,
    ): GeminiMeasuredDecision {
        var lastFailure: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return delegate.decide(apiKey, model, request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!failure.isTransientPlannerFailure() || attempt == maxAttempts - 1) {
                    throw failure
                }
                lastFailure = failure
                delay(initialDelayMs * (1L shl attempt).coerceAtMost(MAX_BACKOFF_MULTIPLIER))
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun Throwable.isTransientPlannerFailure(): Boolean =
        this is IOException || (this is GeminiPlannerException && retryable)

    private companion object {
        const val MAX_BACKOFF_MULTIPLIER = 8L
    }
}
