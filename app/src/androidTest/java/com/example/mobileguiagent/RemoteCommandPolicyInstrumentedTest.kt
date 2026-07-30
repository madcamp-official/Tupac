package com.example.mobileguiagent

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.remote.RemoteCommandPolicy
import com.example.mobileguiagent.remote.RemoteObservationRedactor
import com.example.mobileguiagent.remote.RemotePolicyDecision
import com.example.mobileguiagent.remote.RemotePolicyRequest
import com.example.mobileguiagent.remote.RemoteToolCatalog
import com.example.mobileguiagent.remote.RemoteToolScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteCommandPolicyInstrumentedTest {
    @Test
    fun unknownAndSecretReturningToolsAreNotRemotelyCallable() {
        assertEquals(null, RemoteToolCatalog.find("device_get_field"))
        val decision = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_get_field",
                grantedScopes = RemoteToolScope.ALL,
            ),
        )
        assertDenied(decision, "REMOTE_TOOL_NOT_ALLOWED")
    }

    @Test
    fun requiredOauthScopeIsEnforcedBeforeExecution() {
        val decision = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_observe",
                grantedScopes = setOf(RemoteToolScope.STATUS),
                currentSnapshot = snapshot(node("상품 목록")),
            ),
        )
        assertDenied(decision, "MISSING_SCOPE")
    }

    @Test
    fun sensitiveObservationIsAllowedOnlyWithLocalRedaction() {
        val current = snapshot(node("비밀번호", password = true))
        val decision = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_observe",
                grantedScopes = setOf(RemoteToolScope.OBSERVE),
                currentSnapshot = current,
            ),
        )
        assertTrue(decision is RemotePolicyDecision.AllowRedacted)

        val redacted = RemoteObservationRedactor.redact(current)
        assertFalse(redacted.nodes.single().text.orEmpty().contains("비밀번호"))
        assertEquals(null, redacted.nodes.single().viewId)
    }

    @Test
    fun editableDraftTextIsRedactedOnSensitiveScreenEvenWhenItLooksHarmless() {
        val editableDraft = node(
            "user-entered-draft",
            id = "draft",
            editable = true,
        )
        val current = snapshot(node("카드번호"), editableDraft)

        val redacted = RemoteObservationRedactor.redact(current)
        val outgoingDraft = redacted.nodes.first { it.id == "draft" }

        assertFalse(outgoingDraft.text.orEmpty().contains("user-entered-draft"))
        assertEquals(null, outgoingDraft.viewId)
    }

    @Test
    fun sensitiveScreenshotIsNeverReturned() {
        val decision = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_screenshot",
                grantedScopes = setOf(RemoteToolScope.OBSERVE),
                currentSnapshot = snapshot(node("카드번호")),
            ),
        )
        assertDenied(decision, "SENSITIVE_SCREEN")
    }

    @Test
    fun paymentConfirmationClickIsBlockedInCode() {
        val observed = snapshot(node("결제하기", id = "pay", clickable = true))
        val decision = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_click_node",
                arguments = JSONObject()
                    .put("snapshot_id", observed.fingerprint.hash)
                    .put("node_id", "pay"),
                grantedScopes = setOf(RemoteToolScope.CONTROL),
                currentSnapshot = observed,
                observedSnapshot = observed,
            ),
        )
        assertDenied(decision, "IRREVERSIBLE_ACTION")
    }

    @Test
    fun locallyValidatedStoredLoginCanClickOnlyTheLoginButton() {
        val observed = snapshot(node("로그인", id = "login", clickable = true))
        val withoutStoredCredentialValidation = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_click_node",
                arguments = JSONObject()
                    .put("snapshot_id", observed.fingerprint.hash)
                    .put("node_id", "login"),
                grantedScopes = setOf(RemoteToolScope.CONTROL),
                currentSnapshot = observed,
                observedSnapshot = observed,
            ),
        )
        assertDenied(withoutStoredCredentialValidation, "AUTH_ACTION_BLOCKED")

        val approved = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_click_node",
                arguments = JSONObject()
                    .put("snapshot_id", observed.fingerprint.hash)
                    .put("node_id", "login"),
                grantedScopes = setOf(RemoteToolScope.CONTROL),
                currentSnapshot = observed,
                observedSnapshot = observed,
                locallyConfirmed = true,
            ),
        )
        assertTrue(approved is RemotePolicyDecision.Allow)
    }

    @Test
    fun sensitiveToolsNeedScopeAndShortLivedLocalConfirmation() {
        val withoutConfirmation = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_open_settings",
                grantedScopes = setOf(RemoteToolScope.SENSITIVE),
            ),
        )
        assertDenied(withoutConfirmation, "USER_CONFIRMATION_REQUIRED")

        val confirmed = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_open_settings",
                grantedScopes = setOf(RemoteToolScope.SENSITIVE),
                locallyConfirmed = true,
            ),
        )
        assertTrue(confirmed is RemotePolicyDecision.Allow)
    }

    @Test
    fun locallyApprovedCredentialFillCanTargetPasswordFieldOnPrivateScreen() {
        val observed = snapshot(node("비밀번호", id = "password", password = true))
        val decision = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_fill_field",
                arguments = JSONObject()
                    .put("snapshot_id", observed.fingerprint.hash)
                    .put("node_id", "password")
                    .put("field", "password"),
                grantedScopes = setOf(RemoteToolScope.SENSITIVE),
                currentSnapshot = observed,
                observedSnapshot = observed,
                locallyConfirmed = true,
            ),
        )
        assertTrue(decision is RemotePolicyDecision.Allow)
    }

    @Test
    fun staleSnapshotCannotAuthorizeNodeAction() {
        val observed = snapshot(node("다음", id = "next", clickable = true))
        val decision = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_click_node",
                arguments = JSONObject()
                    .put("snapshot_id", "stale")
                    .put("node_id", "next"),
                grantedScopes = setOf(RemoteToolScope.CONTROL),
                currentSnapshot = observed,
                observedSnapshot = observed,
            ),
        )
        assertDenied(decision, "REMOTE_TARGET_NOT_FOUND")
    }

    @Test
    fun scrollMustTargetAnExplicitObservedSnapshotNode() {
        val observed = snapshot(node("목록", id = "list"))
        val missingLease = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_scroll",
                arguments = JSONObject()
                    .put("direction", "down")
                    .put("node_id", "list"),
                grantedScopes = setOf(RemoteToolScope.CONTROL),
                currentSnapshot = observed,
                observedSnapshot = observed,
            ),
        )
        assertDenied(missingLease, "REMOTE_TARGET_NOT_FOUND")

        val allowed = RemoteCommandPolicy.evaluate(
            RemotePolicyRequest(
                toolName = "device_scroll",
                arguments = JSONObject()
                    .put("snapshot_id", observed.fingerprint.hash)
                    .put("direction", "down")
                    .put("node_id", "list"),
                grantedScopes = setOf(RemoteToolScope.CONTROL),
                currentSnapshot = observed,
                observedSnapshot = observed,
            ),
        )
        assertTrue(allowed is RemotePolicyDecision.Allow)
    }

    private fun assertDenied(decision: RemotePolicyDecision, code: String) {
        assertTrue(decision is RemotePolicyDecision.Deny)
        assertEquals(code, (decision as RemotePolicyDecision.Deny).code)
    }

    private fun snapshot(vararg nodes: UiNode) = UiSnapshot(
        packageName = "com.example.target",
        nodes = nodes.toList(),
    )

    private fun node(
        text: String,
        id: String = "node_$text",
        clickable: Boolean = false,
        password: Boolean = false,
        editable: Boolean = password,
    ) = UiNode(
        id = id,
        text = text,
        contentDescription = null,
        hint = null,
        className = "android.widget.TextView",
        viewId = "com.example.target:id/$id",
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
