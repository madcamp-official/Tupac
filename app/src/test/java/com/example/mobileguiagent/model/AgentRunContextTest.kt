package com.example.mobileguiagent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunContextTest {
    private val megaboxBookingPolicy = SkillRuntimePolicy(
        activationEntities = mapOf(
            "app" to setOf("메가박스", "megabox"),
        ),
    )

    @Test
    fun semanticAppEntitySelectsMegaboxSkill() {
        assertTrue(
            AgentRunContextResolver.run {
                megaboxBookingPolicy.matches(
                    AgentGoalSpec(
                        objective = "영화 예매",
                        entities = listOf(AgentGoalEntity("app", "메가박스")),
                    ),
                )
            },
        )
        assertTrue(
            AgentRunContextResolver.run {
                megaboxBookingPolicy.matches(
                    AgentGoalSpec(
                        objective = "Book a movie",
                        entities = listOf(AgentGoalEntity("app", "MEGABOX")),
                    ),
                )
            },
        )
    }

    @Test
    fun unrelatedGoalDoesNotLoadMegaboxSkill() {
        assertFalse(
            AgentRunContextResolver.run {
                megaboxBookingPolicy.matches(
                    AgentGoalSpec(
                        objective = "설정 열기",
                        entities = listOf(AgentGoalEntity("app", "설정")),
                    ),
                )
            },
        )
    }

    @Test
    fun megaboxAccountGoalDoesNotLoadBookingWorkflow() {
        assertFalse(
            AgentRunContextResolver.run {
                megaboxBookingPolicy.matches(
                    AgentGoalSpec(
                        objective = "로그인 상태 확인",
                        entities = listOf(AgentGoalEntity("app", "다른 앱")),
                    ),
                )
            },
        )
    }

    @Test
    fun skillPromptKeepsTaskAndNavigationInstructionsDistinct() {
        val section = AgentSkillBundle(
            taskSkills = listOf(AgentSkill("book-megabox-movie", "TASK_RULE")),
            navigationSkill = AgentSkill("gui-app-navigation", "NAV_RULE"),
        ).promptSection()

        assertTrue(section.contains("""<skill id="book-megabox-movie">"""))
        assertTrue(section.contains("TASK_RULE"))
        assertTrue(section.contains("""<skill id="gui-app-navigation">"""))
        assertTrue(section.contains("NAV_RULE"))
        assertFalse(section.contains("null"))
    }

    @Test
    fun taskContractCompilesOptimizationPoliciesFromModelGoalSpec() {
        val spec = AgentGoalSpec(
            objective = "가장 빠른 회차 중앙좌석, 없으면 다음 시간대로",
            preferences = listOf(
                AgentGoalPreference("time", "minimize", "start_time"),
                AgentGoalPreference(
                    "seat.position",
                    "prefer",
                    "geometric_center",
                    fallback = "next_available_time",
                ),
            ),
        )
        val contract = GoalSpecTaskContractCompiler.compile(
            goal = "가장 빠른 회차 중앙좌석, 없으면 다음 시간대로",
            spec = spec,
            skills = AgentSkillBundle.EMPTY,
        )

        assertTrue(AgentSelectionPolicy.EARLIEST_AVAILABLE in contract.selectionPolicies)
        assertTrue(AgentSelectionPolicy.GEOMETRIC_CENTER in contract.selectionPolicies)
        assertTrue(AgentSelectionPolicy.NEXT_CANDIDATE_FALLBACK in contract.selectionPolicies)
    }
}
