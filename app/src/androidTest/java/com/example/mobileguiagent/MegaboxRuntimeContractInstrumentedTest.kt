package com.example.mobileguiagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mobileguiagent.model.AgentSkill
import com.example.mobileguiagent.model.AgentSkillBundle
import com.example.mobileguiagent.model.AgentSkillLoader
import com.example.mobileguiagent.model.AgentSelectionPolicy
import com.example.mobileguiagent.model.AgentGoalConstraint
import com.example.mobileguiagent.model.AgentGoalEntity
import com.example.mobileguiagent.model.AgentGoalPreference
import com.example.mobileguiagent.model.AgentGoalSpec
import com.example.mobileguiagent.model.GoalSpecTaskContractCompiler
import com.example.mobileguiagent.model.TaskDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MegaboxRuntimeContractInstrumentedTest {
    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun compilesMovieAndTheaterFromModelGoalSpec() {
        val contract = resolve(
            "메가박스에서 오늘 스파이더맨을 울산의 정확한 극장에서 " +
                "현재 시각 이후 가장 빠른 회차로 예매해",
            AgentGoalSpec(
                objective = "스파이더맨 예매",
                entities = listOf(
                    AgentGoalEntity("movie", "스파이더맨"),
                    AgentGoalEntity("theater", "울산"),
                ),
                constraints = listOf(
                    AgentGoalConstraint("time", "after", "now"),
                ),
                preferences = listOf(
                    AgentGoalPreference("time", "minimize", "start_time"),
                ),
            ),
        )

        assertEquals(setOf("스파이더맨"), contract.requiredEntities["movie"])
        assertEquals(setOf("울산"), contract.requiredEntities["theater"])
        assertEquals(setOf("울산"), contract.requiredSelections)
        assertTrue(AgentSelectionPolicy.EARLIEST_AVAILABLE in contract.selectionPolicies)
        assertTrue(AgentSelectionPolicy.FUTURE_ONLY in contract.selectionPolicies)
    }

    @Test
    fun preservesModelEntitiesRegardlessOfNaturalLanguageOrder() {
        val contract = resolve(
            "메가박스 양산에서 오디세이를 예매해",
            AgentGoalSpec(
                objective = "오디세이 예매",
                entities = listOf(
                    AgentGoalEntity("movie", "오디세이"),
                    AgentGoalEntity("theater", "양산"),
                ),
            ),
        )

        assertEquals(setOf("오디세이"), contract.requiredEntities["movie"])
        assertEquals(setOf("양산"), contract.requiredEntities["theater"])
        assertEquals(setOf("양산"), contract.requiredSelections)
    }

    @Test
    fun keepsPunctuationInModelAuthoredEntityValues() {
        val contract = resolve(
            "메가박스에서 오늘 스파이더맨: 브랜드 뉴 데이를 울산 극장으로 예매해.",
            AgentGoalSpec(
                objective = "영화 예매",
                entities = listOf(
                    AgentGoalEntity("movie", "스파이더맨: 브랜드 뉴 데이"),
                    AgentGoalEntity("theater", "울산"),
                ),
            ),
        )

        assertEquals(setOf("스파이더맨: 브랜드 뉴 데이"), contract.requiredEntities["movie"])
        assertEquals(setOf("울산"), contract.requiredEntities["theater"])
        assertEquals(setOf("울산"), contract.requiredSelections)
    }

    @Test
    fun compilesExplicitDateAndUsesDaejeonSkillNavigationHint() {
        val contract = resolve(
            "메가박스에서 8월 2일 오디세이를 대전 극장으로 예매해.",
            AgentGoalSpec(
                objective = "오디세이 예매",
                entities = listOf(
                    AgentGoalEntity("movie", "오디세이"),
                    AgentGoalEntity("theater", "대전"),
                ),
                constraints = listOf(
                    AgentGoalConstraint("date", "equals", "2026-08-02"),
                ),
            ),
        )

        assertEquals(TaskDate(2026, 8, 2), contract.requiredDate)
        assertEquals(setOf("대전"), contract.requiredEntities["theater"])
        assertEquals(
            listOf("지역별", "대전/충청/세종"),
            contract.entityNavigationHints["theater"]?.get("대전"),
        )
        assertEquals(
            setOf("""^playDate_(\d{8})$"""),
            contract.dateViewIdPatterns,
        )
    }

    @Test
    fun loadsAudienceAndSeatRuntimePoliciesFromSkillAsset() {
        val contract = resolve(
            "메가박스에서 오늘 스파이더맨을 울산의 정확한 극장에서 예매해. " +
                "성인 1명, 중앙 좌석이 없으면 다음 회차로 가.",
            AgentGoalSpec(
                objective = "영화 좌석 선택",
                entities = listOf(
                    AgentGoalEntity("movie", "스파이더맨"),
                    AgentGoalEntity("theater", "울산"),
                ),
                constraints = listOf(
                    AgentGoalConstraint("audience.adult", "equals", "1"),
                ),
                preferences = listOf(
                    AgentGoalPreference(
                        "seat.position",
                        "prefer",
                        "geometric_center",
                        fallback = "next_available_time",
                    ),
                ),
            ),
        )

        assertTrue(AgentSelectionPolicy.GEOMETRIC_CENTER in contract.selectionPolicies)
        assertTrue(AgentSelectionPolicy.NEXT_CANDIDATE_FALLBACK in contract.selectionPolicies)
        assertTrue(contract.seatCandidatePatterns.isNotEmpty())
        assertEquals(setOf("TKA"), contract.stateSlotViewIds["adult_count"])
        assertEquals(
            setOf("nextSelBtn"),
            contract.stateSlotViewIds["seat_selection_continue"],
        )
        assertEquals(setOf("ticketPriceInfo"), contract.stateSlotViewIds["seat_price"])
        assertEquals(
            listOf("지역별", "부산/대구/경상"),
            contract.entityNavigationHints["theater"]?.get("울산"),
        )
        assertEquals(
            setOf("choiceBtn"),
            contract.stateSlotViewIds["entity_selection_complete"],
        )
    }

    private fun resolve(goal: String, spec: AgentGoalSpec) =
        AgentSkillLoader.loadRuntimePolicy(context, SKILL_ID).let { policy ->
            GoalSpecTaskContractCompiler.compile(
                goal = goal,
                spec = spec,
                skills = AgentSkillBundle(
                    taskSkills = listOf(
                        AgentSkill(
                            id = SKILL_ID,
                            instructions = AgentSkillLoader.load(context, SKILL_ID),
                            runtimePolicy = policy,
                        ),
                    ),
                ),
            )
        }

    private companion object {
        const val SKILL_ID = "book-megabox-movie"
    }
}
