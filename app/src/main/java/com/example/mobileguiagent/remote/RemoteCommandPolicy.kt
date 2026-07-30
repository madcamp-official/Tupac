package com.example.mobileguiagent.remote

import com.example.mobileguiagent.agent.SensitiveScreenActionPolicy
import com.example.mobileguiagent.agent.PrivacyRoute
import com.example.mobileguiagent.agent.ScreenPrivacyRouter
import com.example.mobileguiagent.model.AgentActionBoundaryPolicy
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import org.json.JSONObject

data class RemotePolicyRequest(
    val toolName: String,
    val arguments: JSONObject = JSONObject(),
    val grantedScopes: Set<String>,
    val currentSnapshot: UiSnapshot? = null,
    val observedSnapshot: UiSnapshot? = null,
    val locallyConfirmed: Boolean = false,
)

sealed interface RemotePolicyDecision {
    data object Allow : RemotePolicyDecision

    /** The UI tree may be returned only after local redaction. */
    data class AllowRedacted(
        val reason: String,
    ) : RemotePolicyDecision

    data class Deny(
        val code: String,
        val message: String,
    ) : RemotePolicyDecision
}

/**
 * Non-bypassable policy for commands originating outside the Android process.
 *
 * Transport authentication answers who may ask. This policy answers whether
 * the requested action may execute on the current screen.
 */
object RemoteCommandPolicy {
    fun evaluate(request: RemotePolicyRequest): RemotePolicyDecision {
        val spec = RemoteToolCatalog.find(request.toolName)
            ?: return RemotePolicyDecision.Deny(
                code = "REMOTE_TOOL_NOT_ALLOWED",
                message = "원격 실행이 허용되지 않은 도구입니다: ${request.toolName}",
            )
        if (spec.requiredScope !in request.grantedScopes) {
            return RemotePolicyDecision.Deny(
                code = "MISSING_SCOPE",
                message = "필요한 권한이 없습니다: ${spec.requiredScope}",
            )
        }
        if (spec.requiresLocalConfirmation && !request.locallyConfirmed) {
            return RemotePolicyDecision.Deny(
                code = "USER_CONFIRMATION_REQUIRED",
                message = "이 작업은 휴대폰에서 사용자가 직접 승인해야 합니다.",
            )
        }

        val current = request.currentSnapshot
        if (spec.needsCurrentSnapshot && current == null) {
            return RemotePolicyDecision.Deny(
                code = "NO_ACTIVE_WINDOW",
                message = "현재 Android 화면을 확인할 수 없어 원격 작업을 거부했습니다.",
            )
        }
        if (current == null) return RemotePolicyDecision.Allow

        val privacy = ScreenPrivacyRouter.route(current)
        if (spec.returnsScreenshot && privacy.route != PrivacyRoute.CLOUD_OK) {
            return RemotePolicyDecision.Deny(
                code = "SENSITIVE_SCREEN",
                message = "민감한 화면의 스크린샷은 기기 밖으로 보낼 수 없습니다.",
            )
        }
        if (spec.returnsUiTree && privacy.route != PrivacyRoute.CLOUD_OK) {
            return RemotePolicyDecision.AllowRedacted(
                reason = "민감한 화면이므로 UI 텍스트를 로컬에서 마스킹합니다.",
            )
        }

        if (privacy.route != PrivacyRoute.CLOUD_OK) {
            when (spec.risk) {
                RemoteToolRisk.READ,
                RemoteToolRisk.OBSERVE,
                RemoteToolRisk.REVERSIBLE,
                -> Unit
                RemoteToolRisk.WRITE,
                RemoteToolRisk.SENSITIVE,
                -> {
                    // A node-targeted click can still be proven safe below.
                    // Credential filling is also allowed on a private login
                    // screen when the executor verified a package-bound stored
                    // credential for the requested field.
                    val packageBoundCredentialFill =
                        request.toolName == "device_fill_field" &&
                            spec.targetsSnapshotNode &&
                            request.locallyConfirmed
                    if (
                        (!spec.targetsSnapshotNode || request.toolName != "device_click_node") &&
                        !packageBoundCredentialFill
                    ) {
                        return RemotePolicyDecision.Deny(
                            code = "SENSITIVE_SCREEN_WRITE_BLOCKED",
                            message = "민감한 화면에서는 이 원격 작업을 실행할 수 없습니다.",
                        )
                    }
                }
            }
        }

        if (spec.targetsSnapshotNode) {
            val target = targetNode(request)
                ?: return RemotePolicyDecision.Deny(
                    code = "REMOTE_TARGET_NOT_FOUND",
                    message = "최근 관찰 결과에서 대상 노드를 확인할 수 없습니다.",
                )
            // The irreversible-action boundary applies to activating a
            // control, not to replacing text inside an editor. Looking at the
            // editor's old value here can otherwise make a safe correction
            // impossible (for example replacing a draft that mentions a
            // payment-boundary button).
            if (request.toolName == "device_click_node") {
                AgentActionBoundaryPolicy.blockedTapReason(target)?.let { reason ->
                    return RemotePolicyDecision.Deny("IRREVERSIBLE_ACTION", reason)
                }
            }
            SensitiveScreenActionPolicy.blockedTapReason(target)?.let { reason ->
                val packageBoundLoginSubmission =
                    request.toolName == "device_click_node" &&
                        request.locallyConfirmed &&
                        isLoginSubmissionTarget(target)
                if (!packageBoundLoginSubmission) {
                    return RemotePolicyDecision.Deny("AUTH_ACTION_BLOCKED", reason)
                }
            }
        }
        return RemotePolicyDecision.Allow
    }

    private fun targetNode(request: RemotePolicyRequest): UiNode? {
        val nodeId = request.arguments.optString("node_id")
        if (nodeId.isBlank()) return null
        val observed = request.observedSnapshot ?: return null
        val requestedSnapshotId = request.arguments.optString("snapshot_id")
        if (
            requestedSnapshotId.isBlank() ||
            requestedSnapshotId != observed.fingerprint.hash
        ) {
            return null
        }
        return observed.nodes.firstOrNull { node -> node.id == nodeId }
    }

    private fun isLoginSubmissionTarget(node: UiNode): Boolean {
        if (!node.clickable || !node.enabled) return false
        val text = node.text.orEmpty().trim().lowercase()
        val viewId = node.viewId.orEmpty().lowercase()
        return text == "로그인" ||
            text == "login" ||
            viewId.endsWith("loginbtn") ||
            viewId.endsWith("login_button")
    }
}
