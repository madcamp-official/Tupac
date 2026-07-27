package com.example.mobileguiagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.GoBackDeviceTool
import com.example.mobileguiagent.device.WaitDeviceTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceToolRegistryInstrumentedTest {
    private val registry = DeviceToolRegistry()

    @Test
    fun registryIncludesGenericBackAndWaitTools() {
        val definitions = registry.definitions.associateBy { definition -> definition.name }

        assertTrue(definitions.containsKey(GoBackDeviceTool.NAME))
        assertTrue(definitions.containsKey(WaitDeviceTool.NAME))
        assertFalse(
            definitions.getValue(WaitDeviceTool.NAME)
                .inputSchema
                .optJSONArray("required")
                ?.let { required ->
                    (0 until required.length()).any { index ->
                        required.optString(index) == "duration_ms"
                    }
                } == true,
        )

        val durationSchema = definitions.getValue(WaitDeviceTool.NAME)
            .inputSchema
            .getJSONObject("properties")
            .getJSONObject("duration_ms")
        assertEquals(300L, durationSchema.getLong("minimum"))
        assertEquals(5_000L, durationSchema.getLong("maximum"))
        assertEquals(1_200L, durationSchema.getLong("default"))
    }

    @Test
    fun waitRejectsDurationOutsideSchemaRange() {
        val result = registry.execute(
            DeviceToolCall(
                name = WaitDeviceTool.NAME,
                arguments = JSONObject().put("duration_ms", 299),
            ),
        )

        assertTrue(result is DeviceToolResult.Error)
        assertEquals("INVALID_DURATION", (result as DeviceToolResult.Error).code)
    }

    @Test
    fun waitReturnsSuccessfulActionAfterRequestedDuration() {
        val startedAt = System.nanoTime()
        val result = registry.execute(
            DeviceToolCall(
                name = WaitDeviceTool.NAME,
                arguments = JSONObject().put("duration_ms", 300),
            ),
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(result is DeviceToolResult.Action)
        assertEquals(WaitDeviceTool.NAME, (result as DeviceToolResult.Action).action)
        assertTrue(result.success)
        assertTrue("elapsedMs=$elapsedMs", elapsedMs >= 280)
    }
}
