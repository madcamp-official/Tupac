package com.example.mobileguiagent.mcp

import com.example.mobileguiagent.model.UiNode

/**
 * 화면을 밖의 모델에게 한 줄씩 보낸다.
 *
 * 왜 JSON을 버렸는가:
 *   노드 하나가 필드 15개짜리 JSON이었고 381바이트였다. 그런데 "무엇을 누를까"를
 *   정하는 데 쓰이는 것은 id, 라벨, 무엇을 할 수 있는지 셋뿐이다. className,
 *   viewId, depth, enabled, password... 는 한 번도 읽히지 않는다. 화면 하나가
 *   3,500~6,000토큰이었고, 관찰은 매 걸음 일어나므로 다섯 걸음이면 화면 덤프만
 *   3만 토큰이 대화에 쌓인다. 그리고 그것을 매 턴 처음부터 다시 읽는다.
 *
 *   실측(홈 화면 28개 노드): JSON 11,370바이트 → 이 형식 926바이트, 12.3배.
 *
 * 좌표를 남기는 이유:
 *   누를 때는 노드 번호만 있으면 되므로 좌표는 필요 없다. 하지만 어느 것이 한
 *   묶음인지는 좌표로만 알 수 있다 — 상품 이름과 그 옆 장바구니 단추가 같은 줄에
 *   있다는 것 말이다. 네 모서리는 과해서 중심점 하나로 줄였다.
 *
 * 표시가 없는 줄은 읽기만 하는 글자다. 라벨은 그 자체로 뜻이 있으므로("로그인",
 * "받는사람") 지우지 않는다. 개인정보는 여기 오기 전에 이미 걸러져 있다.
 */
object ScreenLines {

    /** 도구 설명에 실을 범례. 응답마다 붙이면 줄인 의미가 없어서 한 번만 말한다. */
    const val LEGEND: String =
        "Each line is `node_N [marks] label @x,y`. Marks: 누름=clickable, " +
            "입력=text field, 비밀번호=password field, 스크롤=scrollable, " +
            "켜짐/꺼짐=toggle state, 잠김=disabled, 값=slider (current/max). " +
            "A line with no marks is text you can only read. @x,y is the node's " +
            "centre in pixels — use it to tell which items belong together, not to tap. " +
            "Tap by node id with device_click_node."

    fun render(nodes: List<UiNode>, label: (UiNode) -> String): String =
        nodes.joinToString("\n") { node -> line(node, label(node)) }

    private fun line(node: UiNode, label: String): String {
        val marks = marksOf(node)
        // Rect.centerX()가 아니라 값으로 직접 센다. 단위 테스트의 android.jar는
        // 껍데기라 메서드가 전부 0을 돌려준다 — 자리를 검사할 수 없게 된다.
        val bounds = node.bounds
        val centre = "@${(bounds.left + bounds.right) / 2},${(bounds.top + bounds.bottom) / 2}"
        // 빈 칸이 겹치지 않게 있는 것만 잇는다. 라벨 없는 노드가 흔하다
        // (실측: 쿠팡 상품 카드는 누를 수 있는데 글자가 하나도 없었다).
        return listOf(node.id, marks, label, centre)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun marksOf(node: UiNode): String {
        val marks = mutableListOf<String>()
        // 비밀번호 칸은 "입력"으로 뭉뚱그리지 않는다. 여기에 값을 직접 넣으면
        // 안 된다는 것을 부르는 쪽이 알아야 한다.
        if (node.editable) marks += if (node.password) "비밀번호" else "입력"
        if (node.clickable) marks += "누름"
        if (node.scrollable) marks += "스크롤"
        node.checked?.let { marks += if (it) "켜짐" else "꺼짐" }
        // 슬라이더는 지금 값을 알아야 얼마나 옮길지 정할 수 있다.
        node.range?.let { marks += "값 ${fmt(it.current)}/${fmt(it.max)}" }
        if (!node.enabled) marks += "잠김"
        return if (marks.isEmpty()) "" else marks.joinToString(",", "[", "]")
    }

    /** 밝기 눈금이 267386880까지 간다. 소수점은 자리만 차지한다. */
    private fun fmt(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else "%.1f".format(value)
}
