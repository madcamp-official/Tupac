package com.example.mobileguiagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.mcp.McpDeviceToolAdapter
import com.example.mobileguiagent.mcp.PocketMcpHttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PocketMcpToolCatalogInstrumentedTest {
    @Test
    fun toolsListHasOneSnapshotBoundClickNodeWhileAdapterDispatchRemainsAvailable() {
        val server = PocketMcpHttpServer(
            port = 0,
            authToken = "test-token",
            onRequest = {},
        )
        val tools = server.toolsListResult().getJSONArray("tools")
        val definitions = (0 until tools.length()).map(tools::getJSONObject)
        val names = definitions.map { definition -> definition.getString("name") }

        assertEquals("tools/list must not contain duplicate names", names.toSet().size, names.size)

        val clickDefinitions = definitions.filter { definition ->
            definition.getString("name") == McpDeviceToolAdapter.EXTERNAL_CLICK_NODE_NAME
        }
        assertEquals(1, clickDefinitions.size)
        val required = clickDefinitions.single()
            .getJSONObject("inputSchema")
            .getJSONArray("required")
        val requiredNames = (0 until required.length()).map(required::getString).toSet()
        assertTrue(requiredNames.containsAll(setOf("snapshot_id", "node_id")))

        val adapter = McpDeviceToolAdapter(DeviceToolRegistry())
        assertTrue(adapter.handles(McpDeviceToolAdapter.EXTERNAL_CLICK_NODE_NAME))
        val dispatchResult = adapter.call(
            McpDeviceToolAdapter.EXTERNAL_CLICK_NODE_NAME,
            JSONObject(),
        )
        assertTrue(dispatchResult.getBoolean("isError"))
        val dispatchPayload = JSONObject(
            dispatchResult
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text"),
        )
        assertEquals("MISSING_NODE_ID", dispatchPayload.getString("error"))
        assertFalse(dispatchPayload.getBoolean("success"))
    }
}
