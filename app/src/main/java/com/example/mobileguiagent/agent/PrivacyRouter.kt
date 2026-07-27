package com.example.mobileguiagent.agent

import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot

/**
 * Routing is decided on-device before any screenshot or UI text is attached to
 * a cloud request. A missed detection cannot be repaired after transmission,
 * so ambiguous checkout/authentication screens deliberately fall toward local
 * processing or explicit user handoff.
 */
enum class PrivacyRoute {
    CLOUD_OK,
    LOCAL_ONLY,
    USER_HANDOFF,
}

enum class PrivacyReason {
    PASSWORD_FIELD,
    AUTHENTICATION_SCREEN,
    PAYMENT_CREDENTIALS,
    DELIVERY_DETAILS,
    PHONE_NUMBER,
    SENSITIVE_PACKAGE,
}

data class PrivacyDecision(
    val route: PrivacyRoute,
    val reasons: Set<PrivacyReason> = emptySet(),
    val sensitiveNodeIds: Set<String> = emptySet(),
)

object ScreenPrivacyRouter {
    fun route(snapshot: UiSnapshot): PrivacyDecision {
        val visibleNodes = snapshot.nodes.filter(UiNode::visibleToUser)
        val sensitiveNodeIds = linkedSetOf<String>()
        val reasons = linkedSetOf<PrivacyReason>()
        val screenLabels = visibleNodes.associateWith { node -> node.label().lowercase() }
        val packageName = snapshot.packageName.lowercase()
        // A launcher is a catalog of installed app names. Labels such as
        // "OTP", a bank name, or a payment app do not mean the current screen
        // contains credentials. Structural password fields remain protected.
        val catalogSurface = LAUNCHER_PACKAGE_MARKERS.any(packageName::contains)
        val checkoutContext = screenLabels.values.any { label ->
            CHECKOUT_CONTEXT_TERMS.any(label::contains)
        }

        screenLabels.forEach { (node, label) ->
            when {
                node.password -> {
                    sensitiveNodeIds += node.id
                    reasons += PrivacyReason.PASSWORD_FIELD
                }
                !catalogSurface && containsAuthenticationTerm(label) -> {
                    sensitiveNodeIds += node.id
                    reasons += PrivacyReason.AUTHENTICATION_SCREEN
                }
                !catalogSurface && PAYMENT_CREDENTIAL_TERMS.any(label::contains) -> {
                    sensitiveNodeIds += node.id
                    reasons += PrivacyReason.PAYMENT_CREDENTIALS
                }
                !catalogSurface &&
                    (
                        STRONG_DELIVERY_TERMS.any(label::contains) ||
                            ADDRESS_PATTERN.containsMatchIn(label) ||
                            (checkoutContext && "배송지" in label)
                        ) -> {
                    sensitiveNodeIds += node.id
                    reasons += PrivacyReason.DELIVERY_DETAILS
                }
                !catalogSurface && PHONE_PATTERN.containsMatchIn(label) -> {
                    sensitiveNodeIds += node.id
                    reasons += PrivacyReason.PHONE_NUMBER
                }
            }
        }

        if (SENSITIVE_PACKAGE_MARKERS.any(packageName::contains)) {
            reasons += PrivacyReason.SENSITIVE_PACKAGE
        }

        // Authentication screens can still contain reversible navigation such
        // as "비회원 로그인". Keep password/auth text entirely on-device and
        // let the restricted local planner choose only a safe navigation node.
        // Payment credentials remain an unconditional user handoff.
        val handoff = PrivacyReason.PAYMENT_CREDENTIALS in reasons
        return PrivacyDecision(
            route = when {
                handoff -> PrivacyRoute.USER_HANDOFF
                reasons.isNotEmpty() -> PrivacyRoute.LOCAL_ONLY
                else -> PrivacyRoute.CLOUD_OK
            },
            reasons = reasons,
            sensitiveNodeIds = sensitiveNodeIds,
        )
    }

    private fun UiNode.label(): String =
        listOfNotNull(text, contentDescription, viewId).joinToString(" ")

    private fun containsAuthenticationTerm(label: String): Boolean =
        AUTHENTICATION_PHRASES.any(label::contains) ||
            AUTHENTICATION_ENGLISH_TOKEN.containsMatchIn(label)

    private val AUTHENTICATION_PHRASES = listOf(
        "비밀번호",
        "인증번호",
        "보안카드",
        "일회용 비밀번호",
    )
    // Short tokens such as "pin" occur inside ordinary WebView IDs and words
    // (for example, "shopping" or "spinner"). Match them as words only.
    private val AUTHENTICATION_ENGLISH_TOKEN =
        Regex("""\b(?:otp|pin|password)\b""")
    private val PAYMENT_CREDENTIAL_TERMS = listOf(
        "카드번호",
        "계좌번호",
        "유효기간",
        "cvc",
        "cvv",
        "결제 비밀번호",
    )
    private val STRONG_DELIVERY_TERMS = listOf(
        "배송지 선택",
        "배송 정보",
        "받는 분",
        "받는사람",
        "수령인",
        "상세주소",
        "휴대폰 번호",
        "연락처",
    )
    private val CHECKOUT_CONTEXT_TERMS = listOf(
        "주문/결제",
        "주문서",
        "결제수단",
        "총 결제금액",
        "최종 결제",
    )
    private val SENSITIVE_PACKAGE_MARKERS = listOf(
        "bank",
        "banking",
        "card",
        "pay",
        "insurance",
        "hospital",
    )
    private val LAUNCHER_PACKAGE_MARKERS = listOf(
        "launcher",
        "quickstep",
    )
    private val PHONE_PATTERN =
        Regex("""(?<!\d)01[016789][-\s]?\d{3,4}[-\s]?\d{4}(?!\d)""")
    private val ADDRESS_PATTERN =
        Regex("""(?:[가-힣]+(?:시|도)\s*)?[가-힣]+(?:구|군|시)\s+[가-힣0-9]+(?:로|길|동)\b""")
}

/**
 * Local planning may navigate away from a private login screen, but it must
 * never submit an authentication action. Guest-mode tabs are navigation, not
 * authentication, and are deliberately allowed.
 */
object PrivateScreenActionPolicy {
    fun blockedLocalTapReason(
        node: UiNode?,
        goal: String = "",
    ): String? {
        val label = listOfNotNull(
            node?.text,
            node?.contentDescription,
            node?.viewId,
        ).joinToString(" ").lowercase().trim()
        if ("비회원 로그인" in label || "guest login" in label) return null
        if (
            goal.isNewBookingGoal() &&
            BOOKING_HISTORY_TERMS.any(label::contains)
        ) {
            return "새 예매 목표에서는 기존 예매 확인·조회 화면으로 이동하지 않습니다."
        }
        return if (
            label == "로그인" ||
            "간편로그인" in label ||
            "로 로그인" in label ||
            "본인인증" in label ||
            "인증하기" in label
        ) {
            "로그인 또는 본인인증 실행은 사용자가 직접 확인해야 합니다."
        } else {
            null
        }
    }

    private fun String.isNewBookingGoal(): Boolean {
        val normalized = lowercase()
        val bookingIntent = listOf(
            "예매",
            "예약",
            "book ",
            "booking",
            "reserve",
            "reservation",
        ).any(normalized::contains)
        val historyIntent = BOOKING_HISTORY_TERMS.any(normalized::contains)
        return bookingIntent && !historyIntent
    }

    private val BOOKING_HISTORY_TERMS = listOf(
        "예매확인",
        "예매 확인",
        "예약확인",
        "예약 확인",
        "주문내역",
        "주문 내역",
        "booking confirmation",
        "check booking",
        "reservation lookup",
        "order history",
    )
}

/**
 * Hard execution boundary independent of model prompts. Shopping exploration
 * and entering a checkout page are reversible; final order/payment actions are
 * never executed by either cloud or local planners in this PoC.
 */
object ShoppingActionPolicy {
    fun blockedTapReason(node: UiNode?): String? {
        val label = listOfNotNull(
            node?.text,
            node?.contentDescription,
            node?.viewId,
        ).joinToString(" ").lowercase()
        return blockedLabelReason(label)
    }

    fun blockedLabelReason(label: String): String? {
        val normalized = label.lowercase()
        return if (IRREVERSIBLE_TERMS.any(normalized::contains)) {
            "주문 확정 또는 결제 행동은 사용자가 직접 확인해야 합니다."
        } else {
            null
        }
    }

    private val IRREVERSIBLE_TERMS = listOf(
        "결제하기",
        "결제 및 주문",
        "주문 및 결제",
        "주문 완료",
        "구매 확정",
        "구매확정",
        "place order",
        "pay now",
    )
}
