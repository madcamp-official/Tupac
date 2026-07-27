package com.example.mobileguiagent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaInferenceBackend
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.model.LocalDeviceToolAdapter
import com.example.mobileguiagent.model.OnDeviceModelProfile
import com.example.mobileguiagent.model.OnDeviceModelProfileResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only device benchmark for local tool planners.
 *
 * The prompts describe synthetic UI trees and the generated calls are parsed
 * but never executed, so this test cannot navigate or modify the connected
 * phone. Logcat contains one stable `LocalToolModelBench` record per case.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceToolModelBenchmarkInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val adapter = LocalDeviceToolAdapter(DeviceToolRegistry())

    @Test
    fun compareQwenAndMiniCpmToolPlanning() = runBlocking {
        val profiles = listOf(
            OnDeviceModelProfileResolver.qwen3Text06B,
            OnDeviceModelProfileResolver.lfm2Tool12B,
            OnDeviceModelProfileResolver.miniCpmV46,
        )
        profiles.forEach { profile ->
            benchmark(profile)
        }
    }

    @Test
    fun benchmarkLfmOnly() = runBlocking {
        benchmark(OnDeviceModelProfileResolver.lfm2Tool12B)
    }

    @Test
    fun benchmarkQwen17BOnly() = runBlocking {
        benchmark(OnDeviceModelProfileResolver.qwen3Text17B)
    }

    @Test
    fun benchmarkQwen06BOnly() = runBlocking {
        benchmark(OnDeviceModelProfileResolver.qwen3Text06B)
    }

    /**
     * Compares both checkpoints through the same llama.cpp/Vulkan runtime,
     * prompt, output budget, parser, and synthetic UI cases. Keeping the
     * runtime fixed prevents LiteRT-vs-llama.cpp differences from being
     * mistaken for model-quality differences.
     */
    @Test
    fun compareQwen06BAndExaone12B() = runBlocking {
        listOf(
            OnDeviceModelProfileResolver.qwen3Text06B,
            OnDeviceModelProfileResolver.exaone40Text12B,
        ).forEach { profile ->
            benchmark(
                profile = profile,
                backend = LlamaInferenceBackend.VULKAN,
                cases = COMPARISON_CASES,
                compactToolPrompt = true,
                outputTokenLimit = COMPARISON_OUTPUT_TOKEN_LIMIT,
            )
        }
    }

    @Test
    fun benchmarkExaone12BOnly() = runBlocking {
        benchmark(
            profile = OnDeviceModelProfileResolver.exaone40Text12B,
            backend = LlamaInferenceBackend.VULKAN,
            cases = COMPARISON_CASES,
            compactToolPrompt = true,
            outputTokenLimit = COMPARISON_OUTPUT_TOKEN_LIMIT,
        )
    }

    /**
     * Compatibility probe for the current prebuilt Android JNI bridge.
     *
     * The bridge cannot pass EXAONE's `enable_thinking=false` Jinja argument,
     * so this larger budget checks whether a valid final call appears after
     * the reasoning block. It is intentionally separate from the latency-fair
     * 64-token comparison above.
     */
    @Test
    fun benchmarkExaone12BReasoningCompatibility() = runBlocking {
        benchmark(
            profile = OnDeviceModelProfileResolver.exaone40Text12B,
            backend = LlamaInferenceBackend.VULKAN,
            cases = EXAONE_REASONING_COMPATIBILITY_CASES,
            compactToolPrompt = true,
            outputTokenLimit = EXAONE_REASONING_OUTPUT_TOKEN_LIMIT,
        )
    }

    /**
     * Runs the same compact prompt with the same checkpoint and token budget
     * on both backends. No returned tool is executed.
     */
    @Test
    fun compareQwen06BCpuAndVulkan() = runBlocking {
        val profile = OnDeviceModelProfileResolver.qwen3Text06B
        listOf(
            LlamaInferenceBackend.CPU,
            LlamaInferenceBackend.VULKAN,
        ).forEach { backend ->
            benchmark(
                profile = profile,
                backend = backend,
                cases = GPU_COMPARISON_CASES,
                repetitions = GPU_COMPARISON_REPETITIONS,
            )
        }
    }

    @Test
    fun benchmarkQwen06BCpuOnly() = runBlocking {
        benchmarkGpuComparisonBackend(LlamaInferenceBackend.CPU)
    }

    @Test
    fun benchmarkQwen06BVulkanOnly() = runBlocking {
        benchmarkGpuComparisonBackend(LlamaInferenceBackend.VULKAN)
    }

    @Test
    fun benchmarkQwen06BVulkanSmoke() = runBlocking {
        benchmark(
            profile = OnDeviceModelProfileResolver.qwen3Text06B,
            backend = LlamaInferenceBackend.VULKAN,
            cases = GPU_COMPARISON_CASES,
            repetitions = 1,
        )
    }

    private suspend fun benchmarkGpuComparisonBackend(
        backend: LlamaInferenceBackend,
    ) {
        benchmark(
            profile = OnDeviceModelProfileResolver.qwen3Text06B,
            backend = backend,
            cases = GPU_COMPARISON_CASES,
            repetitions = GPU_COMPARISON_REPETITIONS,
        )
    }

    private suspend fun benchmark(
        profile: OnDeviceModelProfile,
        backend: LlamaInferenceBackend = LlamaInferenceBackend.VULKAN,
        cases: List<BenchmarkCase> = CASES,
        repetitions: Int = 1,
        compactToolPrompt: Boolean = false,
        outputTokenLimit: Int = OUTPUT_TOKEN_LIMIT,
    ) {
        val modelFile = profile.modelFile(context.filesDir)
        assertTrue(
            "Missing benchmark model: ${modelFile.absolutePath}",
            modelFile.isFile && modelFile.canRead(),
        )

        val engine = LlamaEngine.getInstance(context)
        val loadStarted = SystemClock.elapsedRealtime()
        engine.loadModel(
            modelFile = modelFile,
            visionProjectorFile = null,
            modelFamilyHint = profile.nativeModelFamilyHint,
            imageMaxSliceNums = profile.imageMaxSliceNums,
            modelDisplayName = profile.displayName,
            disableThinking = profile.disableThinking,
            inferenceBackend = backend,
        )
        val loadMs = SystemClock.elapsedRealtime() - loadStarted
        var validCalls = 0
        var correctCalls = 0
        var totalInferenceMs = 0L

        val measuredCases = buildList {
            repeat(repetitions) {
                addAll(cases)
            }
        }
        measuredCases.forEachIndexed { index, benchmarkCase ->
            val started = SystemClock.elapsedRealtime()
            val protocolPrompt = when (profile.toolCallProtocol) {
                com.example.mobileguiagent.model.ModelToolCallProtocol.JSON ->
                    SYSTEM_PROMPT
                com.example.mobileguiagent.model.ModelToolCallProtocol.EXAONE_JSON_DSL_FALLBACK ->
                    EXAONE_SYSTEM_PROMPT
                com.example.mobileguiagent.model.ModelToolCallProtocol.LFM2_NATIVE ->
                    LFM2_SYSTEM_PROMPT
            }
            val toolPrompt = if (
                compactToolPrompt &&
                profile.toolCallProtocol ==
                com.example.mobileguiagent.model.ModelToolCallProtocol.JSON
            ) {
                adapter.compactPromptSection(benchmarkCase.excludedTools)
            } else {
                adapter.promptSectionFor(
                    protocol = profile.toolCallProtocol,
                    excludedToolNames = benchmarkCase.excludedTools,
                )
            }
            val output = engine.generate(
                systemPrompt = protocolPrompt + "\n\n" + toolPrompt,
                userPrompt = benchmarkCase.prompt,
                predictLength = outputTokenLimit,
            ).trim()
            val inferenceMs = SystemClock.elapsedRealtime() - started
            totalInferenceMs += inferenceMs
            val call = adapter.parseToolCall(output, profile.toolCallProtocol)
            val valid = call != null && adapter.validationError(call) == null
            val correct = valid && benchmarkCase.matches(checkNotNull(call))
            if (valid) validCalls += 1
            if (correct) correctCalls += 1
            Log.i(
                TAG,
                "model=${profile.id} backend=${backend.name} " +
                    "run=${index + 1} case=${benchmarkCase.id} " +
                    "load_ms=$loadMs inference_ms=$inferenceMs " +
                    "valid=$valid correct=$correct " +
                    "expected=${benchmarkCase.expectedTool} " +
                    "actual=${call?.name} output=${output.oneLine().take(LOG_OUTPUT_LIMIT)}",
            )
        }

        Log.i(
            TAG,
            "summary model=${profile.id} backend=${backend.name} load_ms=$loadMs " +
                "cases=${measuredCases.size} valid=$validCalls correct=$correctCalls " +
                "total_inference_ms=$totalInferenceMs " +
                "mean_inference_ms=${totalInferenceMs / measuredCases.size}",
        )
    }

    private data class BenchmarkCase(
        val id: String,
        val expectedTool: String,
        val prompt: String,
        val expectedNodeId: String? = null,
        val excludedTools: Set<String> = emptySet(),
    ) {
        fun matches(call: DeviceToolCall): Boolean =
            call.name == expectedTool &&
                (
                    expectedNodeId == null ||
                        call.arguments.optString("node_id") == expectedNodeId
                    )
    }

    private fun String.oneLine(): String =
        replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val TAG = "LocalToolModelBench"
        const val OUTPUT_TOKEN_LIMIT = 128
        const val COMPARISON_OUTPUT_TOKEN_LIMIT = 64
        const val EXAONE_REASONING_OUTPUT_TOKEN_LIMIT = 192
        const val LOG_OUTPUT_LIMIT = 320
        const val GPU_COMPARISON_REPETITIONS = 3

        val SYSTEM_PROMPT = """
            You are the local tool planner for an Android GUI agent.
            Select exactly one next action for the current request.
            Return exactly one registered tool-call JSON object and no prose or XML.
            If an external GUI task has no current UI observation, the first tool
            must be {"tool":"observe_ui","arguments":{}}.
            Never invent a node id that is absent from the current UI nodes.
            Prefer tap_node over coordinate tap when a matching visible node exists.
            Never claim an action happened without calling a tool.
        """.trimIndent()

        val LFM2_SYSTEM_PROMPT = """
            You are the local tool planner for an Android GUI agent.
            Select exactly one next function for the current request.
            Use one available function call and no explanatory prose.
            If an external GUI task has no current UI observation, use observe_ui.
            Never invent a node id that is absent from the current UI nodes.
        """.trimIndent()

        val EXAONE_SYSTEM_PROMPT = """
            You are the local tool planner for an Android GUI agent.
            Select exactly one next action for the current request.
            Do not reason aloud and do not output <think> blocks.
            Prefer one registered JSON tool call with no prose.
            If JSON cannot be completed, return one compact fallback command line.
            Never invent a node id that is absent from the current UI nodes.
            Never claim an action happened without calling a tool.
        """.trimIndent()

        val CASES = listOf(
            BenchmarkCase(
                id = "observe_before_external_navigation",
                expectedTool = "observe_ui",
                prompt = """
                    USER_GOAL: 버거킹 앱을 찾아서 열어.
                    CURRENT_OBSERVATION: none
                    Choose the single next tool.
                """.trimIndent(),
            ),
            BenchmarkCase(
                id = "go_home",
                expectedTool = "go_home",
                prompt = """
                    USER_GOAL: 홈 화면으로 가.
                    CURRENT_PACKAGE: com.example.mobileguiagent
                    Choose the single next tool.
                """.trimIndent(),
            ),
            BenchmarkCase(
                id = "go_back",
                expectedTool = "go_back",
                prompt = """
                    USER_GOAL: 이전 화면으로 돌아가.
                    CURRENT_PACKAGE: com.android.settings
                    Choose the single next tool.
                """.trimIndent(),
            ),
            BenchmarkCase(
                id = "tap_wifi_node",
                expectedTool = "tap_node",
                expectedNodeId = "node_7",
                // Production removes observe_ui from the model's allowlist once
                // the automatic observation is newer than the last action.
                excludedTools = setOf("observe_ui"),
                prompt = """
                    USER_GOAL: 와이파이 설정을 열어.
                    CURRENT_PACKAGE: com.android.settings
                    UI_NODES:
                    [{"id":"node_3","text":"Bluetooth","clickable":true,"enabled":true},
                     {"id":"node_7","text":"Wi-Fi","clickable":true,"enabled":true}]
                    Choose the single next tool.
                """.trimIndent(),
            ),
        )

        val GPU_COMPARISON_CASES = listOf(
            BenchmarkCase(
                id = "gpu_same_prompt",
                expectedTool = "go_home",
                prompt = """
                    USER_GOAL: 홈 화면으로 가.
                    CURRENT_PACKAGE: com.example.mobileguiagent
                    Choose the single next tool.
                """.trimIndent(),
            ),
        )

        /**
         * Includes distractors and privacy-safe operations that occur in the
         * real local planner, while remaining read-only: returned calls are
         * parsed and scored but never sent to Android.
         */
        val COMPARISON_CASES = listOf(
            CASES[0],
            CASES[3],
            BenchmarkCase(
                id = "tap_correct_korean_node_with_distractors",
                expectedTool = "tap_node",
                expectedNodeId = "node_14",
                excludedTools = setOf("observe_ui"),
                prompt = """
                    USER_GOAL: 배송지의 받는 분 입력칸을 선택해.
                    CURRENT_PACKAGE: com.shopping.example
                    UI_NODES:
                    [{"id":"node_4","text":"상품 검색","class_name":"android.widget.EditText","clickable":true,"editable":true,"enabled":true},
                     {"id":"node_9","text":"주소 검색","class_name":"android.widget.Button","clickable":true,"editable":false,"enabled":true},
                     {"id":"node_14","text":"받는 분","class_name":"android.widget.EditText","clickable":true,"editable":true,"enabled":true},
                     {"id":"node_19","text":"결제하기","class_name":"android.widget.Button","clickable":true,"editable":false,"enabled":true}]
                    Choose the single next tool.
                """.trimIndent(),
            ),
            BenchmarkCase(
                id = "swipe_when_target_not_visible",
                expectedTool = "swipe",
                excludedTools = setOf("observe_ui"),
                prompt = """
                    USER_GOAL: 화면 아래에 있는 배송 요청사항을 찾아.
                    CURRENT_PACKAGE: com.shopping.example
                    UI_NODES:
                    [{"id":"node_2","text":"배송지","clickable":false,"enabled":true},
                     {"id":"node_6","text":"받는 분","clickable":true,"enabled":true}]
                    The requested item is not visible. Choose one reversible next tool.
                """.trimIndent(),
            ),
            BenchmarkCase(
                id = "do_not_invent_missing_node",
                expectedTool = "observe_ui",
                prompt = """
                    USER_GOAL: 쿠폰 적용 버튼을 눌러.
                    CURRENT_PACKAGE: com.shopping.example
                    UI_NODES:
                    [{"id":"node_1","text":"주문 상품","clickable":false,"enabled":true},
                     {"id":"node_5","text":"배송지 변경","clickable":true,"enabled":true}]
                    The observation may be stale. Choose the single safest next tool.
                """.trimIndent(),
            ),
            BenchmarkCase(
                id = "wait_for_loading_screen",
                expectedTool = "wait",
                excludedTools = setOf("observe_ui"),
                prompt = """
                    USER_GOAL: 주문서 화면이 열릴 때까지 기다려.
                    CURRENT_PACKAGE: com.shopping.example
                    UI_NODES:
                    [{"id":"node_3","text":"불러오는 중","clickable":false,"enabled":true}]
                    CURRENT_STATE: loading spinner visible
                    Choose the single reversible next tool.
                """.trimIndent(),
            ),
        )

        val EXAONE_REASONING_COMPATIBILITY_CASES = listOf(
            COMPARISON_CASES[1],
            COMPARISON_CASES[3],
            COMPARISON_CASES[5],
        )
    }
}
