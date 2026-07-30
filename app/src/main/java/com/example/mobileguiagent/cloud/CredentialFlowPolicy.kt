package com.example.mobileguiagent.cloud

import com.example.mobileguiagent.credentials.CredentialFieldRole
import com.example.mobileguiagent.credentials.PublicCredentialDescriptor
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.model.AgentCapability
import com.example.mobileguiagent.model.RuntimeAction
import com.example.mobileguiagent.model.TaskContract
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot

internal enum class CredentialBrokerDisposition {
    SUCCEEDED,
    REOBSERVE_AND_RETRY,
    USER_HANDOFF,
    FAIL,
}

/**
 * Credential actions are owned by the deterministic on-device broker. A
 * recoverable stale observation must never hand control to the cloud planner
 * in the same turn.
 */
internal object CredentialBrokerResultPolicy {
    fun disposition(result: DeviceToolResult): CredentialBrokerDisposition = when (result) {
        is DeviceToolResult.Action ->
            if (result.success) {
                CredentialBrokerDisposition.SUCCEEDED
            } else {
                CredentialBrokerDisposition.REOBSERVE_AND_RETRY
            }

        is DeviceToolResult.Error -> when {
            result.code in CREDENTIAL_RESOLUTION_ERRORS ->
                CredentialBrokerDisposition.USER_HANDOFF
            result.code in REOBSERVE_ERRORS ->
                CredentialBrokerDisposition.REOBSERVE_AND_RETRY
            else ->
                CredentialBrokerDisposition.FAIL
        }

        else -> CredentialBrokerDisposition.FAIL
    }

    private val CREDENTIAL_RESOLUTION_ERRORS = setOf(
        "UNKNOWN_SECRET_REF",
        "SECRET_PACKAGE_DENIED",
        "SECRET_FIELD_MISMATCH",
        "SECRET_DECRYPT_FAILED",
    )
    private val REOBSERVE_ERRORS = setOf(
        "SCREEN_CHANGED",
        "OBSERVE_UI_REQUIRED",
        "NODE_NOT_FOUND",
        "NODE_NOT_EDITABLE",
        "ACTION_TIMEOUT",
    )
}

internal data class DeterministicCredentialAction(
    val code: String,
    val action: RuntimeAction,
)

/**
 * Keeps an authorized stored-credential flow on the member-login path. Guest
 * mode remains available when the user explicitly requested it or a complete
 * package-bound username/password pair is not available.
 */
internal object StoredCredentialFlowPolicy {
    fun memberLoginRecovery(
        contract: TaskContract,
        snapshot: UiSnapshot,
        resources: List<PublicCredentialDescriptor>,
    ): DeterministicCredentialAction? {
        if (!mustUseStoredCredentials(contract, resources)) return null
        val selectedGuest = snapshot.nodes.any { node ->
            node.visibleToUser &&
                node.enabled &&
                node.selected &&
                node.isGuestTarget()
        }
        val memberLogin = snapshot.nodes.firstOrNull { node ->
            node.visibleToUser &&
                node.enabled &&
                node.clickable &&
                node.isMemberLoginTarget()
        } ?: return null
        val structuralGuestForm =
            snapshot.nodes.count { node ->
                node.visibleToUser &&
                    node.enabled &&
                    node.editable &&
                    !node.password
            } >= MIN_GUEST_FORM_EDITORS &&
                snapshot.nodes.none { node ->
                    node.visibleToUser &&
                        node.enabled &&
                        node.editable &&
                        node.password
                } &&
                snapshot.nodes.any { node ->
                    node.visibleToUser &&
                        node.enabled &&
                        node.isGuestTarget()
                }
        if (!selectedGuest && !structuralGuestForm) return null
        return DeterministicCredentialAction(
            code = "AUTH_RESTORE_MEMBER_LOGIN",
            action = RuntimeAction.TapNode(memberLogin.id),
        )
    }

    fun blockedGuestNavigationReason(
        contract: TaskContract,
        resources: List<PublicCredentialDescriptor>,
        target: UiNode?,
    ): String? {
        if (!mustUseStoredCredentials(contract, resources)) return null
        if (target?.isGuestTarget() != true) return null
        return "저장된 패키지 전용 로그인 정보가 준비되어 있어 비회원 경로로 " +
            "전환하지 않습니다. 회원 로그인 화면에서 보안 입력을 다시 시도하세요."
    }

    private fun mustUseStoredCredentials(
        contract: TaskContract,
        resources: List<PublicCredentialDescriptor>,
    ): Boolean =
        contract.allows(AgentCapability.USE_STORED_CREDENTIALS) &&
            !isExplicitGuestGoal(contract.originalGoal) &&
            resources.hasCompleteLoginCredential()

    private fun List<PublicCredentialDescriptor>.hasCompleteLoginCredential(): Boolean {
        val hasUsername = any { resource ->
            resource.role == CredentialFieldRole.USERNAME
        }
        val hasPassword = any { resource ->
            resource.role == CredentialFieldRole.PASSWORD ||
                resource.role == CredentialFieldRole.GENERIC_SECRET
        }
        return hasUsername && hasPassword
    }

    private fun isExplicitGuestGoal(goal: String): Boolean {
        val normalized = goal.lowercase()
        return EXPLICIT_GUEST_PATTERNS.any { pattern ->
            pattern.containsMatchIn(normalized)
        }
    }

    private fun UiNode.isGuestTarget(): Boolean {
        return normalizedLabels().any { label ->
            "비회원" in label ||
                "게스트" in label ||
                "guest" in label ||
                "nonlogin" in label ||
                "nomember" in label
        }
    }

    private fun UiNode.isMemberLoginTarget(): Boolean {
        if (isGuestTarget()) return false
        return normalizedLabels().any { label ->
            label == "회원로그인" ||
                label == "memberlogin" ||
                label == "signin" ||
                label == "login"
        }
    }

    private fun UiNode.normalizedLabels(): List<String> =
        listOfNotNull(text, contentDescription, hint, viewId)
            .map { value ->
                value.lowercase().filter(Char::isLetterOrDigit)
            }
            .filter(String::isNotBlank)

    private val EXPLICIT_GUEST_PATTERNS = listOf(
        Regex("""(?:비회원|게스트)\s*(?:으로|로)?\s*(?:로그인|예매|예약|진행)"""),
        Regex("""(?:로그인|계정)\s*(?:하지\s*않고|없이)"""),
        Regex("""\b(?:as|using)\s+(?:a\s+)?guest\b"""),
        Regex("""\bguest\s+(?:login|checkout|booking|reservation)\b"""),
        Regex("""\bwithout\s+(?:an?\s+)?(?:account|login|signing\s+in)\b"""),
    )
    private const val MIN_GUEST_FORM_EDITORS = 2
}
