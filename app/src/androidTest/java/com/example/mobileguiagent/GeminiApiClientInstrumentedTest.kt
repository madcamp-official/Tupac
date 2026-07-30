package com.example.mobileguiagent

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mobileguiagent.cloud.GeminiApiClient
import com.example.mobileguiagent.cloud.GeminiPlannerRequest
import com.example.mobileguiagent.cloud.ScreenElementFusion
import com.example.mobileguiagent.cloud.ScreenElementSource
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.model.AgentSkill
import com.example.mobileguiagent.model.AgentSkillBundle
import com.example.mobileguiagent.ocr.OcrScreenObservation
import com.example.mobileguiagent.ocr.OcrTextLine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeminiApiClientInstrumentedTest {
    private val client = GeminiApiClient()

    @Test
    fun parsesStructuredTapAction() {
        val generatedAction = JSONObject()
            .put("action", "tap")
            .put("x", 918)
            .put("y", 489)
        val response = JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", generatedAction.toString()),
                        ),
                    ),
                ),
            ),
        )

        val parsed = client.parseResponse(response.toString())

        assertEquals("tap", parsed.action)
        assertEquals(918.0, parsed.x)
        assertEquals(489.0, parsed.y)
    }

    @Test
    fun requestContainsImageAndSchemaButNeverApiKey() {
        val request = GeminiPlannerRequest(
            goal = "팝업 광고 닫아",
            step = 1,
            maxSteps = 10,
            screenWidth = 1_080,
            screenHeight = 2_340,
            observation = UiSnapshot(
                packageName = "example.target",
                nodes = listOf(
                    UiNode(
                        id = "node_1",
                        text = "닫기",
                        contentDescription = null,
                        className = "android.widget.Button",
                        viewId = "example.target:id/close",
                        clickable = true,
                        editable = false,
                        scrollable = false,
                        enabled = true,
                        checked = null,
                        bounds = Rect(900, 100, 1_000, 200),
                        depth = 1,
                    ),
                ),
            ),
            screenshot = DeviceToolResult.Screenshot(
                jpegBytes = byteArrayOf(1, 2, 3),
                width = 472,
                height = 1_024,
            ),
            recentActions = emptyList(),
        )

        val body = client.buildRequestBody(request).toString()

        assertFalse(body.contains("GEMINI_API_KEY"))
        assertFalse(body.contains("test-secret"))
        assertEquals(
            "application/json",
            JSONObject(body)
                .getJSONObject("generationConfig")
                .getString("responseMimeType"),
        )
    }

    @Test
    fun uiTreeFirstRequestDoesNotContainInlineImage() {
        val request = GeminiPlannerRequest(
            goal = "생수 찾아줘",
            step = 1,
            maxSteps = 10,
            screenWidth = 1_080,
            screenHeight = 2_340,
            observation = UiSnapshot(
                packageName = "com.coupang.mobile",
                nodes = listOf(
                    UiNode(
                        id = "node_1",
                        text = "검색",
                        contentDescription = null,
                        className = "android.widget.EditText",
                        viewId = "search",
                        clickable = true,
                        editable = true,
                        scrollable = false,
                        enabled = true,
                        checked = null,
                        bounds = Rect(0, 0, 900, 120),
                        depth = 1,
                    ),
                ),
            ),
            recentActions = emptyList(),
        )

        val body = client.buildRequestBody(request)
        val parts = body
            .getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")

        assertEquals(1, parts.length())
        assertFalse(body.toString().contains("inlineData"))
        assertTrue(parts.getJSONObject(0).getString("text").contains("request_visual"))
    }

    @Test
    fun plannerReceivesSharedSkillsAndRegisteredAppLaunchAction() {
        val request = GeminiPlannerRequest(
            goal = "메가박스 열어",
            step = 1,
            maxSteps = 24,
            screenWidth = 1_080,
            screenHeight = 2_340,
            observation = UiSnapshot(
                packageName = "com.sec.android.app.launcher",
                nodes = emptyList(),
            ),
            recentActions = emptyList(),
            skills = AgentSkillBundle(
                taskSkills = listOf(
                    AgentSkill("book-megabox-movie", "MEGABOX_TASK_RULE"),
                ),
                navigationSkill = AgentSkill(
                    "gui-app-navigation",
                    "USE_REGISTERED_LAUNCH_APP",
                ),
            ),
        )

        val body = client.buildRequestBody(request).toString()

        assertTrue(body.contains("book-megabox-movie"))
        assertTrue(body.contains("MEGABOX_TASK_RULE"))
        assertTrue(body.contains("gui-app-navigation"))
        assertTrue(body.contains("USE_REGISTERED_LAUNCH_APP"))
        assertTrue(body.contains("launch_app"))
        assertTrue(body.contains("app_name"))
    }

    @Test
    fun fusedElementsRemoveTreeOcrDuplicateAndKeepOcrOnlyText() {
        val snapshot = UiSnapshot(
            packageName = "example.target",
            nodes = listOf(
                UiNode(
                    id = "node_close",
                    text = "오늘은 그만 보기",
                    contentDescription = null,
                    className = "android.widget.TextView",
                    viewId = null,
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                    checked = null,
                    bounds = Rect(100, 1_000, 500, 1_120),
                    depth = 2,
                ),
            ),
        )
        val ocr = OcrScreenObservation(
            lines = listOf(
                OcrTextLine("오늘은 그만 보기", Rect(50, 500, 250, 560)),
                OcrTextLine("OCR에만 보이는 광고", Rect(20, 650, 300, 710)),
            ),
            imageWidth = 540,
            imageHeight = 1_170,
            captureMs = 0,
            recognitionMs = 10,
        )

        val fused = ScreenElementFusion.fuse(
            snapshot = snapshot,
            ocr = ocr,
            deviceWidth = 1_080,
            deviceHeight = 2_340,
        )

        assertEquals(2, fused.size)
        val close = fused.first { it.nodeId == "node_close" }
        assertTrue(ScreenElementSource.ACCESSIBILITY in close.sources)
        assertTrue(ScreenElementSource.OCR in close.sources)
        assertEquals(
            1,
            fused.count { it.text == "오늘은 그만 보기" },
        )
        assertTrue(fused.any { it.text == "OCR에만 보이는 광고" && it.nodeId == null })
    }
}
