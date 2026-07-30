package com.example.mobileguiagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 자리가 조금 흔들린 것과 진짜 옮겨간 것을 가르는 기준을 잰다.
 *
 * 왜 이 검사가 있는가: clickNode는 관찰한 항목이 지금도 그 자리에 있는지 보고
 * 아니면 거부한다(SCREEN_CHANGED). 예전에는 bounds 완전 일치를 요구해서, 1px만
 * 달라도 멀쩡한 클릭이 거부됐다. 실측: 인스타그램 로그인 직후 "확인" 버튼
 * 클릭이 한 번 거부됐고 곧바로 다시 부르니 그대로 됐다.
 *
 * 여유를 주면 반대 위험이 생긴다 — 한 줄 스크롤된 화면에서 엉뚱한 항목을 같은
 * 것으로 보는 것이다. 그래서 양쪽을 다 박아둔다. 통과해야 하는 흔들림과
 * 거부해야 하는 이동이 각각 어디까지인지가 이 파일의 내용이다.
 *
 * Rect를 쓰지 않는다. 유닛 테스트의 android.jar는 껍데기라 Rect의 생성자가
 * 아무것도 하지 않는다(unitTests.isReturnDefaultValues). SamePlace가 Int를
 * 받도록 만든 이유가 이것이다.
 */
class SamePlaceTest {

    // 실측 화면(1080x2400)에서 가져온 크기들.
    private val ROW_GAP = 186        // 항목 사이 간격. 한 줄 스크롤이 이만큼 움직인다.

    @Test
    fun `같은 자리는 완전히 같다`() {
        assertEquals(1f, SamePlace.overlap(0, 0, 200, 100, 0, 0, 200, 100), 0.001f)
    }

    @Test
    fun `넓이가 0인 노드도 자기 자신과는 맞는다`() {
        // 안 보이는 자리표시자가 이렇게 나온다. 넓이로만 따지면 자기 자신과도
        // 안 맞는다고 나와서, 그 노드는 영원히 클릭할 수 없게 된다.
        assertTrue(SamePlace.enough(540, 300, 540, 300, 540, 300, 540, 300))
    }

    @Test
    fun `몇 픽셀 흔들림은 통과한다`() {
        // 애니메이션이 끝나가는 중, 스크롤 관성이 남은 상태, 리스트 항목 재활용.
        assertTrue("200x100 버튼이 5px 밀린 것은 같은 자리다",
            SamePlace.enough(0, 0, 200, 100, 5, 0, 205, 100))
        assertTrue("40x40 아이콘이 5px 밀린 것도 같은 자리다",
            SamePlace.enough(0, 0, 40, 40, 5, 0, 45, 40))
        assertTrue("상단 배너가 접히며 세로로 3px 올라간 경우",
            SamePlace.enough(100, 500, 900, 600, 100, 497, 900, 597))
    }

    @Test
    fun `한 줄 스크롤은 거부한다`() {
        // 이걸 통과시키면 위 항목을 누르려다 아래 항목을 누른다. 되돌리기 어려운
        // 종류의 오작동이라, 여유를 주더라도 여기까지는 절대 넘어오면 안 된다.
        assertFalse("한 줄 아래로 밀린 자리는 다른 자리다",
            SamePlace.enough(0, 1049, 200, 1149, 0, 1049 + ROW_GAP, 200, 1149 + ROW_GAP))
        assertEquals("겹치지 않으면 0이다", 0f,
            SamePlace.overlap(0, 1049, 200, 1149, 0, 1049 + ROW_GAP, 200, 1149 + ROW_GAP),
            0.001f)
    }

    @Test
    fun `크기가 크게 달라지면 거부한다`() {
        // 같은 왼쪽 위 꼭짓점에서 시작해도 크기가 다르면 다른 항목이다. 접혀 있던
        // 영역이 펼쳐지거나, 목록이 한 줄에서 두 줄로 늘어난 경우다.
        assertFalse("높이가 두 배가 된 자리",
            SamePlace.enough(0, 0, 200, 100, 0, 0, 200, 200))
        assertFalse("절반으로 줄어든 자리",
            SamePlace.enough(0, 0, 200, 100, 0, 0, 100, 100))
    }

    @Test
    fun `기준선이 어디인지 박아둔다`() {
        // ENOUGH를 옮기면 위 케이스들이 조용히 뜻을 바꾼다. 여유가 실제로 얼마인지
        // 여기에 숫자로 남겨, 기준을 만질 때 무엇을 맞바꾸는지 보이게 한다.
        assertEquals(0.6f, SamePlace.ENOUGH, 0.001f)
        assertEquals("40x40 아이콘 10px 밀림이 기준선이다", 0.6f,
            SamePlace.overlap(0, 0, 40, 40, 10, 0, 50, 40), 0.02f)
        assertEquals("200x100 버튼은 50px까지 견딘다", 0.6f,
            SamePlace.overlap(0, 0, 200, 100, 50, 0, 250, 100), 0.02f)
    }
}
