package com.example.mobileguiagent.agent

import android.graphics.Rect
import com.example.mobileguiagent.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FieldAssign이 파이썬(eval/assign.py)과 같은 판단을 하는지 본다.
 *
 * 케이스는 eval/cases.py에서 옮겼다. 대부분 "예전에 이렇게 틀렸다"의 기록이라,
 * 각 단언에 그 사연을 붙였다 — 깨졌을 때 무엇을 되돌린 것인지 알 수 있게.
 *
 * 화면은 실기기에서 본 특징만 그대로 옮겨 담은 것이다. 카카오톡 아이디 칸의
 * 라벨, 크롬 주소창의 viewId처럼 판단을 갈랐던 것들이다.
 */
class FieldAssignTest {

    private fun node(
        id: String,
        text: String? = null,
        hint: String? = null,
        viewId: String? = null,
        editable: Boolean = false,
        clickable: Boolean = false,
        password: Boolean = false,
    ) = UiNode(
        id = id,
        text = text,
        contentDescription = null,
        hint = hint,
        className = null,
        viewId = viewId,
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = true,
        checked = null,
        bounds = Rect(),
        depth = 1,
        password = password,
    )

    // 아이디 칸 라벨이 "이메일 또는 전화번호"다. 라벨로 맞추면 email로 간다.
    private val kakaoLogin = listOf(
        node("node_9", text = "카카오톡을 시작합니다"),
        node("node_12", hint = "이메일 또는 전화번호", editable = true),
        node("node_15", hint = "비밀번호", editable = true, password = true),
        node("node_16", text = "로그인", clickable = true),
        node("node_17", text = "새로운 카카오계정 만들기", clickable = true),
        node("node_18", text = "카카오계정 또는 비밀번호 찾기", clickable = true),
    )

    // 크롬 웹 폼. 라벨이 hint에만 있고, 주소창이 editable로 섞여 있다.
    // text가 null이 아니라 ""다. 실기기 크롬이 그렇게 준다 — 이걸 null로 만들어
    // 두는 바람에 배송지 폼 5칸을 전부 놓치는 버그를 못 잡았다.
    private val chromeForm = listOf(
        node("node_15", text = "", hint = "받는사람", viewId = "a", editable = true),
        node("node_17", text = "", hint = "연락처", viewId = "b", editable = true),
        node("node_20", text = "", hint = "우편번호", viewId = "c", editable = true),
        node("node_22", text = "", hint = "주소", viewId = "d", editable = true),
        node("node_24", text = "", hint = "상세주소", viewId = "e", editable = true),
        node("node_37", text = "localhost:8090",
             viewId = "com.android.chrome:id/url_bar", editable = true),
        node("node_90", text = "저장", clickable = true),
    )

    private fun assign(nodes: List<UiNode>, pkg: String, wanted: Set<String>) =
        FieldAssign.inputTargets(nodes, pkg, wanted).map { (node, field) -> node.id to field }

    @Test
    fun `로그인은 라벨이 아니라 password 플래그로 가른다`() {
        assertEquals(
            "라벨이 '이메일 또는 전화번호'라 라벨로 맞추면 email로 간다",
            listOf("node_12" to "username", "node_15" to "password"),
            assign(kakaoLogin, "com.kakao.talk", setOf("username", "password")),
        )
    }

    @Test
    fun `폼은 라벨로 맞춘다`() {
        assertEquals(
            "'이메일 주소'를 공백으로 쪼개면 '주소' 칸이 email로 잡혔다",
            listOf(
                "node_15" to "name", "node_17" to "phone", "node_20" to "postcode",
                "node_22" to "address", "node_24" to "address_detail",
            ),
            assign(chromeForm, "com.android.chrome",
                   setOf("name", "phone", "postcode", "address", "address_detail", "email")),
        )
    }

    @Test
    fun `크롬 주소창은 입력 대상이 아니다`() {
        // 아이디를 먼저 받는 2단계 로그인이면 "비밀번호 아닌 입력창"이 주소창
        // 하나뿐이 되어, 아이디가 주소창에 입력된다.
        val twoStep = listOf(
            node("node_37", text = "accounts.kakao.com",
                 viewId = "com.android.chrome:id/url_bar", editable = true),
            node("node_40", hint = "비밀번호", editable = true, password = true),
        )
        assertEquals(
            "주소창이 username 칸으로 잡히면 아이디가 주소창에 들어간다",
            listOf("node_40" to "password"),
            assign(twoStep, "com.android.chrome", setOf("username", "password")),
        )
    }

    @Test
    fun `제출 버튼은 후보가 하나일 때만 고른다`() {
        assertEquals(
            "카카오톡 로그인 화면의 후보는 '로그인' 하나다. '찾기'·'만들기'는 뺀다",
            "node_16",
            FieldAssign.submitButton(kakaoLogin, "com.kakao.talk")?.id,
        )
        assertNull(
            "'확인'이 둘이면 어느 쪽인지 알 수 없다. 엉뚱한 버튼은 되돌리기 어렵다",
            FieldAssign.submitButton(
                listOf(node("node_1", text = "확인", clickable = true),
                       node("node_2", text = "확인", clickable = true)),
                "com.example.app",
            ),
        )
    }

    @Test
    fun `계획은 필드 순서만 담는다`() {
        assertEquals(
            "노드 번호를 박아두면 한 칸 채운 뒤 뒤 칸 번호가 밀려 어긋난다",
            listOf("username", "password"),
            FieldAssign.planFields(kakaoLogin, "com.kakao.talk", setOf("username", "password")),
        )
    }

    @Test
    fun `짚어주는 줄은 fill 형식이고 값이 들어간다`() {
        val plan = FieldAssign.stepsNow(
            kakaoLogin, "com.kakao.talk",
            values = mapOf("username" to "minsu", "password" to "pw1234"),
            order = listOf("username", "password"),
            wantSubmit = true, filled = emptySet(), submitted = false,
        )
        assertEquals(
            "화면 표기가 \"node_12 [type] 라벨\"이라 type 형식은 앞부분이 겹친다. " +
                "실측: 카카오톡 로그인에서 type 0/10, fill 10/10",
            "fill node_12 minsu",
            plan.current?.line,
        )
        assertNull("막힐 이유가 없다", plan.blocked)
    }

    @Test
    fun `채운 칸은 다시 찾지 않는다`() {
        val plan = FieldAssign.stepsNow(
            kakaoLogin, "com.kakao.talk",
            values = mapOf("username" to "minsu", "password" to "pw1234"),
            order = listOf("username", "password"),
            wantSubmit = true, filled = setOf("username"), submitted = false,
        )
        assertEquals(
            "값을 넣으면 라벨이 값으로 바뀐다. 다시 찾으면 '칸이 없음'이 된다",
            "fill node_15 pw1234",
            plan.current?.line,
        )
        assertEquals(
            "끝난 단계도 값과 함께 보여준다. 값 예시가 없으면 모델이 값을 못 쓴다 " +
                "(실측: 예시 0개면 0/20, 3개면 20/20)",
            "fill node_12 minsu",
            plan.steps.first { it.done }.line,
        )
    }

    @Test
    fun `칸이 사라지면 다음 단계로 넘어가지 않고 멈춘다`() {
        // 아이디를 넣은 뒤 팝업이 끼어들어 비밀번호 칸이 사라진 화면.
        val popped = listOf(
            node("node_12", text = "minsu", editable = true),
            node("node_18", text = "로그인", clickable = true),
        )
        val plan = FieldAssign.stepsNow(
            popped, "com.kakao.talk",
            values = mapOf("username" to "minsu", "password" to "pw1234"),
            order = listOf("username", "password"),
            wantSubmit = true, filled = setOf("username"), submitted = false,
        )
        assertEquals(
            "건너뛰면 값을 덜 채운 채 제출을 누르게 된다",
            "password를 넣을 칸이 지금 화면에 없습니다",
            plan.blocked,
        )
        assertNull("막혔으면 짚어줄 줄이 없다", plan.current)
    }

    @Test
    fun `값이 든 칸도 라벨은 hint로 읽는다`() {
        // 한 칸을 채우면 text에 라벨이 아니라 그 값이 들어간다. text를 먼저 보면
        // "홍길동"이 라벨이 되어 어느 필드에도 안 걸린다.
        val filledIn = listOf(node("node_15", text = "홍길동", hint = "받는사람",
                                   viewId = "a", editable = true))
        assertEquals(
            "채운 칸을 다시 찾을 수 있어야 화면이 바뀌어도 계획이 이어진다",
            listOf("node_15" to "name"),
            assign(filledIn, "com.android.chrome", setOf("name")),
        )
    }

    @Test
    fun `모르는 칸은 비워둔다`() {
        val unknown = listOf(node("node_1", hint = "추천인 코드", editable = true))
        assertEquals(
            "짐작으로 채우느니 비워두는 편이 낫다",
            emptyList<Pair<String, String>>(),
            assign(unknown, "com.example.shop", setOf("name", "phone")),
        )
    }
}
