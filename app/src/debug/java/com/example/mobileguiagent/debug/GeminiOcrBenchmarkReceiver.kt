package com.example.mobileguiagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.example.mobileguiagent.BuildConfig
import com.example.mobileguiagent.cloud.GeminiApiClient
import com.example.mobileguiagent.cloud.GeminiMeasuredDecision
import com.example.mobileguiagent.cloud.GeminiModel
import com.example.mobileguiagent.cloud.GeminiPlannerRequest
import com.example.mobileguiagent.cloud.ScreenElementFusion
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.ocr.LocalOcrScreenAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only, non-acting comparison on the preserved Burger King popup image.
 *
 * Legacy mode reproduces tree-first -> optional image retry. Fused mode runs
 * local OCR, removes UI-tree duplicates, and sends the fused text/bounds first.
 */
class GeminiOcrBenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = when (intent.action) {
            ACTION_LEGACY -> BenchmarkMode.LEGACY
            ACTION_FUSED -> BenchmarkMode.FUSED
            else -> return
        }
        val appContext = context.applicationContext
        benchmarkScope.launch {
            runCatching { runBenchmark(appContext, mode) }
                .onFailure { error ->
                    Log.e(TAG, "mode=${mode.logName} benchmark_failed", error)
                }
        }
    }

    private suspend fun runBenchmark(
        context: Context,
        mode: BenchmarkMode,
    ) {
        val totalStarted = SystemClock.elapsedRealtime()
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        require(apiKey.isNotBlank()) {
            "GEMINI_API_KEY is missing from BuildConfig"
        }
        val imageFile = File(context.filesDir, SAVED_POPUP_IMAGE)
        require(imageFile.isFile) {
            "Saved popup image is missing: ${imageFile.absolutePath}"
        }
        val imageBytes = imageFile.readBytes()
        val display = context.resources.displayMetrics
        val snapshot = minimalPopupSnapshot(display.widthPixels, display.heightPixels)
        val client = GeminiApiClient()
        val analyzer = LocalOcrScreenAnalyzer(DeviceToolRegistry())

        var ocrMs = 0L
        val screenElements = if (mode == BenchmarkMode.FUSED) {
            val ocrStarted = SystemClock.elapsedRealtime()
            val ocr = analyzer.recognizeJpeg(imageBytes).getOrThrow()
            ocrMs = SystemClock.elapsedRealtime() - ocrStarted
            ScreenElementFusion.fuse(
                snapshot = snapshot,
                ocr = ocr,
                deviceWidth = display.widthPixels,
                deviceHeight = display.heightPixels,
            )
        } else {
            emptyList()
        }

        var apiMs = 0L
        val firstApiStarted = SystemClock.elapsedRealtime()
        val first = client.decide(
            apiKey = apiKey,
            model = GeminiModel.FLASH_LITE_3_1,
            request = request(
                snapshot = snapshot,
                screenWidth = display.widthPixels,
                screenHeight = display.heightPixels,
                screenElements = screenElements,
            ),
        )
        apiMs += SystemClock.elapsedRealtime() - firstApiStarted
        var finalDecision = first
        var apiCalls = 1
        var visualUsed = false
        if (first.action.action == ACTION_REQUEST_VISUAL) {
            visualUsed = true
            apiCalls += 1
            val visualApiStarted = SystemClock.elapsedRealtime()
            finalDecision = client.decide(
                apiKey = apiKey,
                model = GeminiModel.FLASH_LITE_3_1,
                request = request(
                    snapshot = snapshot,
                    screenWidth = display.widthPixels,
                    screenHeight = display.heightPixels,
                    screenElements = screenElements,
                    screenshot = DeviceToolResult.Screenshot(
                        jpegBytes = imageBytes,
                        width = SAVED_IMAGE_WIDTH,
                        height = SAVED_IMAGE_HEIGHT,
                    ),
                ),
            )
            apiMs += SystemClock.elapsedRealtime() - visualApiStarted
        }

        val decisions = if (apiCalls == 1) listOf(first) else listOf(first, finalDecision)
        val selectedElement = screenElements.firstOrNull { element ->
            element.id == finalDecision.action.elementId
        }
        val resolvedX = selectedElement?.bounds?.exactCenterX()
        val resolvedY = selectedElement?.bounds?.exactCenterY()
        Log.i(
            TAG,
            "mode=${mode.logName} success=true ocr_ms=$ocrMs " +
                "elements=${screenElements.size} api_calls=$apiCalls " +
                "visual_used=$visualUsed api_ms=$apiMs " +
                "request_bytes=${decisions.sumOf { it.requestBytes }} " +
                "prompt_tokens=${decisions.sumNullable(GeminiMeasuredDecision::promptTokenCount)} " +
                "total_tokens=${decisions.sumNullable(GeminiMeasuredDecision::totalTokenCount)} " +
                "total_ms=${SystemClock.elapsedRealtime() - totalStarted} " +
                "action=${finalDecision.action.action} " +
                "x=${finalDecision.action.x} y=${finalDecision.action.y} " +
                "node_id=${finalDecision.action.nodeId} " +
                "element_id=${finalDecision.action.elementId} " +
                "element_text=${selectedElement?.text} " +
                "resolved_x=$resolvedX resolved_y=$resolvedY",
        )
    }

    private fun request(
        snapshot: UiSnapshot,
        screenWidth: Int,
        screenHeight: Int,
        screenElements: List<com.example.mobileguiagent.cloud.ScreenElement>,
        screenshot: DeviceToolResult.Screenshot? = null,
    ): GeminiPlannerRequest = GeminiPlannerRequest(
        goal = "현재 화면의 광고 팝업을 안전하게 닫아.",
        step = 1,
        maxSteps = 24,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        observation = snapshot,
        screenElements = screenElements,
        screenshot = screenshot,
        recentActions = emptyList(),
    )

    private fun minimalPopupSnapshot(
        width: Int,
        height: Int,
    ): UiSnapshot = UiSnapshot(
        packageName = "kr.co.burgerkinghybrid",
        nodes = listOf(
            UiNode(
                id = "node_0",
                text = null,
                contentDescription = null,
                className = "android.webkit.WebView",
                viewId = null,
                clickable = false,
                editable = false,
                scrollable = true,
                enabled = true,
                checked = null,
                bounds = Rect(0, 0, width, height),
                depth = 0,
                visibleToUser = true,
            ),
        ),
    )

    private fun List<GeminiMeasuredDecision>.sumNullable(
        selector: (GeminiMeasuredDecision) -> Int?,
    ): Int? {
        val values = mapNotNull(selector)
        return values.takeIf { it.size == size }?.sum()
    }

    private enum class BenchmarkMode(
        val logName: String,
    ) {
        LEGACY("legacy_tree_then_image"),
        FUSED("fused_tree_ocr"),
    }

    private companion object {
        const val TAG = "GeminiOcrBenchmark"
        const val ACTION_LEGACY =
            "com.example.mobileguiagent.BENCHMARK_GEMINI_LEGACY"
        const val ACTION_FUSED =
            "com.example.mobileguiagent.BENCHMARK_GEMINI_FUSED_OCR"
        const val ACTION_REQUEST_VISUAL = "request_visual"
        const val SAVED_POPUP_IMAGE = "benchmarks/burger_king_popup.png"
        // The preserved benchmark image was resized with the same 1024px long
        // edge used by the production Gemini visual fallback.
        const val SAVED_IMAGE_WIDTH = 473
        const val SAVED_IMAGE_HEIGHT = 1_024
        val benchmarkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
