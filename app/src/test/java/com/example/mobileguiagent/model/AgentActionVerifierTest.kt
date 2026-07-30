package com.example.mobileguiagent.model

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionVerifierTest {
    @Test
    fun boundsOnlyChangeDoesNotVerifyAction() {
        val before = snapshot(
            node(
                id = "before",
                text = "다음",
                viewId = "com.example:id/next",
                clickable = true,
                bounds = Rect(20, 100, 200, 180),
            ),
        )
        val after = snapshot(
            node(
                id = "after",
                text = "다음",
                viewId = "com.example:id/next",
                clickable = true,
                bounds = Rect(40, 140, 240, 220),
            ),
        )

        val result = AgentActionVerifier.verify(before, after)

        assertFalse(result.verified)
        assertTrue(result.evidence.isEmpty())
        assertTrue(result.message.contains("layout"))
    }

    @Test
    fun anonymousLiveNumericLabelDoesNotVerifyActionOrChangeResumeSignature() {
        val before = snapshot(
            node(id = "showtime", text = "19:45", viewId = "time_movie_19452220"),
            node(id = "remaining_before", text = "1154"),
        )
        val after = snapshot(
            node(id = "showtime_refreshed", text = "19:45", viewId = "time_movie_19452220"),
            node(id = "remaining_after", text = "854"),
        )

        val result = AgentActionVerifier.verify(before, after)

        assertFalse(result.verified)
        assertTrue(result.evidence.isEmpty())
        assertEquals(
            AgentActionVerifier.semanticSignature(before),
            AgentActionVerifier.semanticSignature(after),
        )
    }

    @Test
    fun transientSnackbarAloneDoesNotVerifyAction() {
        val root = node(id = "root", text = "영화 목록")
        val before = snapshot(root)
        val after = snapshot(
            root.copy(id = "new_root"),
            node(
                id = "snackbar",
                parentId = "new_root",
                className = "com.google.android.material.snackbar.Snackbar",
                viewId = "com.example:id/snackbar_text",
            ),
            node(
                id = "snackbar_text",
                parentId = "snackbar",
                text = "선택되었습니다",
            ),
        )

        val result = AgentActionVerifier.verify(
            before = before,
            after = after,
            expectedChange = "선택되었습니다",
        )

        assertFalse(result.verified)
        assertTrue(result.evidence.isEmpty())
        assertEquals(2, result.ignoredTransientNodeCount)
    }

    @Test
    fun packageChangeVerifiesAction() {
        val before = snapshot(
            node(id = "root", text = "홈"),
            packageName = "com.example.launcher",
        )
        val after = snapshot(
            node(id = "root", text = "홈"),
            packageName = "com.example.target",
        )

        val result = AgentActionVerifier.verify(before, after)

        assertTrue(result.verified)
        assertEquals(setOf(AgentActionEvidence.PACKAGE_CHANGED), result.evidence)
    }

    @Test
    fun semanticControlChangeVerifiesActionDespiteNodeIdChurn() {
        val before = snapshot(
            node(
                id = "node_4",
                text = "좌석 선택",
                viewId = "com.example:id/continue",
                clickable = true,
            ),
        )
        val after = snapshot(
            node(
                id = "node_97",
                text = "결제하기",
                viewId = "com.example:id/continue",
                clickable = true,
            ),
        )

        val result = AgentActionVerifier.verify(before, after)

        assertTrue(result.verified)
        assertTrue(AgentActionEvidence.SEMANTIC_UI_CHANGED in result.evidence)
    }

    @Test
    fun checkedAndSelectedStateChangesVerifyAction() {
        val before = snapshot(
            node(
                id = "old_checkbox",
                text = "약관 동의",
                viewId = "com.example:id/terms",
                clickable = true,
                checked = false,
            ),
            node(
                id = "old_option",
                text = "울산",
                viewId = "com.example:id/theater",
                clickable = true,
                selected = false,
            ),
        )
        val after = snapshot(
            node(
                id = "new_checkbox",
                text = "약관 동의",
                viewId = "com.example:id/terms",
                clickable = true,
                checked = true,
            ),
            node(
                id = "new_option",
                text = "울산",
                viewId = "com.example:id/theater",
                clickable = true,
                selected = true,
            ),
        )

        val result = AgentActionVerifier.verify(before, after)

        assertTrue(result.verified)
        assertEquals(
            setOf(AgentActionEvidence.CONTROL_STATE_CHANGED),
            result.evidence,
        )
    }

    @Test
    fun newlyVisibleExpectedKeywordVerifiesTextOnlyResult() {
        val before = snapshot(node(id = "root", text = "예매 진행"))
        val after = snapshot(
            node(id = "root", text = "예매 진행"),
            node(id = "label", text = "D7 좌석 선택됨"),
        )

        val result = AgentActionVerifier.verify(
            before = before,
            after = after,
            expectedChange = "D7 좌석 선택됨 표시",
        )

        assertTrue(result.verified)
        assertTrue(AgentActionEvidence.EXPECTED_CHANGE_OBSERVED in result.evidence)
        assertTrue("d7" in result.matchedExpectedKeywords)
    }

    @Test
    fun taskStateSlotChangeProvidesExplicitEvidence() {
        val before = snapshot(
            node(
                id = "old_slot",
                text = "서울",
                viewId = "com.megabox.mop:id/prevTheaterNm",
            ),
        )
        val after = snapshot(
            node(
                id = "new_slot",
                text = "울산",
                viewId = "com.megabox.mop:id/prevTheaterNm",
            ),
        )

        val result = AgentActionVerifier.verify(
            before = before,
            after = after,
            expectedChange = null,
            stateSlotViewIds = mapOf("theater" to setOf("prevTheaterNm")),
        )

        assertTrue(result.verified)
        assertTrue(AgentActionEvidence.STATE_SLOT_CHANGED in result.evidence)
        assertEquals(setOf("theater"), result.changedStateSlots)
    }

    @Test
    fun nodeReorderingWithoutSemanticChangeDoesNotVerifyAction() {
        val first = node(id = "one", text = "영화", viewId = "movie")
        val second = node(
            id = "two",
            text = "다음",
            viewId = "next",
            clickable = true,
        )
        val before = snapshot(first, second)
        val after = snapshot(
            second.copy(id = "new_two"),
            first.copy(id = "new_one"),
        )

        val result = AgentActionVerifier.verify(before, after)

        assertFalse(result.verified)
    }

    private fun snapshot(
        vararg nodes: UiNode,
        packageName: String = "com.example.app",
    ) = UiSnapshot(
        packageName = packageName,
        nodes = nodes.toList(),
        capturedAtMillis = 1L,
    )

    private fun node(
        id: String,
        parentId: String? = null,
        text: String? = null,
        description: String? = null,
        hint: String? = null,
        className: String? = "android.widget.TextView",
        roleDescription: String? = null,
        viewId: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
        checked: Boolean? = null,
        selected: Boolean = false,
        bounds: Rect = Rect(0, 0, 100, 100),
    ) = UiNode(
        id = id,
        parentId = parentId,
        text = text,
        contentDescription = description,
        hint = hint,
        className = className,
        roleDescription = roleDescription,
        viewId = viewId,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        enabled = enabled,
        checked = checked,
        selected = selected,
        bounds = bounds,
        depth = if (parentId == null) 0 else 1,
    )
}
