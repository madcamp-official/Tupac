package com.example.mobileguiagent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionCycleGuardTest {
    @Test
    fun rejectsThirdVisitToSameStateActionAcrossLongerCycle() {
        val guard = AgentActionCycleGuard()
        guard.record("schedule:13:10")
        guard.record("summary:close")
        guard.record("notice:confirm")
        guard.record("schedule:13:10")
        guard.record("summary:close")
        guard.record("notice:confirm")

        assertTrue(guard.wouldRepeatStateAction("schedule:13:10"))
    }

    @Test
    fun detectsAlternatingTwoStateCycle() {
        val guard = AgentActionCycleGuard()

        guard.record("screen-a:tap:울산")
        guard.record("screen-b:tap:울산")
        guard.record("screen-a:tap:울산")

        assertTrue(guard.wouldRepeatAlternatingCycle("screen-b:tap:울산"))
    }

    @Test
    fun allowsProgressingSequence() {
        val guard = AgentActionCycleGuard()

        guard.record("screen-a:tap:울산")
        guard.record("screen-b:tap:선택 완료")
        guard.record("screen-c:tap:날짜")

        assertFalse(guard.wouldRepeatAlternatingCycle("screen-d:tap:오디세이"))
    }

    @Test
    fun resetAllowsOneRetryAfterBlockingSurfaceWasAcknowledged() {
        val guard = AgentActionCycleGuard()
        guard.record("schedule:tap:19:10")
        guard.record("schedule:tap:19:10")
        assertTrue(guard.wouldRepeatStateAction("schedule:tap:19:10"))

        guard.reset()

        assertFalse(guard.wouldRepeatStateAction("schedule:tap:19:10"))
    }

    @Test
    fun rejectsDeselectWhenRequestedTargetAlreadyHasNonzeroCompletion() {
        val correction = SelectionProgressGuard.correction(
            goal = "울산 영화관을 선택해",
            targetLabel = "울산",
            visibleLabels = sequenceOf("극장 선택", "선택 완료 (1/5)"),
        )

        assertTrue(correction?.contains("already selected") == true)
    }

    @Test
    fun allowsFirstSelectionWhenCompletionCountIsZero() {
        val correction = SelectionProgressGuard.correction(
            goal = "울산 영화관을 선택해",
            targetLabel = "울산",
            visibleLabels = sequenceOf("선택 완료 (0/5)"),
        )

        assertTrue(correction == null)
    }

}
