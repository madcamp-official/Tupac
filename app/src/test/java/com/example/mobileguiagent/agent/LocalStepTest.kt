package com.example.mobileguiagent.agent

import android.graphics.Rect
import com.example.mobileguiagent.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프롬프트를 만드는 쪽과 답을 받는 쪽을 기기 없이 본다.
 *
 * 각 단언은 "예전에 이렇게 틀렸다"의 기록이다. 실기기에서 무엇 때문에 모델이
 * 어긋났는지를 여기 박아둬야, 프롬프트를 다시 손볼 때 같은 곳으로 돌아가지 않는다.
 */
class LocalStepTest {

    private fun step(line: String, why: String, done: Boolean = false) =
        FieldAssign.Step(
            action = "fill", field = "username", nodeId = "node_12",
            line = line, why = why, done = done,
        )

    private val plan = listOf(
        step("fill node_12 minsu", "끝남", done = true),
        step("fill node_15 비밀", "비밀번호 칸"),
        step("tap node_16", "제출 — 로그인"),
    )

    // 실기기에서 모델이 답 형식 안내를 그대로 이어 붙였다.
    //   계획: fill node_16 <값>
    //   모델: fill node_16 <값> / tap node_16 / done
    // 안내문의 동사가 계획 줄과 같아서(둘 다 fill) 베낄 대상이 되어버렸다.
    @Test
    fun promptDoesNotOfferAnAnswerMenuToCopy() {
        val prompt = LocalStep.promptFor(plan, plan[1], screen = null)
        assertFalse("답 형식 안내가 남아 있다", prompt.contains("형태:"))
        assertFalse("베낄 수 있는 선택지 목록이 남아 있다", prompt.contains(" / "))
    }

    // 베낄 한 줄이 "답:" 바로 앞에 있어야 한다. 사이에 다른 것이 끼면 모델이
    // 그 끼어든 것을 베낀다.
    @Test
    fun theLineToCopyIsTheLastThingBeforeTheAnswer() {
        val prompt = LocalStep.promptFor(plan, plan[1], screen = "SCREEN (app: com.kakao.talk)\nnode_15 [type] 비밀번호")
        assertTrue(prompt.endsWith("지금 할 것: fill node_15 비밀\n이 한 줄을 그대로 답하세요.\n답:"))
    }

    // 끝난 단계도 값과 함께 보여준다. 실측(공백 든 주소, 20번씩): 값 예시가
    // 0개면 0/20, 3개면 20/20이었다.
    @Test
    fun finishedStepsStayVisibleWithTheirValues() {
        val prompt = LocalStep.promptFor(plan, plan[1], screen = null)
        assertTrue("끝난 단계가 사라졌다", prompt.contains("[완료] fill node_12 minsu"))
    }

    @Test
    fun screenIsOmittedWhenNotGiven() {
        assertFalse(LocalStep.promptFor(plan, plan[1], screen = null).contains("SCREEN"))
    }

    // 모델이 줄 앞뒤에 공백을 붙이는 일이 있다. 그것까지 불일치로 볼 이유는 없다.
    @Test
    fun firstLineTrimsOnlyTheEdges() {
        assertEquals("fill node_12 서울시 중구", LocalStep.firstLine("  fill node_12 서울시 중구  \n뭐라고요?"))
    }

    // 안쪽은 손대지 않는다. 실측으로 모델이 "서울시 중구 세종대로 110"을
    // "서울 중구 세종대로 110"으로 흘렸다 — 여기서 다듬어 맞춰주면 그 값이
    // 그대로 배송지에 들어간다.
    @Test
    fun aDroppedCharacterIsStillAMismatch() {
        val planned = "fill node_12 서울시 중구 세종대로 110"
        assertFalse(planned == LocalStep.firstLine("fill node_12 서울 중구 세종대로 110"))
    }

    @Test
    fun firstLineSkipsLeadingBlankLines() {
        assertEquals("tap node_16", LocalStep.firstLine("\n\n  tap node_16\n"))
    }

    // 로그에 남기기 전에 값만 가린다. 긴 값을 먼저 지워야 짧은 값이 긴 값의
    // 조각일 때 나머지가 남지 않는다.
    @Test
    fun redactRemovesLongerSecretsFirst() {
        val masked = LocalStep.redact("fill node_15 minsu1234", listOf("minsu", "minsu1234"))
        assertEquals("fill node_15 <값 9자>", masked)
    }

    @Test
    fun redactLeavesTheShapeReadable() {
        val masked = LocalStep.redact("fill node_16 hunter2 / tap node_16", listOf("hunter2"))
        assertEquals("fill node_16 <값 7자> / tap node_16", masked)
    }

    // 화면 목록은 라벨이 있는 노드만 싣는다. 라벨 없는 노드는 모델이 고를 근거가
    // 없고 토큰만 먹는다.
    @Test
    fun screenSkipsNodesWithoutALabel() {
        val nodes = listOf(
            node("node_1", text = "카카오톡"),
            node("node_2"),
            node("node_3", hint = "비밀번호", editable = true, password = true),
        )
        val screen = LocalStep.renderScreen(nodes, "com.kakao.talk")
        assertEquals(
            """
            SCREEN (app: com.kakao.talk)
            node_1 [tap] 카카오톡
            node_3 [type,비밀번호] 비밀번호
            """.trimIndent(),
            screen,
        )
    }

    private fun node(
        id: String,
        text: String? = null,
        hint: String? = null,
        editable: Boolean = false,
        password: Boolean = false,
    ) = UiNode(
        id = id,
        text = text,
        contentDescription = null,
        hint = hint,
        className = null,
        viewId = null,
        clickable = false,
        editable = editable,
        scrollable = false,
        enabled = true,
        checked = null,
        bounds = Rect(),
        depth = 1,
        password = password,
    )
}
