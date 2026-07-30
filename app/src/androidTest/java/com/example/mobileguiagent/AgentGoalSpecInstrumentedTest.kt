package com.example.mobileguiagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mobileguiagent.model.AgentActionBoundaryPolicy
import com.example.mobileguiagent.model.AgentGoalConstraint
import com.example.mobileguiagent.model.AgentGoalEntity
import com.example.mobileguiagent.model.AgentGoalPreference
import com.example.mobileguiagent.model.AgentGoalSpec
import com.example.mobileguiagent.model.AgentGoalSpecJson
import com.example.mobileguiagent.model.TaskContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentGoalSpecInstrumentedTest {
    @Test
    fun jsonRoundTripPreservesOpenDomainFieldsAndSafetyBoundary() {
        val original = AgentGoalSpec(
            objective = "문서 공유 준비",
            entities = listOf(AgentGoalEntity("document", "분기 보고서")),
            constraints = listOf(AgentGoalConstraint("recipient.role", "equals", "reviewer")),
            preferences = listOf(
                AgentGoalPreference("delivery.channel", "prefer", "email", "share_link"),
            ),
            successCriteria = listOf("공유 확인 화면이 보인다."),
            forbiddenActions = setOf("send_message"),
        )

        val decoded = AgentGoalSpecJson.decode(AgentGoalSpecJson.encode(original))

        assertEquals("reviewer", decoded.constraint("recipient.role")?.value)
        assertEquals("share_link", decoded.preferences.single().fallback)
        assertTrue("send_message" in decoded.forbiddenActions)
        assertTrue("execute_payment" in decoded.forbiddenActions)
    }

    @Test
    fun modelForbiddenActionBlocksMatchingGenericControl() {
        val contract = TaskContract(
            originalGoal = "메시지는 보내지 말고 초안만 작성",
            capabilities = emptySet(),
            requiredSelections = emptySet(),
            goalSpec = AgentGoalSpec(
                objective = "메시지 초안 작성",
                forbiddenActions = setOf("send_message"),
            ),
        )

        assertNotNull(
            AgentActionBoundaryPolicy.blockedLabelReason(contract, "Send message"),
        )
        assertNotNull(
            AgentActionBoundaryPolicy.blockedLabelReason(contract, "메시지 보내기"),
        )
    }
}
