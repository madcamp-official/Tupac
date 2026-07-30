package com.example.mobileguiagent

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mobileguiagent.agent.PrivacyReason
import com.example.mobileguiagent.agent.PrivacyRoute
import com.example.mobileguiagent.agent.SensitiveScreenActionPolicy
import com.example.mobileguiagent.agent.ScreenPrivacyRouter
import com.example.mobileguiagent.model.AgentActionBoundaryPolicy
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyRouterInstrumentedTest {
    @Test
    fun authenticationLabelRoutesToRedactedCloudWithoutImmediateHandoff() {
        val decision = ScreenPrivacyRouter.route(
            snapshot(
                node(
                    id = "guest",
                    text = "비회원 로그인",
                    clickable = true,
                ),
                node(
                    id = "auth",
                    text = "인증번호",
                    editable = true,
                ),
            ),
        )

        assertEquals(PrivacyRoute.CLOUD_REDACTED, decision.route)
        assertEquals(true, PrivacyReason.AUTHENTICATION_SCREEN in decision.reasons)
    }

    @Test
    fun ordinaryProductScreenStaysCloudFirst() {
        val decision = ScreenPrivacyRouter.route(
            snapshot(
                node("생수 2L 6개"),
                node("장바구니"),
                node("구매하기", clickable = true),
            ),
        )
        assertEquals(PrivacyRoute.CLOUD_OK, decision.route)
    }

    @Test
    fun ordinaryWordsContainingPinOrOtpDoNotTriggerAuthenticationRouting() {
        val decision = ScreenPrivacyRouter.route(
            snapshot(
                node("shopping"),
                node("spinner_movie"),
                node("hotpick"),
            ),
        )

        assertEquals(PrivacyRoute.CLOUD_OK, decision.route)
    }

    @Test
    fun controllerGoalTextDoesNotMasqueradeAsTargetAuthenticationScreen() {
        val decision = ScreenPrivacyRouter.route(
            snapshot(
                node("메가박스 앱을 열고 저장된 계정으로 로그인해", editable = true),
            ).copy(packageName = "com.example.mobileguiagent"),
        )

        assertEquals(PrivacyRoute.CLOUD_OK, decision.route)
    }

    @Test
    fun controllerPasswordFieldUsesRedactedCloudRoute() {
        val decision = ScreenPrivacyRouter.route(
            snapshot(node("비밀번호", password = true))
                .copy(packageName = "com.example.mobileguiagent"),
        )

        assertEquals(PrivacyRoute.CLOUD_REDACTED, decision.route)
        assertEquals(true, PrivacyReason.PASSWORD_FIELD in decision.reasons)
    }

    @Test
    fun checkoutDeliveryScreenRoutesToRedactedCloud() {
        val decision = ScreenPrivacyRouter.route(
            snapshot(node("주문/결제"), node("배송지"), node("받는 분")),
        )
        assertEquals(PrivacyRoute.CLOUD_REDACTED, decision.route)
        assertEquals(true, PrivacyReason.DELIVERY_DETAILS in decision.reasons)
    }

    @Test
    fun passwordScreenUsesRedactedCloudPlanning() {
        val decision = ScreenPrivacyRouter.route(
            snapshot(node("비밀번호", password = true)),
        )
        assertEquals(PrivacyRoute.CLOUD_REDACTED, decision.route)
        assertEquals(true, PrivacyReason.PASSWORD_FIELD in decision.reasons)
    }

    @Test
    fun sensitiveHintWithoutTextIsStillKeptOnDevice() {
        val sensitive = node("", id = "card_field").copy(
            hint = "카드번호",
            editable = true,
        )
        val decision = ScreenPrivacyRouter.route(snapshot(sensitive))

        assertEquals(PrivacyRoute.USER_HANDOFF, decision.route)
        assertEquals(true, PrivacyReason.PAYMENT_CREDENTIALS in decision.reasons)
    }

    @Test
    fun sensitiveAuthenticationPolicyAllowsGuestTabButBlocksLoginSubmission() {
        assertNull(
            SensitiveScreenActionPolicy.blockedTapReason(
                node("비회원 로그인", clickable = true),
            ),
        )
        assertNotNull(
            SensitiveScreenActionPolicy.blockedTapReason(
                node("로그인", clickable = true),
            ),
        )
        assertNotNull(
            SensitiveScreenActionPolicy.blockedTapReason(
                node = node("비회원 예매확인", clickable = true),
                goal = "오디세이를 비회원으로 예매해줘",
            ),
        )
        assertNull(
            SensitiveScreenActionPolicy.blockedTapReason(
                node = node("비회원 예매확인", clickable = true),
                goal = "비회원 예매확인 화면을 열어줘",
            ),
        )
    }

    @Test
    fun finalPaymentIsBlockedButEnteringCheckoutIsAllowed() {
        assertNull(AgentActionBoundaryPolicy.blockedTapReason(node("구매하기")))
        assertNotNull(AgentActionBoundaryPolicy.blockedTapReason(node("결제하기")))
    }

    private fun snapshot(vararg nodes: UiNode) = UiSnapshot(
        packageName = "com.coupang.mobile",
        nodes = nodes.toList(),
    )

    private fun node(
        text: String,
        clickable: Boolean = false,
        password: Boolean = false,
        editable: Boolean = password,
        id: String = "node_$text",
    ) = UiNode(
        id = id,
        text = text,
        contentDescription = null,
        className = "android.widget.TextView",
        viewId = null,
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = true,
        checked = null,
        password = password,
        bounds = Rect(0, 0, 100, 100),
        depth = 0,
    )
}
