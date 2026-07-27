package com.example.mobileguiagent

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.model.LocalDeviceToolAdapter
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDeviceToolAdapterInstrumentedTest {
    private val adapter = LocalDeviceToolAdapter()

    @Test
    fun parsesCaptureScreenToolCallFromPlainJson() {
        val call = adapter.parseToolCall(
            """{"tool":"capture_screen","arguments":{"max_dimension":1200}}""",
        )

        assertEquals("capture_screen", call?.name)
        assertEquals(1200, call?.arguments?.getInt("max_dimension"))
    }

    @Test
    fun parsesToolCallWrappedInMarkdownFence() {
        val call = adapter.parseToolCall(
            """
            ```json
            {"tool":"capture_screen","arguments":{}}
            ```
            """.trimIndent(),
        )

        assertEquals("capture_screen", call?.name)
    }

    @Test
    fun parsesQwenNativeToolCallWrapperAndNameKey() {
        val call = adapter.parseToolCall(
            """
            <tool_call>
            {"name":"tap","arguments":{"x":989,"y":1148}}
            </tool_call>
            """.trimIndent(),
        )

        assertEquals("tap", call?.name)
        assertEquals(989, call?.arguments?.getInt("x"))
        assertEquals(1148, call?.arguments?.getInt("y"))
        assertNull(call?.let(adapter::validationError))
    }

    @Test
    fun qwenNativeToolCallStillUsesSchemaValidation() {
        val call = adapter.parseToolCall(
            """<tool_call>{"name":"tap","arguments":{"x":989}}</tool_call>""",
        )

        assertEquals("tap", call?.name)
        assertEquals(
            """tap.arguments is missing required field "y".""",
            call?.let(adapter::validationError),
        )
    }

    @Test
    fun normalizesGuiOwlClickWithoutScalingCoordinates() {
        val call = adapter.parseToolCall(
            """
            <tool_call>
            {"name":"mobile_use","arguments":{"action":"click","coordinate":[916,491]}}
            </tool_call>
            """.trimIndent(),
        )

        assertEquals("tap", call?.name)
        assertEquals(916, call?.arguments?.getInt("x"))
        assertEquals(491, call?.arguments?.getInt("y"))
        assertNull(call?.let(adapter::validationError))
    }

    @Test
    fun normalizesGuiOwlDirectClickAlias() {
        val call = adapter.parseToolCall(
            """{"tool":"click","arguments":{"coordinate":[918,492]}}""",
        )

        assertEquals("tap", call?.name)
        assertEquals(918, call?.arguments?.getInt("x"))
        assertEquals(492, call?.arguments?.getInt("y"))
        assertNull(call?.let(adapter::validationError))
    }

    @Test
    fun recoversOnlyBracketlessDirectClickCoordinate() {
        val call = adapter.parseToolCall(
            """{"tool":"click","arguments":{"coordinate":436,934}}""",
        )

        assertEquals("tap", call?.name)
        assertEquals(436.0, call?.arguments?.getDouble("x"))
        assertEquals(934.0, call?.arguments?.getDouble("y"))
        assertNull(call?.let(adapter::validationError))
    }

    @Test
    fun rejectsMalformedGuiOwlClickCoordinate() {
        assertNull(
            adapter.parseToolCall(
                """<tool_call>{"name":"mobile_use","arguments":{"action":"click","coordinate":917,493}}</tool_call>""",
            ),
        )
    }

    @Test
    fun normalizesGuiOwlTypeAndSwipeActions() {
        val typeCall = adapter.parseToolCall(
            """<tool_call>{"name":"mobile_use","arguments":{"action":"type","text":"버거킹"}}</tool_call>""",
        )
        val swipeCall = adapter.parseToolCall(
            """
            <tool_call>
            {"name":"mobile_use","arguments":{"action":"swipe","coordinate":[500,800],"coordinate2":[500,200]}}
            </tool_call>
            """.trimIndent(),
        )

        assertEquals("set_text", typeCall?.name)
        assertEquals("버거킹", typeCall?.arguments?.getString("text"))
        assertNull(typeCall?.let(adapter::validationError))
        assertEquals("swipe", swipeCall?.name)
        assertEquals(500, swipeCall?.arguments?.getInt("start_x"))
        assertEquals(800, swipeCall?.arguments?.getInt("start_y"))
        assertEquals(500, swipeCall?.arguments?.getInt("end_x"))
        assertEquals(200, swipeCall?.arguments?.getInt("end_y"))
        assertNull(swipeCall?.let(adapter::validationError))
    }

    @Test
    fun normalizesOnlySupportedGuiOwlSystemWaitAndTerminateActions() {
        val homeCall = adapter.parseToolCall(
            """<tool_call>{"name":"mobile_use","arguments":{"action":"system_button","button":"Home"}}</tool_call>""",
        )
        val backCall = adapter.parseToolCall(
            """<tool_call>{"name":"mobile_use","arguments":{"action":"system_button","button":"Back"}}</tool_call>""",
        )
        val waitCall = adapter.parseToolCall(
            """<tool_call>{"name":"mobile_use","arguments":{"action":"wait","time":1.5}}</tool_call>""",
        )
        val finishCall = adapter.parseToolCall(
            """<tool_call>{"name":"mobile_use","arguments":{"action":"terminate","status":"success"}}</tool_call>""",
        )

        assertEquals("go_home", homeCall?.name)
        assertNull(homeCall?.let(adapter::validationError))
        assertEquals("go_back", backCall?.name)
        assertNull(backCall?.let(adapter::validationError))
        assertEquals("wait", waitCall?.name)
        assertEquals(1500L, waitCall?.arguments?.getLong("duration_ms"))
        assertNull(waitCall?.let(adapter::validationError))
        assertEquals("finish", finishCall?.name)
        assertEquals("작업을 완료했습니다.", finishCall?.arguments?.getString("message"))
        assertNull(finishCall?.let(adapter::validationError))
        assertNull(
            adapter.parseToolCall(
                """<tool_call>{"name":"mobile_use","arguments":{"action":"system_button","button":"Menu"}}</tool_call>""",
            ),
        )
        assertNull(
            adapter.parseToolCall(
                """<tool_call>{"name":"mobile_use","arguments":{"action":"wait","time":20}}</tool_call>""",
            ),
        )
    }

    @Test
    fun rejectsUnknownGuiOwlAction() {
        assertNull(
            adapter.parseToolCall(
                """<tool_call>{"name":"mobile_use","arguments":{"action":"open","text":"Burger King"}}</tool_call>""",
            ),
        )
    }

    @Test
    fun rejectsMalformedGuiOwlCoordinate() {
        assertNull(
            adapter.parseToolCall(
                """<tool_call>{"name":"mobile_use","arguments":{"action":"click","coordinate":[916]}}</tool_call>""",
            ),
        )
        assertNull(
            adapter.parseToolCall(
                """<tool_call>{"name":"mobile_use","arguments":{"action":"swipe","coordinate":[500,"bottom"],"coordinate2":[500,200]}}</tool_call>""",
            ),
        )
    }

    @Test
    fun rejectsLiteralGuiOwlArgumentsPlaceholder() {
        assertNull(
            adapter.parseToolCall(
                """<tool_call>{"name":"mobile_use","arguments":{...}}</tool_call>""",
            ),
        )
    }

    @Test
    fun rejectsUnknownTool() {
        assertNull(
            adapter.parseToolCall(
                """{"tool":"delete_everything","arguments":{}}""",
            ),
        )
    }

    @Test
    fun reportsQwenRequestedToolNameWithoutAllowingUnknownTool() {
        val output =
            """<tool_call>{"name":"delete_everything","arguments":{}}</tool_call>"""

        assertEquals("delete_everything", adapter.requestedToolName(output))
        assertNull(adapter.parseToolCall(output))
    }

    @Test
    fun rejectsShortMalformedNoArgumentToolOutput() {
        assertNull(adapter.parseToolCall("""{="go_home"}"""))
    }

    @Test
    fun rejectsHallucinatedMalformedTool() {
        assertNull(adapter.parseToolCall("""<tool_call>{"check_if_needed"})"""))
    }

    @Test
    fun recoversSingleRequiredStringArgumentFromCompactModelOutput() {
        val call = adapter.parseToolCall(
            """{"tool":"tap_node","arguments":"node_8"}""",
        )

        assertEquals("tap_node", call?.name)
        assertEquals("node_8", call?.arguments?.getString("node_id"))
        assertNull(call?.let(adapter::validationError))
    }

    @Test
    fun reportsMissingRequiredArgumentBeforeExecution() {
        val call = adapter.parseToolCall(
            """{"tool":"tap_node","arguments":{"x":100,"y":200}}""",
        )

        assertEquals(
            """tap_node.arguments is missing required field "node_id".""",
            call?.let(adapter::validationError),
        )
    }

    @Test
    fun rejectsUnescapedQuotedObjectArguments() {
        assertNull(
            adapter.parseToolCall(
                """{"tool":"swipe","arguments":"{"start_x":520.0,"start_y":770.0,"end_x":800.0,"end_y":180.0}}""",
            ),
        )
    }

    @Test
    fun parsesOnlyFirstOfMultipleGeneratedJsonCalls() {
        val call = adapter.parseToolCall(
            """
            {"tool":"tap","arguments":{"x":915,"y":490}}
            {"tool":"go_home","arguments":{}}
            """.trimIndent(),
        )

        assertEquals("tap", call?.name)
        assertEquals(915, call?.arguments?.getInt("x"))
        assertEquals(490, call?.arguments?.getInt("y"))
    }

    @Test
    fun balancesBracesAndEscapesInsideJsonStrings() {
        val call = adapter.parseToolCall(
            """
            {"tool":"set_text","arguments":{"text":"literal {brace} and \"quote\" and \\slash"}}
            {"tool":"go_home","arguments":{}}
            """.trimIndent(),
        )

        assertEquals("set_text", call?.name)
        assertEquals(
            "literal {brace} and \"quote\" and \\slash",
            call?.arguments?.getString("text"),
        )
    }

    @Test
    fun rejectsIncompleteFirstJsonObjectEvenWhenLaterTextContainsAClosingBrace() {
        assertNull(
            adapter.parseToolCall(
                """{"tool":"tap","arguments":{"x":915,"y":490} Action finished }""",
            ),
        )
    }

    @Test
    fun rejectsUnknownFirstObjectInsteadOfExecutingLaterValidCall() {
        assertNull(
            adapter.parseToolCall(
                """
                {"tool":"delete_everything","arguments":{}}
                {"tool":"go_home","arguments":{}}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun plannerProjectionKeepsLateSemanticControlWithinBoundedNodeList() {
        val ordinaryNodes = (0 until 80).map { index ->
            testNode(
                id = "node_$index",
                label = "App $index",
                viewId = "launcher:id/icon_container",
                clickable = true,
            )
        }
        val searchNode = testNode(
            id = "node_428",
            label = "",
            viewId = "launcher:id/app_search_edit_text_wrapper",
            clickable = true,
        )
        val json = adapter.resultJson(
            DeviceToolResult.UiObservation(
                UiSnapshot("launcher", ordinaryNodes + searchNode),
            ),
            goal = "Open an app",
        )
        val nodes = json.getJSONArray("nodes")

        assertEquals(24, nodes.length())
        assertEquals("node_428", nodes.getJSONObject(0).getString("id"))
        assertEquals(
            "search_input",
            nodes.getJSONObject(0).getString("semantic_role"),
        )
        assertTrue(json.getInt("node_count") > json.getInt("returned_node_count"))
    }

    private fun testNode(
        id: String,
        label: String,
        viewId: String,
        clickable: Boolean,
    ) = UiNode(
        id = id,
        text = label,
        contentDescription = null,
        className = "android.view.View",
        viewId = viewId,
        clickable = clickable,
        editable = false,
        scrollable = false,
        enabled = true,
        checked = null,
        bounds = Rect(0, 0, 100, 100),
        depth = 1,
    )
}
