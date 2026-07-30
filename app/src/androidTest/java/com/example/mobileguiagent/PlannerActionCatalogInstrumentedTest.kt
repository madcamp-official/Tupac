package com.example.mobileguiagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mobileguiagent.cloud.GeminiPlannerAction
import com.example.mobileguiagent.cloud.PlannerActionCatalog
import com.example.mobileguiagent.device.SetTextDeviceTool
import com.example.mobileguiagent.device.SwipeDeviceTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerActionCatalogInstrumentedTest {
    @Test
    fun swipeRequiresAllFourCoordinates() {
        assertThrows(IllegalStateException::class.java) {
            PlannerActionCatalog.toDeviceToolCall(
                action = GeminiPlannerAction(
                    action = PlannerActionCatalog.SWIPE,
                    x = 100.0,
                    y = 800.0,
                    endY = 200.0,
                ),
                screenWidth = 1_000,
                screenHeight = 2_000,
                screenElements = emptyList(),
            )
        }
    }

    @Test
    fun swipeConvertsEveryNormalizedCoordinateIndependently() {
        val call = PlannerActionCatalog.toDeviceToolCall(
            action = GeminiPlannerAction(
                action = PlannerActionCatalog.SWIPE,
                x = 100.0,
                y = 800.0,
                endX = 900.0,
                endY = 200.0,
            ),
            screenWidth = 1_001,
            screenHeight = 2_001,
            screenElements = emptyList(),
        )

        assertEquals(SwipeDeviceTool.NAME, call.name)
        assertEquals(100.0, call.arguments.getDouble("start_x"), 0.0)
        assertEquals(1_600.0, call.arguments.getDouble("start_y"), 0.0)
        assertEquals(900.0, call.arguments.getDouble("end_x"), 0.0)
        assertEquals(400.0, call.arguments.getDouble("end_y"), 0.0)
    }

    @Test
    fun typingRequiresAnExactEditableNode() {
        assertThrows(IllegalStateException::class.java) {
            PlannerActionCatalog.toDeviceToolCall(
                action = GeminiPlannerAction(
                    action = PlannerActionCatalog.TYPE,
                    text = "검색어",
                ),
                screenWidth = 1_000,
                screenHeight = 2_000,
                screenElements = emptyList(),
            )
        }

        val call = PlannerActionCatalog.toDeviceToolCall(
            action = GeminiPlannerAction(
                action = PlannerActionCatalog.TYPE,
                nodeId = "node_7",
                text = "검색어",
            ),
            screenWidth = 1_000,
            screenHeight = 2_000,
            screenElements = emptyList(),
        )
        assertEquals(SetTextDeviceTool.NAME, call.name)
        assertEquals("node_7", call.arguments.getString("node_id"))
    }
}
