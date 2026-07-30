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
    CLOUD_REDACTED,
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
        val catalogSurface =
            LAUNCHER_PACKAGE_MARKERS.any(packageName::contains) ||
                CONTROLLER_PACKAGE_MARKERS.any(packageName::contains)
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

        // Authentication and delivery screens may be planned in the cloud only
        // after editable and sensitive node text has been redacted on-device.
        // Payment credentials remain an unconditional user handoff.
        val handoff = PrivacyReason.PAYMENT_CREDENTIALS in reasons
        return PrivacyDecision(
            route = when {
                handoff -> PrivacyRoute.USER_HANDOFF
                reasons.isNotEmpty() -> PrivacyRoute.CLOUD_REDACTED
                else -> PrivacyRoute.CLOUD_OK
            },
            reasons = reasons,
            sensitiveNodeIds = sensitiveNodeIds,
        )
    }

    private fun UiNode.label(): String =
        listOfNotNull(text, contentDescription, hint, viewId).joinToString(" ")

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
    // The controller renders the user's goal and prior action summaries. Words
    // such as "로그인" or "카드번호" there describe the requested task; they
    // are not evidence that the current target app exposes an auth/payment
    // screen. Structural password fields remain protected by the first rule.
    private val CONTROLLER_PACKAGE_MARKERS = listOf(
        "com.example.mobileguiagent",
    )
    private val PHONE_PATTERN =
        Regex("""(?<!\d)01[016789][-\s]?\d{3,4}[-\s]?\d{4}(?!\d)""")
    private val ADDRESS_PATTERN =
        Regex("""(?:[가-힣]+(?:시|도)\s*)?[가-힣]+(?:구|군|시)\s+[가-힣0-9]+(?:로|길|동)\b""")
}

// 팀원 브랜치(agent/android-gui-agent-integration)의 같은 파일에서 가져왔다.
// 병합 때 충돌을 줄이려고 내용을 그대로 두었다.
//
// 원본의 SensitiveScreenActionPolicy는 가져오지 않았다. 두 가지 이유다.
// 하나는 로그인·본인인증 버튼 탭을 막는 부분인데, 이쪽은 절차서가 submit까지
// 하도록 정했다(로그인은 실패해도 다시 하면 되고, 주문·결제·회원가입은
// skills의 submit: no로 이미 사람에게 넘긴다). 다른 하나는 예매 이력 화면을
// 피하는 규칙으로, 그쪽 브랜치의 예매 도메인 전용이라 여기서는 쓸 곳이 없다.
