package com.example.mobileguiagent.cloud

import com.example.mobileguiagent.model.UiSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class RetryingAgentPlannerTest {
    @Test
    fun retriesOnlyThePlannerTurnForTransientFailures() = runBlocking {
        var attempts = 0
        val planner = RetryingAgentPlanner(
            delegate = AgentPlanner { _, _, _ ->
                attempts += 1
                if (attempts < 3) throw IOException("temporary")
                decision()
            },
            maxAttempts = 3,
            initialDelayMs = 0,
        )

        val result = planner.decide("key", GeminiModel.FLASH_LITE_3_1, request())

        assertEquals(3, attempts)
        assertEquals("wait", result.action.action)
    }

    @Test
    fun doesNotRetryNonTransientClientFailure() {
        var attempts = 0
        val planner = RetryingAgentPlanner(
            delegate = AgentPlanner { _, _, _ ->
                attempts += 1
                throw GeminiPlannerException(400, "bad request")
            },
            maxAttempts = 3,
            initialDelayMs = 0,
        )

        assertThrows(GeminiPlannerException::class.java) {
            runBlocking {
                planner.decide("key", GeminiModel.FLASH_LITE_3_1, request())
            }
        }
        assertEquals(1, attempts)
    }

    private fun request() = GeminiPlannerRequest(
        goal = "wait",
        step = 1,
        maxSteps = 1,
        screenWidth = 100,
        screenHeight = 100,
        observation = UiSnapshot("example.app", emptyList()),
        recentActions = emptyList(),
    )

    private fun decision() = GeminiMeasuredDecision(
        action = GeminiPlannerAction(action = "wait"),
        requestBytes = 1,
        promptTokenCount = 1,
        candidatesTokenCount = 1,
        totalTokenCount = 2,
    )
}
