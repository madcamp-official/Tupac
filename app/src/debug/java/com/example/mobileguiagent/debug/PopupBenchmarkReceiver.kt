package com.example.mobileguiagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.example.minicpm_v_demo.LlamaEngine
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.ObserveUiDeviceTool
import com.example.mobileguiagent.device.TapDeviceTool
import com.example.mobileguiagent.model.LocalAgentController
import com.example.mobileguiagent.model.LocalDeviceToolAdapter
import com.example.mobileguiagent.model.OnDeviceModelProfileResolver
import com.example.mobileguiagent.ocr.LocalOcrScreenAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Debug-only benchmark harness. It intentionally never launches an Activity,
 * so the Burger King popup remains the foreground screen while either local
 * path observes and acts on it.
 */
class PopupBenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in LONG_RUNNING_ACTIONS) {
            // A BroadcastReceiver has a hard execution deadline. Local VLM
            // image encoding can exceed it on CPU-only phones, so detach the
            // debug benchmark from the broadcast and let the app's active
            // accessibility service keep the process alive.
            val appContext = context.applicationContext
            benchmarkScope.launch {
                runCatching {
                    when (intent.action) {
                        ACTION_VLM -> runLocalVlm(appContext)
                        ACTION_VLM_SAVED_IMAGE -> runLocalVlmSavedImage(appContext)
                    }
                }.onFailure { error ->
                    Log.e(TAG, "benchmark_failed action=${intent.action}", error)
                }
            }
            return
        }
        val pending = goAsync()
        val appContext = context.applicationContext
        benchmarkScope.launch {
            try {
                when (intent.action) {
                    ACTION_ICON -> runIcon(appContext)
                    ACTION_OCR -> runOcr(appContext)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "benchmark_failed action=${intent.action}", error)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runIcon(context: Context) {
        val started = SystemClock.elapsedRealtime()
        val registry = DeviceToolRegistry()
        val analyzer = LocalOcrScreenAnalyzer(registry)
        val observation = analyzer.recognizeCurrentScreen(
            preferCloseIcons = true,
        ).getOrThrow()
        val candidate = observation.closeIcons.maxByOrNull { it.confidence }
        if (candidate == null) {
            Log.i(
                TAG,
                "mode=icon success=false capture_ms=${observation.captureMs} " +
                    "icon_ms=${observation.iconDetectionMs} " +
                    "ocr_ms=${observation.recognitionMs} " +
                    "total_ms=${SystemClock.elapsedRealtime() - started} " +
                    "reason=no_close_candidate",
            )
            return
        }
        val display = context.resources.displayMetrics
        val bounds = observation.boundsInDevicePixels(
            candidate = candidate,
            deviceWidth = display.widthPixels,
            deviceHeight = display.heightPixels,
        )
        val clickStarted = SystemClock.elapsedRealtime()
        val click = registry.execute(
            DeviceToolCall(
                TapDeviceTool.NAME,
                JSONObject()
                    .put("x", bounds.exactCenterX())
                    .put("y", bounds.exactCenterY()),
            ),
        )
        val clickMs = SystemClock.elapsedRealtime() - clickStarted
        delay(500)
        val after = analyzer.recognizeCurrentScreen(
            preferCloseIcons = true,
        ).getOrNull()
        val popupGone = after != null && after.closeIcons.isEmpty()
        Log.i(
            TAG,
            "mode=icon success=${click is DeviceToolResult.Action && click.success && popupGone} " +
                "confidence=${candidate.confidence} " +
                "x=${bounds.exactCenterX()} y=${bounds.exactCenterY()} " +
                "capture_ms=${observation.captureMs} " +
                "icon_ms=${observation.iconDetectionMs} " +
                "ocr_ms=${observation.recognitionMs} click_ms=$clickMs " +
                "verify_ms=${after?.totalMs ?: -1} " +
                "total_ms=${SystemClock.elapsedRealtime() - started}",
        )
    }

    private suspend fun runOcr(context: Context) {
        val started = SystemClock.elapsedRealtime()
        val registry = DeviceToolRegistry()
        val analyzer = LocalOcrScreenAnalyzer(registry)
        val observation = analyzer.recognizeCurrentScreen().getOrThrow()
        val candidate = analyzer.selectPopupDismissCandidate(observation)
        if (candidate == null) {
            Log.i(
                TAG,
                "mode=ocr success=false capture_ms=${observation.captureMs} " +
                    "recognition_ms=${observation.recognitionMs} " +
                    "total_ms=${SystemClock.elapsedRealtime() - started} " +
                    "reason=no_close_candidate texts=" +
                    observation.lines.joinToString(" | ") { it.text }.take(LOG_LIMIT),
            )
            return
        }
        val display = context.resources.displayMetrics
        val (x, y) = observation.centerInDevicePixels(
            candidate,
            deviceWidth = display.widthPixels,
            deviceHeight = display.heightPixels,
        )
        val clickStarted = SystemClock.elapsedRealtime()
        val click = registry.execute(
            DeviceToolCall(
                TapDeviceTool.NAME,
                JSONObject().put("x", x).put("y", y),
            ),
        )
        val clickMs = SystemClock.elapsedRealtime() - clickStarted
        delay(500)
        val after = analyzer.recognizeCurrentScreen().getOrNull()
        val popupGone =
            after != null && analyzer.selectPopupDismissCandidate(after) == null
        Log.i(
            TAG,
            "mode=ocr success=${click is DeviceToolResult.Action && click.success && popupGone} " +
                "candidate=${candidate.text} x=$x y=$y " +
                "capture_ms=${observation.captureMs} " +
                "recognition_ms=${observation.recognitionMs} click_ms=$clickMs " +
                "verify_ms=${after?.totalMs ?: -1} " +
                "total_ms=${SystemClock.elapsedRealtime() - started}",
        )
    }

    private suspend fun runLocalVlm(context: Context) {
        val totalStarted = SystemClock.elapsedRealtime()
        val resolved = OnDeviceModelProfileResolver.resolve(context)
        require(resolved.ready) {
            "로컬 모델 파일이 없습니다: ${resolved.modelFile.absolutePath}"
        }
        val engine = LlamaEngine.getInstance(context)
        val loadStarted = SystemClock.elapsedRealtime()
        engine.loadModel(
            modelFile = resolved.modelFile,
            visionProjectorFile = resolved.visionProjectorFile,
            modelFamilyHint = resolved.profile.nativeModelFamilyHint,
            imageMaxSliceNums = resolved.profile.imageMaxSliceNums,
            modelDisplayName = resolved.profile.displayName,
            disableThinking = resolved.profile.disableThinking,
        )
        val loadMs = SystemClock.elapsedRealtime() - loadStarted
        val registry = DeviceToolRegistry()
        val controller = LocalAgentController(LocalDeviceToolAdapter(registry))
        val agentStarted = SystemClock.elapsedRealtime()
        val outcome = controller.run(
            context = context,
            engine = engine,
            goal = "현재 화면의 광고 또는 팝업 닫기 버튼만 눌러 팝업을 닫아.",
            initialCall = DeviceToolCall(ObserveUiDeviceTool.NAME),
            visionAvailable = resolved.visionAvailable,
            modelProfile = resolved.profile,
            onProgress = {},
            onTrace = {},
        )
        Log.i(
            TAG,
            "mode=local_vlm model=${resolved.profile.id} load_ms=$loadMs " +
                "agent_ms=${SystemClock.elapsedRealtime() - agentStarted} " +
                "total_ms=${SystemClock.elapsedRealtime() - totalStarted} " +
                "steps=${outcome.steps} status=${outcome.status}",
        )
    }

    /**
     * Measures local visual grounding on the exact screenshot used by an OCR
     * benchmark. This path does not tap: it is intended for repeatable latency
     * comparison after a popup's "do not show today" action prevents replay.
     */
    private suspend fun runLocalVlmSavedImage(context: Context) {
        val totalStarted = SystemClock.elapsedRealtime()
        val imageFile = File(context.filesDir, SAVED_POPUP_IMAGE)
        require(imageFile.isFile) {
            "벤치마크 이미지가 없습니다: ${imageFile.absolutePath}"
        }
        val resolved = OnDeviceModelProfileResolver.resolve(context)
        require(resolved.ready) {
            "로컬 모델 파일이 없습니다: ${resolved.modelFile.absolutePath}"
        }
        val engine = LlamaEngine.getInstance(context)
        val loadStarted = SystemClock.elapsedRealtime()
        engine.loadModel(
            modelFile = resolved.modelFile,
            visionProjectorFile = resolved.visionProjectorFile,
            modelFamilyHint = resolved.profile.nativeModelFamilyHint,
            imageMaxSliceNums = resolved.profile.imageMaxSliceNums,
            modelDisplayName = resolved.profile.displayName,
            disableThinking = resolved.profile.disableThinking,
        )
        val loadMs = SystemClock.elapsedRealtime() - loadStarted
        val inferenceStarted = SystemClock.elapsedRealtime()
        val response = engine.generateWithImage(
            systemPrompt = """
                You visually ground Android controls.
                Return only JSON:
                {"tool":"tap","arguments":{"x":integer,"y":integer}}
                Coordinates are absolute pixels on a 1080x2340 screen.
            """.trimIndent(),
            userPrompt =
                "Find the safest reversible control that closes the visible advertisement popup.",
            imageBytes = imageFile.readBytes(),
            predictLength = SAVED_IMAGE_RESPONSE_TOKENS,
        )
        Log.i(
            TAG,
            "mode=local_vlm_saved_image model=${resolved.profile.id} " +
                "load_ms=$loadMs inference_ms=" +
                "${SystemClock.elapsedRealtime() - inferenceStarted} " +
                "total_ms=${SystemClock.elapsedRealtime() - totalStarted} " +
                "response=${response.replace('\n', ' ').take(LOG_LIMIT)}",
        )
    }

    private companion object {
        const val TAG = "PopupBenchmark"
        const val LOG_LIMIT = 2_000
        const val ACTION_ICON =
            "com.example.mobileguiagent.BENCHMARK_ICON_POPUP"
        const val ACTION_OCR =
            "com.example.mobileguiagent.BENCHMARK_OCR_POPUP"
        const val ACTION_VLM =
            "com.example.mobileguiagent.BENCHMARK_LOCAL_VLM_POPUP"
        const val ACTION_VLM_SAVED_IMAGE =
            "com.example.mobileguiagent.BENCHMARK_LOCAL_VLM_SAVED_IMAGE"
        const val SAVED_POPUP_IMAGE = "benchmarks/burger_king_popup.png"
        const val SAVED_IMAGE_RESPONSE_TOKENS = 96
        val LONG_RUNNING_ACTIONS = setOf(ACTION_VLM, ACTION_VLM_SAVED_IMAGE)
        val benchmarkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
