package com.example.mobileguiagent.agent

import com.example.mobileguiagent.model.LocalAgentOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CallLocalAgentToolTest {
    @Test
    fun trimsGoalBeforeDelegating() = runBlocking {
        var received: LocalAgentRequest? = null
        val tool = CallLocalAgentTool { request ->
            received = request
            LocalAgentOutcome("done", "local", 2)
        }

        val result = tool.execute(
            LocalAgentRequest(
                goal = "  와이파이 설정 열기  ",
                controllerVisible = false,
            ),
        )

        assertEquals("와이파이 설정 열기", received?.goal)
        assertEquals(2, result.steps)
    }

    @Test
    fun rejectsBlankGoal() {
        val tool = CallLocalAgentTool {
            LocalAgentOutcome("unused", "unused", 0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                tool.execute(LocalAgentRequest("   ", controllerVisible = false))
            }
        }
    }
}
