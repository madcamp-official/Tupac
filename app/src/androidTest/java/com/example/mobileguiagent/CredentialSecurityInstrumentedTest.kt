package com.example.mobileguiagent

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mobileguiagent.credentials.AndroidKeystoreSecretStore
import com.example.mobileguiagent.credentials.CredentialFieldRole
import com.example.mobileguiagent.credentials.LocalCredentialRepository
import com.example.mobileguiagent.credentials.SecretAccessResult
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.FillSecretDeviceTool
import com.example.mobileguiagent.device.SensitiveUiRedaction
import com.example.mobileguiagent.mcp.McpDeviceToolAdapter
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.remote.RemoteObservationRedactor
import com.example.mobileguiagent.remote.RemoteToolCatalog
import com.example.mobileguiagent.secret.SecretVault
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
    fun storedSecretCanBeReusedOnlyByItsMatchingPackage() {
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

            val reusable = LocalCredentialRepository.accessSecret(
                context,
                record.descriptor.id,
                "com.example.testtarget",
                targetIsPassword = false,
            )
            assertTrue(reusable is SecretAccessResult.Ready)
            (reusable as SecretAccessResult.Ready).characters.fill('\u0000')
        } finally {
            LocalCredentialRepository.delete(context, record.descriptor.id)
        }
    }

    @Test
    fun userFacingVaultAccountIsAvailableToPrivateFillBroker() {
        val packageName = "com.example.syntheticmegabox"
        try {
            assertTrue(
                SecretVault.putAccount(
                    context,
                    packageName,
                    "username",
                    "synthetic-user",
                ),
            )
            val descriptor = LocalCredentialRepository
                .publicCatalogForPackage(context, packageName)
                .single { it.role == CredentialFieldRole.USERNAME }
            assertTrue(descriptor.id.startsWith("SV_"))

            val allowed = LocalCredentialRepository.accessSecret(
                context,
                descriptor.id,
                packageName,
                targetIsPassword = false,
            )
            assertTrue(allowed is SecretAccessResult.Ready)
            val characters = (allowed as SecretAccessResult.Ready).characters
            try {
                assertEquals("synthetic-user", String(characters))
            } finally {
                characters.fill('\u0000')
            }
        } finally {
            SecretVault.removeService(context, packageName)
        }
    }

    @Test
    fun secretFillBrokerIsNotExposedThroughMcp() {
        val registry = DeviceToolRegistry()
        val mcpNames = McpDeviceToolAdapter(registry)
            .definitions()
            .map { it.getString("name") }
        assertFalse(mcpNames.any { it.contains("secret", ignoreCase = true) })
        assertEquals(null, RemoteToolCatalog.find("device_get_field"))
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
    fun filledSecretIsRedactedBeforeCloudObservation() {
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
        val serialized = RemoteObservationRedactor.redact(redacted).nodes.single().text.orEmpty()
        assertFalse(serialized.contains(secretValue))
        assertTrue(serialized.contains("SENSITIVE_VALUE_REDACTED"))
    }

    @Test
    fun everyFilledCredentialFieldRemainsRedactedAcrossSequentialFills() {
        val packageName = "com.example.synthetic.multifield"
        val username = editableNode(
            id = "node_3",
            viewId = "$packageName:id/username",
            bounds = Rect(10, 20, 300, 90),
            password = false,
        )
        val password = editableNode(
            id = "node_4",
            viewId = "$packageName:id/password",
            bounds = Rect(10, 110, 300, 180),
            password = true,
        )
        SensitiveUiRedaction.markFilledField(packageName, username)
        SensitiveUiRedaction.markFilledField(packageName, password)

        val redacted = SensitiveUiRedaction.redact(
            UiSnapshot(
                packageName = packageName,
                nodes = listOf(
                    username.copy(text = "synthetic-user"),
                    password.copy(text = "synthetic-password"),
                ),
            ),
        )

        assertEquals(
            listOf("[LOCAL_VALUE_REDACTED]", "[LOCAL_VALUE_REDACTED]"),
            redacted.nodes.map(UiNode::text),
        )
    }

    private fun editableNode(
        id: String,
        viewId: String,
        bounds: Rect,
        password: Boolean,
    ): UiNode = UiNode(
        id = id,
        text = "",
        contentDescription = null,
        className = "android.widget.EditText",
        viewId = viewId,
        clickable = true,
        editable = true,
        scrollable = false,
        enabled = true,
        checked = null,
        selected = false,
        password = password,
        bounds = bounds,
        depth = 2,
    )

}
