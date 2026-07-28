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

    /** 한 단추에 붙일 글자 수. 상품 카드처럼 글이 많은 것이 줄을 다 차지하지 않게. */
    private const val MAX_LABELS = 3

    /** 한 줄에 실을 글자 길이. 이보다 길면 무엇인지는 이미 알 수 있다. */
    private const val MAX_LABEL_CHARS = 80

    /**
     * 띄어쓰기 없이 이만큼 길면 사람에게 보여주는 글이 아니라 주소나 식별자로 본다.
     *
     * 실측(네이버 검색결과): 광고 단추의 contentDescription이
     * "44KouPt1vroSwhzmZjPhA2xVhPNUKcHaEM9ADnJzghu5hCYK..." 로 400자였다. 이런 줄은
     * 무엇을 누를지 정하는 데 아무 도움이 안 되면서 화면의 절반을 차지한다.
     */
    private const val OPAQUE_CHARS = 40

    private val WHITESPACE = Regex("\\s+")

    /**
     * 글자를 그 글자를 감싼 단추에 얹어 한 줄로 만든다.
     *
     * 왜 필요한가(실측):
     *   쿠팡 홈 47줄 중 그대로 쓸 수 있는 줄은 2줄이었다. 28줄은 무엇인지 모를
     *   단추였고 17줄은 누를 수 없는 글자였다. 설정은 24줄 중 1줄이었다. 즉
     *   "이 글자의 임자가 어느 단추인가"를 맞추는 것이 예외가 아니라 대부분의
     *   일이었고, 그걸 부르는 쪽이 좌표 거리로 짐작하고 있었다.
     *
     *   폰은 짐작할 이유가 없다. 트리에 부모가 그대로 있다. 같은 걸 누를 때는
     *   이미 하고 있었다(AgentAccessibilityService.findClickableNode) — 읽을 때만
     *   안 하고 있었다.
     *
     * 스크롤 상자를 만나면 멈춘다. 리스트는 화면 전체를 감싸므로, 넘어가면 모든
     * 글자가 리스트 하나에 달라붙어 도리어 화면이 뭉개진다.
     */
    fun render(shown: List<UiNode>, all: List<UiNode>, label: (UiNode) -> String): String {
        val byId = all.associateBy { it.id }
        val visible = shown.mapTo(mutableSetOf()) { it.id }

        // 어느 글자가 어느 단추의 것인지 먼저 정한다.
        val merged = mutableMapOf<String, MutableList<String>>()
        val absorbed = mutableSetOf<String>()
        for (node in shown) {
            if (isTarget(node)) continue
            val text = label(node)
            if (text.isEmpty()) continue
            val owner = ownerOf(node, byId)?.takeIf { it.id in visible } ?: continue
            merged.getOrPut(owner.id) { mutableListOf() } += text
            // 붙일 자리가 없어도 지운다. 임자가 정해진 글자를 따로 한 줄 더
            // 내보내면 합친 뜻이 없다.
            absorbed += node.id
        }

        return shown
            .filterNot { it.id in absorbed }
            .joinToString("\n") { node ->
                // 제 라벨이 먼저다. 단추 자신의 설명("쿠팡 홈")이 안쪽 글자보다
                // 그 단추를 잘 가리킨다.
                line(node, joinLabels(listOf(label(node)) + merged[node.id].orEmpty()))
            }
    }

    /**
     * 겹치는 글자를 걷어내고 한 줄 분량으로 줄인다.
     *
     * 왜 단순히 이어 붙이면 안 되는가(실측, 네이버):
     *   "  NAVER NAVER", "네이버페이 네이버페이" 처럼 같은 말이 두 번 나온다.
     *   단추의 contentDescription이 안쪽 글자를 이미 담고 있는데, 앞뒤 공백이
     *   달라서 글자 비교로는 같은 줄로 보이지 않기 때문이다. 그래서 공백을
     *   고른 뒤에 견주고, 이미 담긴 말이면 버린다.
     */
    private fun joinLabels(candidates: List<String>): String {
        val kept = mutableListOf<String>()
        for (raw in candidates) {
            val text = WHITESPACE.replace(raw.trim(), " ")
            if (text.isEmpty()) continue
            if (text.length >= OPAQUE_CHARS && !text.contains(' ')) continue
            // 이미 넣은 말에 들어 있으면 버린다("NAVER"는 "  NAVER"와 같은 말이다).
            if (kept.any { it.contains(text) }) continue
            kept.removeAll { text.contains(it) }
            kept += text
            if (kept.size >= MAX_LABELS) break
        }
        val joined = kept.joinToString(" ")
        return if (joined.length <= MAX_LABEL_CHARS) joined
        else joined.take(MAX_LABEL_CHARS).trimEnd() + "…"
    }

    /** 부를 수 있는 것. 스크롤 상자는 글자의 임자가 될 수 없다. */
    private fun isTarget(node: UiNode): Boolean = node.clickable || node.editable

    private fun ownerOf(node: UiNode, byId: Map<String, UiNode>): UiNode? {
        var current = node.parentId?.let(byId::get)
        while (current != null) {
            if (isTarget(current)) return current
            if (current.scrollable) return null
            current = current.parentId?.let(byId::get)
        }
        return null
    }

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
