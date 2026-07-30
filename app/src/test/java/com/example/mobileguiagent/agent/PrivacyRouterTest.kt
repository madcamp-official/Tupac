package com.example.mobileguiagent.agent

import android.graphics.Rect
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 화면을 클라우드로 보낼지, 가리고 보낼지, 아예 사람에게 넘길지 가르는 규칙.
 *
 * 라우터는 팀원 브랜치에서 가져왔고 낱말 목록에 기대는 방식이다. 그런 규칙은
 * 조용히 넓어지거나 좁아진다 — 낱말 하나를 더하면 엉뚱한 화면이 걸리고, 하나를
 * 빼면 새는데 둘 다 티가 안 난다. 그래서 잡아야 하는 것과 잡으면 안 되는 것을
 * 양쪽으로 박아둔다.
 *
 * 여기서 재는 것은 판정뿐이다. 판정 결과로 무엇을 가리는지는
 * PocketMcpHttpServer가 정하고, 그쪽은 실기기로 확인한다.
 */
class PrivacyRouterTest {

    private fun node(
        id: String,
        text: String? = null,
        hint: String? = null,
        desc: String? = null,
        viewId: String? = null,
        password: Boolean = false,
        editable: Boolean = false,
    ) = UiNode(
        id = id,
        text = text,
        contentDescription = desc,
        hint = hint,
        className = null,
        viewId = viewId,
        clickable = false,
        editable = editable,
        scrollable = false,
        enabled = true,
        checked = null,
        bounds = Rect(),
        depth = 1,
        visibleToUser = true,
        password = password,
    )

    private fun screen(packageName: String, vararg nodes: UiNode) =
        ScreenPrivacyRouter.route(UiSnapshot(packageName = packageName, nodes = nodes.toList()))

    // ─────────────────── 잡아야 하는 것 ───────────────────

    @Test
    fun `결제 자격증명 화면은 사람에게 넘긴다`() {
        // 이 갈래가 이 라우터를 가져온 이유다. blockedApp은 패키지 이름으로만
        // 거르므로, 쇼핑앱 안의 결제 폼은 그대로 통과한다.
        val decision = screen(
            "com.example.shop",
            node("node_1", text = "결제 수단"),
            node("node_2", hint = "카드번호", editable = true),
            node("node_3", hint = "CVC", editable = true),
        )
        assertEquals(PrivacyRoute.USER_HANDOFF, decision.route)
        assertTrue(PrivacyReason.PAYMENT_CREDENTIALS in decision.reasons)
    }

    @Test
    fun `비밀번호 칸은 플래그만으로 잡는다`() {
        // 라벨이 없어도 걸려야 한다. 접근성 트리가 알려주는 구조라 라벨보다 확실하다.
        val decision = screen(
            "com.example.shop",
            node("node_1", editable = true, password = true),
        )
        assertEquals(PrivacyRoute.CLOUD_REDACTED, decision.route)
        assertTrue(PrivacyReason.PASSWORD_FIELD in decision.reasons)
        assertTrue("node_1" in decision.sensitiveNodeIds)
    }

    @Test
    fun `배송 정보와 전화번호를 알아본다`() {
        val decision = screen(
            "com.example.shop",
            node("node_1", hint = "받는사람", editable = true),
            node("node_2", text = "010-1234-5678"),
        )
        assertEquals(PrivacyRoute.CLOUD_REDACTED, decision.route)
        assertTrue(PrivacyReason.DELIVERY_DETAILS in decision.reasons)
        assertTrue(PrivacyReason.PHONE_NUMBER in decision.reasons)
    }

    @Test
    fun `은행 앱은 화면 전체를 가릴 이유가 된다`() {
        val decision = screen("com.kbstar.kbbank", node("node_1", text = "계좌 조회"))
        assertTrue(PrivacyReason.SENSITIVE_PACKAGE in decision.reasons)
    }

    // ─────────────────── 잡으면 안 되는 것 ───────────────────

    @Test
    fun `평범한 화면은 그대로 보낸다`() {
        val decision = screen(
            "com.android.settings",
            node("node_1", text = "설정"),
            node("node_2", text = "와이파이"),
        )
        assertEquals(PrivacyRoute.CLOUD_OK, decision.route)
        assertTrue(decision.reasons.isEmpty())
    }

    @Test
    fun `런처의 앱 이름은 그 화면에 자격증명이 있다는 뜻이 아니다`() {
        // 홈 화면에는 은행 앱과 OTP 앱 이름이 그냥 놓여 있다. 이걸 잡으면
        // 홈 화면에서 아무것도 못 하게 된다.
        val decision = screen(
            "com.sec.android.app.launcher",
            node("node_1", text = "OTP"),
            node("node_2", text = "카드번호 메모"),
        )
        assertEquals(PrivacyRoute.CLOUD_OK, decision.route)
    }

    @Test
    fun `우리 앱 화면의 목표 문장은 신호가 아니다`() {
        // 상태 화면에 "로그인 해줘" 같은 목표가 떠 있어도 그건 요청이지
        // 그 화면에 자격증명이 있다는 뜻이 아니다.
        val decision = screen(
            "com.example.mobileguiagent",
            node("node_1", text = "인스타그램 비밀번호 넣고 로그인해줘"),
        )
        assertEquals(PrivacyRoute.CLOUD_OK, decision.route)
    }

    @Test
    fun `pin이 낱말 속에 있는 것은 인증이 아니다`() {
        // "shopping", "spinner" 같은 흔한 낱말과 WebView의 view id에 pin이 들어 있다.
        // 부분일치로 보면 쇼핑 화면이 전부 인증 화면이 된다.
        val decision = screen(
            "com.example.shop",
            node("node_1", text = "shopping cart"),
            node("node_2", viewId = "com.example.shop:id/spinner_sort"),
        )
        assertFalse(PrivacyReason.AUTHENTICATION_SCREEN in decision.reasons)
    }
}
