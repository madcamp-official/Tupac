package com.example.mobileguiagent.mcp

import android.graphics.Rect
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 한 줄 형식이 무엇을 담고 무엇을 버리는지 못박는다.
 *
 * 화면은 실기기에서 본 것을 옮겼다 — 쿠팡 홈의 검색창과 글자 없는 상품 카드,
 * 설정의 토글과 밝기 슬라이더다.
 */
class ScreenLinesTest {

    private fun node(
        id: String,
        left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 100,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
        checked: Boolean? = null,
        password: Boolean = false,
        range: UiRange? = null,
    ) = UiNode(
        id = id, text = null, contentDescription = null, hint = null,
        className = null, viewId = null,
        clickable = clickable, editable = editable, scrollable = scrollable,
        enabled = enabled, checked = checked,
        // 생성자 대신 값을 직접 넣는다. 단위 테스트의 Rect는 껍데기라 생성자가
        // 아무것도 하지 않는다.
        bounds = Rect().also { it.left = left; it.top = top; it.right = right; it.bottom = bottom },
        depth = 1,
        password = password, range = range,
    )

    private fun render(node: UiNode, label: String) =
        ScreenLines.render(listOf(node)) { label }

    @Test
    fun `누를 수 있는 것은 표시와 중심점이 붙는다`() {
        assertEquals(
            "네 모서리 대신 중심점 하나만 남긴다. 어느 것이 한 묶음인지만 알면 된다",
            "node_16 [누름] 로그인 @910,189",
            render(node("node_16", 789, 141, 1032, 237, clickable = true), "로그인"),
        )
    }

    @Test
    fun `비밀번호 칸은 입력과 구별한다`() {
        assertEquals(
            "여기에 값을 직접 넣으면 안 된다는 것을 부르는 쪽이 알아야 한다",
            "node_15 [비밀번호] 비밀번호 @50,50",
            render(node("node_15", editable = true, password = true), "비밀번호"),
        )
    }

    @Test
    fun `라벨이 없으면 빈 칸이 겹치지 않는다`() {
        // 실측: 쿠팡 상품 카드는 누를 수 있는데 글자가 하나도 없었다.
        assertEquals(
            "node_113 [누름] @219,1934",
            render(node("node_113", 24, 1790, 414, 2079, clickable = true), ""),
        )
    }

    @Test
    fun `토글과 슬라이더는 지금 값을 함께 준다`() {
        assertEquals(
            "켜져 있는지 모르면 껐다 켰다를 뒤집는다",
            "node_40 [누름,켜짐] 블루투스 @50,50",
            render(node("node_40", clickable = true, checked = true), "블루투스"),
        )
        assertEquals(
            "지금 값을 알아야 얼마나 옮길지 정할 수 있다. 소수점은 자리만 차지한다",
            "node_41 [값 128/255] 밝기 @50,50",
            render(node("node_41", range = UiRange(0f, 255f, 128f)), "밝기"),
        )
    }

    @Test
    fun `표시가 없는 줄은 읽기만 하는 글자다`() {
        assertEquals(
            "node_18 이 상품 놓치지 마세요! @50,50",
            render(node("node_18"), "이 상품 놓치지 마세요!"),
        )
    }

    @Test
    fun `여러 줄은 줄바꿈으로 잇는다`() {
        assertEquals(
            "node_1 [입력] 검색 @50,50\nnode_2 [스크롤] @50,50\nnode_3 [누름,잠김] 저장 @50,50",
            ScreenLines.render(
                listOf(
                    node("node_1", editable = true),
                    node("node_2", scrollable = true),
                    node("node_3", clickable = true, enabled = false),
                ),
            ) { node ->
                mapOf("node_1" to "검색", "node_2" to "", "node_3" to "저장").getValue(node.id)
            },
        )
    }
}
