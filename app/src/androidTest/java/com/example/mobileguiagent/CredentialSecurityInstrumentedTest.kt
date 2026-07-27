package com.example.mobileguiagent

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mobileguiagent.cloud.GeminiApiClient
import com.example.mobileguiagent.credentials.AndroidKeystoreSecretStore
import com.example.mobileguiagent.credentials.CredentialFieldRole
import com.example.mobileguiagent.credentials.LocalCredentialRepository
import com.example.mobileguiagent.credentials.PublicCredentialDescriptor
import com.example.mobileguiagent.credentials.SecretAccessResult
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.FillSecretDeviceTool
import com.example.mobileguiagent.device.SensitiveUiRedaction
import com.example.mobileguiagent.mcp.McpDeviceToolAdapter
import com.example.mobileguiagent.model.LocalDeviceToolAdapter
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class CredentialSecurityInstrumentedTest {
    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun keystoreCiphertextRoundTripDoesNotPersistPlaintext() {
        val store = AndroidKeystoreSecretStore(context)
        val id = "TEST_${System.nanoTime()}"
        val value = "synthetic-password-${System.nanoTime()}"
        val characters = value.toCharArray()
        try {
            store.put(id, characters)
            val encrypted = requireNotNull(store.encryptedPayloadForTest(id))
            assertFalse(encrypted.contains(value))
            val restored = requireNotNull(store.get(id))
            try {
                assertEquals(value, String(restored))
            } finally {
                restored.fill('\u0000')
            }
        } finally {
            characters.fill('\u0000')
            store.remove(id)
        }
    }

    @Test
    fun secretUseRequiresMatchingPackageAndOneTimeGrant() {
        val secret = "synthetic-user".toCharArray()
        val record = try {
            LocalCredentialRepository.save(
                context = context,
                scopeAlias = "test_service",
                allowedPackage = "com.example.testtarget",
                role = CredentialFieldRole.USERNAME,
                secret = secret,
            )
        } finally {
            secret.fill('\u0000')
        }
        try {
            val withoutGrant = LocalCredentialRepository.accessSecret(
                context,
                record.descriptor.id,
                "com.example.testtarget",
                targetIsPassword = false,
            )
            assertTrue(withoutGrant is SecretAccessResult.Denied)

            assertTrue(
                LocalCredentialRepository.grantOneTimeUse(
                    context,
                    record.descriptor.id,
                ),
            )
            val wrongPackage = LocalCredentialRepository.accessSecret(
                context,
                record.descriptor.id,
                "com.example.other",
                targetIsPassword = false,
            )
            assertTrue(wrongPackage is SecretAccessResult.Denied)

            val allowed = LocalCredentialRepository.accessSecret(
                context,
                record.descriptor.id,
                "com.example.testtarget",
                targetIsPassword = false,
            )
            assertTrue(allowed is SecretAccessResult.Ready)
            (allowed as SecretAccessResult.Ready).characters.fill('\u0000')

            val consumed = LocalCredentialRepository.accessSecret(
                context,
                record.descriptor.id,
                "com.example.testtarget",
                targetIsPassword = false,
            )
            assertTrue(consumed is SecretAccessResult.Denied)
        } finally {
            LocalCredentialRepository.delete(context, record.descriptor.id)
            LocalCredentialRepository.clearApprovalsForTest()
        }
    }

    @Test
    fun localToolIsRegisteredButMcpDoesNotExposeIt() {
        val registry = DeviceToolRegistry()
        val localPrompt = LocalDeviceToolAdapter(registry).promptSection()
        val mcpNames = McpDeviceToolAdapter(registry)
            .definitions()
            .map { it.getString("name") }
        assertTrue(localPrompt.contains(FillSecretDeviceTool.NAME))
        assertTrue(localPrompt.contains("node_id and secret_ref are different"))
        assertFalse(mcpNames.any { it.contains("secret", ignoreCase = true) })
    }

    @Test
    fun credentialReferenceCannotBeUsedAsNodeId() {
        val result = DeviceToolRegistry().execute(
            DeviceToolCall(
                FillSecretDeviceTool.NAME,
                JSONObject()
                    .put("node_id", "R_NOT_A_UI_NODE")
                    .put("secret_ref", "R_NOT_A_UI_NODE"),
            ),
        )
        assertTrue(result is DeviceToolResult.Error)
        assertEquals("INVALID_NODE_ID", (result as DeviceToolResult.Error).code)
    }

    @Test
    fun filledSecretIsRedactedFromLaterModelObservation() {
        val secretValue = "synthetic-secret-that-must-not-reenter-the-model"
        val beforeFill = UiNode(
            id = "node_7",
            text = "",
            contentDescription = null,
            className = "android.widget.EditText",
            viewId = "com.example.target:id/username",
            clickable = true,
            editable = true,
            scrollable = false,
            enabled = true,
            checked = null,
            bounds = Rect(10, 20, 300, 90),
            depth = 2,
        )
        SensitiveUiRedaction.markFilledField(
            packageName = "com.example.target",
            node = beforeFill,
        )
        val redacted = SensitiveUiRedaction.redact(
            UiSnapshot(
                packageName = "com.example.target",
                nodes = listOf(beforeFill.copy(text = secretValue)),
            ),
        )
        val serialized = LocalDeviceToolAdapter()
            .resultJson(DeviceToolResult.UiObservation(redacted))
            .toString()
        assertFalse(serialized.contains(secretValue))
        assertTrue(serialized.contains("LOCAL_VALUE_REDACTED"))
    }

    @Test
    fun geminiSelectorPayloadContainsOnlyPublicDescriptor() {
        val descriptor = PublicCredentialDescriptor(
            id = "R_OPAQUE_TEST",
            scopeAlias = "shopping_account",
            role = CredentialFieldRole.PASSWORD,
        )
        val payload = GeminiApiClient()
            .buildCredentialSelectionRequest(
                goal = "쇼핑 앱에 로그인해",
                resources = listOf(descriptor),
            )
            .toString()
        assertTrue(payload.contains("R_OPAQUE_TEST"))
        assertTrue(payload.contains("shopping_account"))
        assertFalse(payload.contains("allowed_package"))
        assertFalse(payload.contains("password_value"))
    }
}
