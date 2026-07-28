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
        parent: String? = null,
    ) = UiNode(
        id = id, text = null, contentDescription = null, hint = null,
        className = null, viewId = null,
        clickable = clickable, editable = editable, scrollable = scrollable,
        enabled = enabled, checked = checked,
        // 생성자 대신 값을 직접 넣는다. 단위 테스트의 Rect는 껍데기라 생성자가
        // 아무것도 하지 않는다.
        bounds = Rect().also { it.left = left; it.top = top; it.right = right; it.bottom = bottom },
        depth = 1, parentId = parent,
        password = password, range = range,
    )

    private fun render(node: UiNode, label: String) =
        ScreenLines.render(listOf(node), listOf(node)) { label }

    private fun render(nodes: List<UiNode>, labels: Map<String, String>) =
        ScreenLines.render(nodes, nodes) { node -> labels[node.id].orEmpty() }

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
            render(
                listOf(
                    node("node_1", editable = true),
                    node("node_2", scrollable = true),
                    node("node_3", clickable = true, enabled = false),
                ),
                mapOf("node_1" to "검색", "node_3" to "저장"),
            ),
        )
    }

    @Test
    fun `글자는 그 글자를 감싼 단추에 얹힌다`() {
        // 실측(쿠팡 홈): "로그인"이라 적힌 줄은 누를 수 없고, 진짜 단추에는
        // 글자가 없다. 둘을 좌표로 짝짓는 것이 부르는 쪽의 주된 일이었다.
        assertEquals(
            "단추와 간판이 따로 오면 어느 것이 짝인지 매번 좌표로 재야 한다",
            "node_16 [누름] 로그인 @910,189",
            render(
                listOf(
                    node("node_16", 789, 141, 1032, 237, clickable = true),
                    node("node_18", 861, 141, 1008, 237, parent = "node_16"),
                ),
                mapOf("node_18" to "로그인"),
            ),
        )
    }

    @Test
    fun `스크롤 상자를 넘어서까지 얹지는 않는다`() {
        // 리스트는 화면 전체를 감싼다. 여기를 넘어가면 모든 글자가 리스트 하나에
        // 달라붙어 화면이 도리어 뭉개진다.
        assertEquals(
            "node_25 [스크롤] @50,50\nnode_30 이 상품 놓치지 마세요! @50,50",
            render(
                listOf(
                    node("node_25", scrollable = true),
                    node("node_30", parent = "node_25"),
                ),
                mapOf("node_30" to "이 상품 놓치지 마세요!"),
            ),
        )
    }

    @Test
    fun `임자가 없는 글자는 제 줄로 남는다`() {
        assertEquals(
            "화면 제목처럼 아무 단추에도 속하지 않는 글자가 사라지면 안 된다",
            "node_9 카카오톡을 시작합니다 @50,50",
            render(listOf(node("node_9")), mapOf("node_9" to "카카오톡을 시작합니다")),
        )
    }

    @Test
    fun `단추가 제 설명을 가지면 그것이 앞에 온다`() {
        assertEquals(
            "단추 자신의 설명이 안쪽 글자보다 그 단추를 잘 가리킨다",
            "node_14 [누름] 쿠팡 홈 3 @50,50",
            render(
                listOf(
                    node("node_14", clickable = true),
                    node("node_15", parent = "node_14"),
                ),
                mapOf("node_14" to "쿠팡 홈", "node_15" to "3"),
            ),
        )
    }

    @Test
    fun `글자가 많은 단추는 잘라서 얹는다`() {
        assertEquals(
            "상품 카드는 글이 예닐곱 줄이라 그대로 얹으면 줄을 다 차지한다",
            "node_113 [누름] 세탁세제 12900원 로켓배송 @50,50",
            render(
                listOf(
                    node("node_113", clickable = true),
                    node("node_114", parent = "node_113"),
                    node("node_115", parent = "node_113"),
                    node("node_116", parent = "node_113"),
                    node("node_117", parent = "node_113"),
                ),
                mapOf(
                    "node_114" to "세탁세제", "node_115" to "12900원",
                    "node_116" to "로켓배송", "node_117" to "내일 도착",
                ),
            ),
        )
    }

    @Test
    fun `중첩된 단추는 각자 남는다`() {
        // 목록 한 줄 전체가 눌리면서 그 안의 스위치도 따로 눌리는 화면이 있다.
        assertEquals(
            "안쪽 단추가 사라지면 스위치만 누르는 길이 없어진다",
            "node_40 [누름] 저장 @50,50\nnode_42 [누름,켜짐] @50,50",
            render(
                listOf(
                    node("node_40", clickable = true),
                    node("node_41", parent = "node_40"),
                    node("node_42", clickable = true, checked = true, parent = "node_40"),
                ),
                mapOf("node_41" to "저장"),
            ),
        )
    }
}
