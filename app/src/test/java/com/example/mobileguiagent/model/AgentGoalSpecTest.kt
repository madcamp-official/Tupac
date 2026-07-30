package com.example.mobileguiagent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGoalSpecTest {
    @Test
    fun unfamiliarShoppingGoalRemainsGenericWithoutAnAppSkill() {
        val spec = AgentGoalSpec(
            objective = "파란색 후드티 두 개를 장바구니에 담기",
            entities = listOf(
                AgentGoalEntity("product", "후드티"),
                AgentGoalEntity("variant.color", "파란색"),
            ),
            constraints = listOf(
                AgentGoalConstraint("quantity", "equals", "2"),
                AgentGoalConstraint("completion", "equals", "cart_updated"),
            ),
            preferences = listOf(
                AgentGoalPreference("price", "minimize", "total"),
            ),
            successCriteria = listOf("장바구니에 정확한 옵션과 수량이 표시된다."),
        )

        val contract = GoalSpecTaskContractCompiler.compile(
            goal = spec.objective,
            spec = spec,
            skills = AgentSkillBundle.EMPTY,
        )

        assertEquals(setOf("후드티"), contract.requiredEntities["product"])
        assertEquals(setOf("파란색"), contract.requiredEntities["variant.color"])
        assertEquals("2", contract.constraintValue("quantity"))
        assertNull(contract.requiredDate)
        assertTrue(contract.preferredViewIds.isEmpty())
        assertFalse(contract.allows(AgentCapability.USE_STORED_CREDENTIALS))
    }

    @Test(expected = IllegalArgumentException::class)
    fun validatorRejectsExecutableLookingKeysInsteadOfTreatingThemAsCode() {
        AgentGoalSpecValidator.validate(
            AgentGoalSpec(
                objective = "unsafe",
                constraints = listOf(
                    AgentGoalConstraint("quantity;delete", "equals", "1"),
                ),
            ),
        )
    }
}
