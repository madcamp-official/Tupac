package com.example.mobileguiagent.cloud

import android.graphics.Rect
import com.example.mobileguiagent.credentials.CredentialFieldRole
import com.example.mobileguiagent.credentials.PublicCredentialDescriptor
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.model.AgentCapability
import com.example.mobileguiagent.model.RuntimeAction
import com.example.mobileguiagent.model.TaskContract
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialFlowPolicyTest {
    @Test
    fun screenChangedRequiresFreshObservationInsteadOfPlannerFallback() {
        val disposition = CredentialBrokerResultPolicy.disposition(
            DeviceToolResult.Error(
                code = "SCREEN_CHANGED",
                message = "stale",
            ),
        )

        assertEquals(
            CredentialBrokerDisposition.REOBSERVE_AND_RETRY,
            disposition,
        )
    }

    @Test
    fun otherStaleCredentialTargetsAlsoRequireFreshObservation() {
        listOf(
            "OBSERVE_UI_REQUIRED",
            "NODE_NOT_FOUND",
            "NODE_NOT_EDITABLE",
            "ACTION_TIMEOUT",
        ).forEach { code ->
            assertEquals(
                code,
                CredentialBrokerDisposition.REOBSERVE_AND_RETRY,
                CredentialBrokerResultPolicy.disposition(
                    DeviceToolResult.Error(code, "retry"),
                ),
            )
        }
    }

    @Test
    fun credentialResolutionFailureRequiresHandoffInsteadOfGuestFallback() {
        assertEquals(
            CredentialBrokerDisposition.USER_HANDOFF,
            CredentialBrokerResultPolicy.disposition(
                DeviceToolResult.Error(
                    code = "SECRET_DECRYPT_FAILED",
                    message = "unavailable",
                ),
            ),
        )
    }

    @Test
    fun selectedGuestTabIsDeterministicallyRestoredToMemberLogin() {
        val action = StoredCredentialFlowPolicy.memberLoginRecovery(
            contract = bookingContract(),
            snapshot = UiSnapshot(
                packageName = "com.megabox.mop",
                nodes = listOf(
                    node(
                        id = "member",
                        description = "회원 로그인",
                        clickable = true,
                        viewId = "com.megabox.mop:id/memberTab",
                    ),
                    node(
                        id = "guest",
                        description = "비회원 로그인",
                        clickable = true,
                        selected = true,
                    ),
                ),
            ),
            resources = credentials(),
        )

        assertNotNull(action)
        assertEquals("AUTH_RESTORE_MEMBER_LOGIN", action?.code)
        assertEquals(RuntimeAction.TapNode("member"), action?.action)
    }

    @Test
    fun structuralGuestFormIsRecoveredWhenTabSelectionStateIsUnavailable() {
        val action = StoredCredentialFlowPolicy.memberLoginRecovery(
            contract = bookingContract(),
            snapshot = UiSnapshot(
                packageName = "com.megabox.mop",
                nodes = listOf(
                    node(
                        id = "member",
                        description = "회원 로그인",
                        clickable = true,
                        viewId = "com.megabox.mop:id/memberTab",
                    ),
                    node(
                        id = "guest",
                        description = "비회원 로그인",
                        clickable = true,
                    ),
                    node(
                        id = "name",
                        description = "이름",
                        clickable = false,
                        editable = true,
                    ),
                    node(
                        id = "birth",
                        description = "생년월일",
                        clickable = false,
                        editable = true,
                    ),
                    node(
                        id = "guest_submit",
                        description = "",
                        clickable = true,
                        viewId = "com.megabox.mop:id/nonLoginBtn",
                    ),
                ),
            ),
            resources = credentials(),
        )

        assertEquals(RuntimeAction.TapNode("member"), action?.action)
    }

    @Test
    fun completeStoredCredentialsBlockUnrequestedGuestNavigation() {
        val reason = StoredCredentialFlowPolicy.blockedGuestNavigationReason(
            contract = bookingContract(),
            resources = credentials(),
            target = node(
                id = "guest",
                description = "비회원 로그인",
                clickable = true,
            ),
        )

        assertNotNull(reason)
    }

    @Test
    fun explicitGuestBookingKeepsGuestPathAvailable() {
        val contract = bookingContract(
            goal = "메가박스에서 비회원으로 예매해줘",
        )
        val guest = node(
            id = "guest",
            description = "비회원 로그인",
            clickable = true,
            selected = true,
        )

        assertNull(
            StoredCredentialFlowPolicy.blockedGuestNavigationReason(
                contract = contract,
                resources = credentials(),
                target = guest,
            ),
        )
        assertNull(
            StoredCredentialFlowPolicy.memberLoginRecovery(
                contract = contract,
                snapshot = UiSnapshot(
                    packageName = "com.megabox.mop",
                    nodes = listOf(guest),
                ),
                resources = credentials(),
            ),
        )
    }

    @Test
    fun incompleteCredentialPairDoesNotClaimStoredLoginIsAvailable() {
        val usernameOnly = credentials().filter { descriptor ->
            descriptor.role == CredentialFieldRole.USERNAME
        }

        assertNull(
            StoredCredentialFlowPolicy.blockedGuestNavigationReason(
                contract = bookingContract(),
                resources = usernameOnly,
                target = node(
                    id = "guest",
                    description = "비회원 로그인",
                    clickable = true,
                ),
            ),
        )
    }

    private fun bookingContract(
        goal: String = "메가박스에서 영화를 예매해줘",
    ) = TaskContract(
        originalGoal = goal,
        capabilities = setOf(AgentCapability.USE_STORED_CREDENTIALS),
        requiredSelections = emptySet(),
    )

    private fun credentials() = listOf(
        PublicCredentialDescriptor(
            id = "R_USERNAME",
            scopeAlias = "Megabox",
            role = CredentialFieldRole.USERNAME,
        ),
        PublicCredentialDescriptor(
            id = "R_PASSWORD",
            scopeAlias = "Megabox",
            role = CredentialFieldRole.PASSWORD,
        ),
    )

    private fun node(
        id: String,
        description: String,
        clickable: Boolean,
        selected: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        viewId: String? = null,
    ) = UiNode(
        id = id,
        text = null,
        contentDescription = description,
        className = "android.widget.TextView",
        viewId = viewId,
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = true,
        checked = null,
        selected = selected,
        password = password,
        bounds = Rect(0, 0, 100, 100),
        depth = 1,
    )
}
