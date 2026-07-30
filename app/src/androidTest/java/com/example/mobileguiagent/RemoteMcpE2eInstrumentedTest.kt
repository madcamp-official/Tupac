package com.example.mobileguiagent

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mobileguiagent.credentials.AndroidKeystoreSecretStore
import com.example.mobileguiagent.remote.RemoteDeviceRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Manual production-gateway smoke test.
 *
 * This is gated so normal instrumentation runs never use a provisioned
 * account. Build/install the test APK without reinstalling the target app,
 * then pass `-e remoteMcpE2e true` to the instrumentation runner.
 */
@RunWith(AndroidJUnit4::class)
class RemoteMcpE2eInstrumentedTest {
    @Test
    fun listsToolsAndRoundTripsReadOnlyDeviceCalls() {
        assumeTrue(
            InstrumentationRegistry.getArguments()
                .getString(E2E_ARGUMENT)
                .toBoolean(),
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitUntilConnected()

        val accessToken = AndroidKeystoreSecretStore(context)
            .get(ACCESS_TOKEN_ID)
            ?: error("저장된 Supabase access token이 없습니다.")
        try {
            val tools = mcpRequest(
                accessToken,
                method = "tools/list",
                params = JSONObject(),
            ).getJSONObject("result").getJSONArray("tools")
            val toolNames = (0 until tools.length())
                .map { tools.getJSONObject(it).getString("name") }
                .toSet()
            assertTrue(toolNames.containsAll(EXPECTED_TOOLS))

            val statusRelay = relayResult(
                mcpRequest(
                    accessToken,
                    method = "tools/call",
                    params = JSONObject()
                        .put("name", "device_status")
                        .put("arguments", JSONObject()),
                ),
            )
            assertEquals("succeeded", statusRelay.getString("status"))
            val accessibilityConnected = statusRelay
                .getJSONObject("result")
                .optBoolean("accessibility_connected")

            val observeRelay = relayResult(
                mcpRequest(
                    accessToken,
                    method = "tools/call",
                    params = JSONObject()
                        .put("name", "device_observe")
                        .put("arguments", JSONObject().put("max_nodes", 10)),
                ),
            )
            if (accessibilityConnected) {
                assertEquals("succeeded", observeRelay.getString("status"))
                assertTrue(
                    observeRelay
                        .getJSONObject("result")
                        .getInt("returned_node_count") <= 10,
                )
            } else {
                assertEquals("denied", observeRelay.getString("status"))
                assertEquals(
                    "NO_ACTIVE_WINDOW",
                    observeRelay.getJSONObject("error").getString("code"),
                )
            }
        } finally {
            accessToken.fill('\u0000')
        }
    }

    private fun waitUntilConnected() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (RemoteDeviceRepository.state.value.connected) return
            Thread.sleep(250)
        }
        error("휴대폰이 클라우드 MCP WebSocket에 연결되지 않았습니다.")
    }

    private fun mcpRequest(
        accessToken: CharArray,
        method: String,
        params: JSONObject,
    ): JSONObject {
        val responseBody = HTTP_CLIENT.newCall(
            Request.Builder()
                .url("${BuildConfig.MCP_GATEWAY_URL.trimEnd('/')}/mcp")
                .header("Authorization", "Bearer ${String(accessToken)}")
                .header("Accept", "application/json, text/event-stream")
                .post(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", "android-e2e-$method")
                        .put("method", method)
                        .put("params", params)
                        .toString()
                        .toRequestBody(JSON_MEDIA_TYPE),
                )
                .build(),
        ).execute().use { response ->
            assertTrue("MCP HTTP status ${response.code}", response.isSuccessful)
            response.body.string()
        }
        val jsonRpc = jsonRpcPayload(responseBody)
        assertFalse(jsonRpc.has("error"))
        return jsonRpc
    }

    private fun relayResult(jsonRpc: JSONObject): JSONObject = JSONObject(
        jsonRpc
            .getJSONObject("result")
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text"),
    )

    private fun jsonRpcPayload(responseBody: String): JSONObject {
        val trimmed = responseBody.trim()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        val eventPayload = responseBody
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .firstOrNull { it.startsWith("{") && it.contains("\"jsonrpc\"") }
            ?: error("MCP JSON-RPC 응답을 찾지 못했습니다.")
        return JSONObject(eventPayload)
    }

    private companion object {
        const val E2E_ARGUMENT = "remoteMcpE2e"
        const val ACCESS_TOKEN_ID = "REMOTE_SUPABASE_ACCESS_TOKEN"
        val EXPECTED_TOOLS = setOf("device_status", "device_observe", "device_back")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val HTTP_CLIENT = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
