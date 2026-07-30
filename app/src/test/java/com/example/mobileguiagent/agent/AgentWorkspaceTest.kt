package com.example.mobileguiagent.agent

import android.graphics.Rect
import com.example.mobileguiagent.model.AgentRunContext
import com.example.mobileguiagent.model.AgentSelectionPolicy
import com.example.mobileguiagent.model.AgentSkillBundle
import com.example.mobileguiagent.model.GeometricSeatInterceptor
import com.example.mobileguiagent.model.TaskContract
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AgentWorkspaceTest {
    @Test
    fun beginRunClearsVolatileShowtimeExhaustionFacts() {
        val workspace = workspace(contract()).copy(
            facts = mapOf(
                "goal.movie" to AgentFact(
                    key = "goal.movie",
                    value = "오디세이",
                    source = AgentFactSource.USER,
                    confirmed = true,
                    observedAt = 1L,
                ),
                "runtime.exhausted_showtime.770" to AgentFact(
                    key = "runtime.exhausted_showtime.770",
                    value = "center_unavailable:seat:F8",
                    source = AgentFactSource.RUNTIME,
                    confirmed = true,
                    observedAt = 2L,
                ),
                "derived.earliest_showtime" to AgentFact(
                    key = "derived.earliest_showtime",
                    value = "12:50",
                    source = AgentFactSource.RUNTIME,
                    confirmed = true,
                    observedAt = 2L,
                ),
            ),
        )

        val resumed = AgentWorkspaceReducer.beginRun(workspace, "new-run", now = 3L)

        assertTrue("goal.movie" in resumed.facts)
        assertFalse("runtime.exhausted_showtime.770" in resumed.facts)
        assertFalse("derived.earliest_showtime" in resumed.facts)
    }

    @Test
    fun collectsAllShowtimesAndDerivesEarliestIndependentOfTreeOrder() {
        val contract = contract(
            showtimeViewIdPatterns = setOf("""^time_.*_(\d{4})\d{4}$"""),
        )
        val workspace = workspace(contract)
        val snapshot = UiSnapshot(
            packageName = "com.megabox.mop",
            nodes = listOf(
                node("late", "14:55", "time_movie_01_14551730"),
                node("earliest", "14:15", "time_movie_02_14151650"),
                node("later", "17:00", "time_movie_02_17001935"),
            ),
        )

        val updated = AgentWorkspaceReducer.observe(workspace, snapshot, contract, now = 10)

        assertEquals(
            listOf("14:15", "14:55", "17:00"),
            updated.candidates.getValue("showtime").map { it.attributes.getValue("time") },
        )
        assertEquals(
            AgentCandidateCollectionStatus.COMPLETE_SNAPSHOT,
            updated.candidateStatuses["showtime"],
        )
        assertEquals("14:15", updated.facts.getValue("derived.earliest_showtime").value)
    }

    @Test
    fun showtimeWorkspaceCandidatesExcludeAdjacentMovieSections() {
        val contract = contract(
            requiredEntities = mapOf("movie" to setOf("스파이더맨")),
            showtimeViewIdPatterns = setOf("""^time_.*_(\d{4})\d{4}$"""),
            selectionPolicies = setOf(AgentSelectionPolicy.FUTURE_ONLY),
        )
        val snapshot = UiSnapshot(
            packageName = "com.megabox.mop",
            nodes = listOf(
                node("root", "", "", depth = 0),
                node("schedule", "", "", parentId = "root", depth = 1),
                node("requested_section", "", "", parentId = "schedule", depth = 2),
                node(
                    "requested_movie",
                    "스파이더맨: 브랜드 뉴 데이",
                    "",
                    parentId = "requested_section",
                    depth = 3,
                ),
                node(
                    "requested_past",
                    "17:40",
                    "time_spiderman_01_17402015",
                    parentId = "requested_section",
                    depth = 3,
                ),
                node(
                    "requested_future",
                    "18:10",
                    "time_spiderman_07_18102045",
                    parentId = "requested_section",
                    depth = 3,
                ),
                node("other_section", "", "", parentId = "schedule", depth = 2),
                node("other_movie", "호프", "", parentId = "other_section", depth = 3),
                node(
                    "other_time",
                    "17:55",
                    "time_hope_03_17552041",
                    parentId = "other_section",
                    depth = 3,
                ),
            ),
        )

        val updated = AgentWorkspaceReducer.observe(
            workspace(contract),
            snapshot,
            contract,
            now = localTimeMillis(hour = 17, minute = 50),
        )

        assertEquals(
            listOf("18:10"),
            updated.candidates.getValue("showtime").map { it.attributes.getValue("time") },
        )
        assertEquals("18:10", updated.facts.getValue("derived.earliest_showtime").value)
    }

    @Test
    fun leavingShowtimeScreenPreservesCandidatesForSeatFallback() {
        val contract = contract(
            showtimeViewIdPatterns = setOf("""^time_.*_(\d{4})\d{4}$"""),
        )
        val withShowtimes = AgentWorkspaceReducer.observe(
            workspace(contract),
            UiSnapshot(
                "com.megabox.mop",
                listOf(node("showtime", "14:15", "time_movie_02_14151650")),
            ),
            contract,
            now = 10,
        )

        val leftScreen = AgentWorkspaceReducer.observe(
            withShowtimes,
            UiSnapshot(
                "com.megabox.mop",
                listOf(node("payment", "결제하기", "payment")),
            ),
            contract,
            now = 11,
        )

        assertTrue(leftScreen.candidates.containsKey("showtime"))
        assertEquals(
            AgentCandidateCollectionStatus.PARTIAL,
            leftScreen.candidateStatuses["showtime"],
        )
        assertEquals(
            "14:15",
            leftScreen.facts.getValue("derived.earliest_showtime").value,
        )
    }

    @Test
    fun exhaustedShowtimeFactAdvancesDurableEarliestCandidate() {
        val contract = contract(
            showtimeViewIdPatterns = setOf("""^time_.*_(\d{4})\d{4}$"""),
        )
        val schedule = UiSnapshot(
            "com.megabox.mop",
            listOf(
                node("first", "18:40", "time_movie_05_18402115"),
                node("next", "21:25", "time_movie_05_21252400"),
            ),
        )
        val observed = AgentWorkspaceReducer.observe(
            workspace(contract),
            schedule,
            contract,
            now = 10,
        )
        val exhausted = AgentWorkspaceReducer.recordRuntimeFact(
            observed,
            key =
                GeometricSeatInterceptor.EXHAUSTED_SHOWTIME_FACT_PREFIX +
                    (18 * 60 + 40),
            value = "center_unavailable:seat:D6,seat:C6",
            now = 11,
        )

        val resumed = AgentWorkspaceReducer.observe(
            exhausted,
            schedule,
            contract,
            now = 12,
        )

        assertEquals(
            "21:25",
            resumed.facts.getValue("derived.earliest_showtime").value,
        )
        assertTrue(
            resumed.facts.containsKey(
                GeometricSeatInterceptor.EXHAUSTED_SHOWTIME_FACT_PREFIX +
                    (18 * 60 + 40),
            ),
        )
    }

    @Test
    fun screenObservationCannotOverwriteImmutableUserFact() {
        val contract = contract(
            requiredEntities = mapOf("theater" to setOf("울산")),
            stateSlotViewIds = mapOf("theater" to setOf("prevTheaterNm")),
        )
        val workspace = workspace(contract)
        val snapshot = UiSnapshot(
            packageName = "com.megabox.mop",
            nodes = listOf(node("theater", "서울", "prevTheaterNm")),
        )

        val updated = AgentWorkspaceReducer.observe(workspace, snapshot, contract, now = 10)

        assertEquals("울산", updated.facts.getValue("goal.theater").value)
        assertEquals("서울", updated.facts.getValue("screen.theater").value)
    }

    @Test
    fun plannerRevisesAuditablePlanWithoutConfirmingModelProgress() {
        val workspace = workspace(contract())
        val updated = AgentWorkspaceReducer.recordDecision(
            workspace,
            proposedPlan =
                listOf("Select exact theater", "Collect showtimes", "Verify completion"),
            progressSummary = "The theater list is visible",
            now = 20,
        )

        assertEquals(3, updated.plan.size)
        assertEquals(AgentPlanStepStatus.ACTIVE, updated.plan.first().status)
        assertFalse(updated.facts.getValue("model.progress").confirmed)
    }

    @Test
    fun plannerGeneratedPlanIsSanitizedBeforePersistence() {
        val updated = AgentWorkspaceReducer.recordDecision(
            workspace(contract()),
            proposedPlan = listOf("Contact me@example.com", "Call 010-1234-5678"),
            progressSummary = "me@example.com is visible",
            now = 20,
        )

        assertTrue(updated.plan.all { AgentDataSanitizer.REDACTED in it.description })
        assertEquals(
            AgentDataSanitizer.REDACTED + " is visible",
            updated.facts.getValue("model.progress").value,
        )
    }

    @Test
    fun resumeStateNeverStoresSnapshotNodeIdAsSelection() {
        val original = AgentWorkspaceReducer.observe(
            workspace(contract()),
            UiSnapshot("example.app", listOf(node("ephemeral-node", "화면", "screen_title"))),
            contract(),
            now = 30,
        )
        assertEquals("example.app", original.resumePoint?.packageName)
        assertTrue(original.selections.isEmpty())
    }

    @Test
    fun persistenceSanitizerRedactsTypedTextAndCommonPii() {
        assertFalse(AgentDataSanitizer.text("010-1234-5678")!!.contains("1234"))
        assertEquals(AgentDataSanitizer.REDACTED, AgentDataSanitizer.text("me@example.com"))
    }

    @Test
    fun successfulSideEffectRemainsPendingUntilSemanticVerification() {
        val observed = AgentWorkspaceReducer.observe(
            workspace(contract()),
            UiSnapshot("example.app", listOf(node("next", "다음", "next"))),
            contract(),
            now = 10,
        )
        val staged = AgentWorkspaceReducer.stageToolIntent(
            workspace = observed,
            call = DeviceToolCall("tap_node"),
            step = 1,
            callId = "call-1",
            expectedChange = "좌석 화면 표시",
            targetKey = "next",
            now = 11,
        )
        val dispatched = AgentWorkspaceReducer.recordToolResult(
            workspace = staged,
            call = DeviceToolCall("tap_node"),
            result = DeviceToolResult.Action("tap_node", true, "눌렀습니다."),
            step = 1,
            callId = "call-1",
            now = 12,
        )

        assertEquals("tap_node", dispatched.pendingAction?.tool)

        val verified = AgentWorkspaceReducer.recordVerification(
            workspace = dispatched,
            callId = "call-1",
            verified = true,
            evidence = listOf("SEMANTIC_UI_CHANGED"),
            message = "verified",
            step = 2,
            now = 13,
        )
        assertEquals(null, verified.pendingAction)
        assertTrue(verified.facts.getValue("tool.last_verification").confirmed)
    }

    @Test
    fun verifiedSeatPersistsWhenItDisappearsFromRefreshedAvailableCandidates() {
        val withAvailableSeat = workspace(contract()).copy(
            candidates = mapOf(
                "seat" to listOf(
                    AgentCandidate(
                        kind = "seat",
                        stableKey = "seat:G13",
                        label = "G13 스탠다드 판매가능 일반",
                        attributes = mapOf("geometric_center" to "true"),
                        observedAt = 10,
                        screenFingerprint = "before",
                    ),
                ),
            ),
        )
        val staged = AgentWorkspaceReducer.stageToolIntent(
            workspace = withAvailableSeat,
            call = DeviceToolCall("tap_node"),
            step = 1,
            callId = "seat-call",
            expectedChange = "Select exact geometric-center seat G13",
            targetKey = "seat:G13",
            now = 11,
        )
        val refreshedWithoutSelectedSeat = staged.copy(
            candidates = mapOf(
                "seat" to listOf(
                    AgentCandidate(
                        kind = "seat",
                        stableKey = "seat:E13",
                        label = "E13 스탠다드 판매가능 일반",
                        attributes = emptyMap(),
                        observedAt = 12,
                        screenFingerprint = "after",
                    ),
                ),
            ),
            // An asynchronous WebView click may report false and clear the
            // staged action before the next snapshot proves the selection.
            pendingAction = null,
        )

        val verified = AgentWorkspaceReducer.recordVerification(
            workspace = refreshedWithoutSelectedSeat,
            callId = "seat-call",
            targetKey = "seat:G13",
            verified = true,
            evidence = listOf("SEAT_SELECTION_CONFIRMED"),
            message = "price changed from 0 to 10000",
            step = 2,
            now = 13,
        )

        assertEquals("seat:G13", verified.selections.getValue("seat").candidateKey)
        assertTrue(verified.selections.getValue("seat").validated)
    }

    @Test
    fun verifiedExplicitDatePersistsForResume() {
        val staged = AgentWorkspaceReducer.stageToolIntent(
            workspace = workspace(contract()),
            call = DeviceToolCall("tap_node"),
            step = 1,
            callId = "date-call",
            expectedChange = "Select exact date 20260802",
            targetKey = "date:20260802",
            now = 11,
        )

        val verified = AgentWorkspaceReducer.recordVerification(
            workspace = staged,
            callId = "date-call",
            verified = true,
            evidence = listOf("SEMANTIC_UI_CHANGED"),
            message = "schedule changed",
            step = 2,
            now = 13,
        )

        assertEquals(
            "date:20260802",
            verified.selections.getValue("date").candidateKey,
        )
        assertTrue(verified.selections.getValue("date").validated)
    }

    @Test
    fun pausingAnInDoubtActionPreservesItForResumeReconciliation() {
        val observed = AgentWorkspaceReducer.observe(
            workspace(contract()),
            UiSnapshot("example.app", listOf(node("next", "다음", "next"))),
            contract(),
            now = 10,
        )
        val staged = AgentWorkspaceReducer.stageToolIntent(
            workspace = observed,
            call = DeviceToolCall("tap_node"),
            step = 1,
            callId = "call-in-doubt",
            expectedChange = "다음 화면",
            targetKey = "next",
            now = 11,
        )

        val paused = AgentWorkspaceReducer.finish(
            workspace = staged,
            status = AgentWorkspaceStatus.PAUSED,
            message = "cancelled",
            now = 12,
        )

        assertEquals("call-in-doubt", paused.pendingAction?.callId)
        assertEquals(AgentWorkspaceStatus.PAUSED, paused.status)
    }

    @Test
    fun durableGoalIsSanitizedBeforeItIsStored() {
        val rawGoal = "me@example.com 계정으로 010-1234-5678에 연락"
        val created = AgentWorkspace.create(
            goal = rawGoal,
            runContext = AgentRunContext(
                goal = rawGoal,
                skills = AgentSkillBundle.EMPTY,
                taskContract = contract(),
            ),
            now = 1,
        )

        assertFalse(created.goal.contains("example.com"))
        assertFalse(created.goal.contains("1234"))
        assertNotEquals(AgentWorkspace.normalizeGoal(created.goal), created.goalKey)
        assertEquals(AgentWorkspace.goalIdentity(rawGoal), created.goalKey)
        assertTrue(created.goalKey.startsWith("sha256:"))
    }

    @Test
    fun redactedGoalsStillHaveDistinctResumeIdentities() {
        val first = "me@example.com 계정으로 로그인"
        val second = "other@example.com 계정으로 로그인"

        assertEquals(
            AgentDataSanitizer.text(first),
            AgentDataSanitizer.text(second),
        )
        assertNotEquals(
            AgentWorkspace.goalIdentity(first),
            AgentWorkspace.goalIdentity(second),
        )
    }

    private fun workspace(contract: TaskContract): AgentWorkspace = AgentWorkspace.create(
        goal = contract.originalGoal,
        runContext = AgentRunContext(
            goal = contract.originalGoal,
            skills = AgentSkillBundle.EMPTY,
            taskContract = contract,
        ),
        now = 1,
    )

    private fun contract(
        requiredEntities: Map<String, Set<String>> = emptyMap(),
        stateSlotViewIds: Map<String, Set<String>> = emptyMap(),
        showtimeViewIdPatterns: Set<String> = emptySet(),
        selectionPolicies: Set<AgentSelectionPolicy> = emptySet(),
    ): TaskContract = TaskContract(
        originalGoal = "메가박스 울산에서 영화 예매",
        capabilities = emptySet(),
        requiredSelections = emptySet(),
        requiredEntities = requiredEntities,
        stateSlotViewIds = stateSlotViewIds,
        showtimeViewIdPatterns = showtimeViewIdPatterns,
        selectionPolicies = selectionPolicies,
    )

    private fun localTimeMillis(hour: Int, minute: Int): Long =
        Calendar.getInstance().run {
            clear()
            set(2026, Calendar.JULY, 29, hour, minute)
            timeInMillis
        }

    private fun node(
        id: String,
        text: String,
        viewId: String,
        parentId: String? = null,
        depth: Int = 1,
    ): UiNode = UiNode(
        id = id,
        parentId = parentId,
        text = text,
        contentDescription = null,
        className = "android.widget.TextView",
        viewId = viewId,
        clickable = true,
        editable = false,
        scrollable = false,
        enabled = true,
        checked = null,
        bounds = Rect(0, 0, 100, 100),
        depth = depth,
    )
}
