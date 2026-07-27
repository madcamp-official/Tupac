package com.example.mobileguiagent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.model.LocalDeviceToolAdapter
import com.example.mobileguiagent.model.ModelToolCallProtocol
import com.example.mobileguiagent.model.litert.LiteRtLmBackend
import com.example.mobileguiagent.model.litert.LiteRtLmEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Read-only benchmark for the LiteRT-LM alternative backend.
 *
 * The generated Device Tool call is parsed and validated but never executed.
 * This makes repeated CPU/GPU runs safe on a connected personal phone.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtLmToolBenchmarkInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val adapter = LocalDeviceToolAdapter(DeviceToolRegistry())

    @Test
    fun benchmarkQwen06BLiteRtCpu() = runBlocking {
        benchmark(LiteRtLmBackend.CPU)
    }

    @Test
    fun benchmarkQwen06BLiteRtGpu() = runBlocking {
        benchmark(LiteRtLmBackend.GPU)
    }

    private suspend fun benchmark(backend: LiteRtLmBackend) {
        val modelFile = File(context.filesDir, MODEL_RELATIVE_PATH)
        assertTrue(
            "Missing LiteRT-LM benchmark model: ${modelFile.absolutePath}",
            modelFile.isFile && modelFile.canRead(),
        )

        val systemPrompt = SYSTEM_PROMPT + "\n\n" +
            adapter.promptSectionFor(ModelToolCallProtocol.JSON)
        val engine = LiteRtLmEngine(
            context = context,
            modelFile = modelFile,
            backend = backend,
        )

        try {
            val loadMs = engine.initialize()

            // First generation compiles/caches backend kernels. Report it but
            // keep it outside the steady-state mean.
            val warmUp = engine.generate(systemPrompt, USER_PROMPT)
            logResult(
                backend = backend,
                phase = "warmup",
                run = 0,
                loadMs = loadMs,
                generation = warmUp,
            )

            var totalMs = 0L
            var totalTtftMs = 0L
            repeat(MEASURED_REPETITIONS) { index ->
                val generation = engine.generate(systemPrompt, USER_PROMPT)
                val call = adapter.parseToolCall(generation.text)
                assertTrue(
                    "Invalid LiteRT-LM tool output: ${generation.text}",
                    call != null && adapter.validationError(call) == null,
                )
                assertEquals(EXPECTED_TOOL, call?.name)
                totalMs += generation.totalMs
                totalTtftMs += generation.timeToFirstChunkMs
                logResult(
                    backend = backend,
                    phase = "measured",
                    run = index + 1,
                    loadMs = loadMs,
                    generation = generation,
                )
            }

            Log.i(
                TAG,
                "summary backend=${backend.name} model=$MODEL_FILE_NAME " +
                    "runs=$MEASURED_REPETITIONS load_ms=$loadMs " +
                    "mean_ttft_ms=${totalTtftMs / MEASURED_REPETITIONS} " +
                    "mean_generation_ms=${totalMs / MEASURED_REPETITIONS}",
            )
        } finally {
            engine.close()
        }
    }

    private fun logResult(
        backend: LiteRtLmBackend,
        phase: String,
        run: Int,
        loadMs: Long,
        generation: com.example.mobileguiagent.model.litert.LiteRtLmGeneration,
    ) {
        val call = adapter.parseToolCall(generation.text)
        Log.i(
            TAG,
            "backend=${backend.name} phase=$phase run=$run load_ms=$loadMs " +
                "ttft_ms=${generation.timeToFirstChunkMs} " +
                "generation_ms=${generation.totalMs} " +
                "chunks=${generation.emittedChunkCount} " +
                "actual=${call?.name} output=${generation.text.oneLine().take(320)}",
        )
    }

    private fun String.oneLine(): String =
        replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val TAG = "LiteRtLmToolBench"
        const val MODEL_FILE_NAME = "Qwen3-0.6B.litertlm"
        const val MODEL_RELATIVE_PATH =
            "models/qwen3-0.6b-litertlm/$MODEL_FILE_NAME"
        const val EXPECTED_TOOL = "go_home"
        const val MEASURED_REPETITIONS = 3

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

        val USER_PROMPT = """
            USER_GOAL: 홈 화면으로 가.
            CURRENT_PACKAGE: com.example.mobileguiagent
            Choose the single next tool.
            /no_think
        """.trimIndent()
    }
}
