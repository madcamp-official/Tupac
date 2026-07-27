package com.example.mobileguiagent.model.litert

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File

enum class LiteRtLmBackend {
    CPU,
    GPU,
}

data class LiteRtLmGeneration(
    val text: String,
    val timeToFirstChunkMs: Long,
    val totalMs: Long,
    val emittedChunkCount: Int,
)

/**
 * Small, model-independent LiteRT-LM adapter used by local experiments.
 *
 * Device actions remain outside this class. The model only emits a proposed
 * Device Tool call; LocalDeviceToolAdapter still validates it before anything
 * can be executed. Keeping that boundary makes this backend interchangeable
 * with the existing llama.cpp engine.
 */
class LiteRtLmEngine(
    context: Context,
    private val modelFile: File,
    private val backend: LiteRtLmBackend,
) : AutoCloseable {
    private val cacheDirectory = File(
        context.cacheDir,
        "litert-lm/${modelFile.nameWithoutExtension}/${backend.name.lowercase()}",
    )

    private var engine: Engine? = null

    suspend fun initialize(): Long = withContext(Dispatchers.IO) {
        check(engine == null) { "LiteRT-LM engine is already initialized" }
        require(modelFile.isFile && modelFile.canRead()) {
            "LiteRT-LM model is unavailable: ${modelFile.absolutePath}"
        }
        check(cacheDirectory.exists() || cacheDirectory.mkdirs()) {
            "Could not create LiteRT-LM cache: ${cacheDirectory.absolutePath}"
        }

        val started = SystemClock.elapsedRealtime()
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = when (backend) {
                LiteRtLmBackend.CPU -> Backend.CPU()
                LiteRtLmBackend.GPU -> Backend.GPU()
            },
            cacheDir = cacheDirectory.absolutePath,
        )
        engine = Engine(config).also { it.initialize() }
        SystemClock.elapsedRealtime() - started
    }

    /**
     * Measures user-visible latency after the engine has been loaded.
     *
     * LiteRT-LM emits text fragments rather than exposing raw token IDs, so
     * "first chunk" is the closest stable app-level TTFT measurement.
     */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
    ): LiteRtLmGeneration = withContext(Dispatchers.Default) {
        val activeEngine = checkNotNull(engine) {
            "LiteRT-LM engine must be initialized before generation"
        }
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            samplerConfig = SamplerConfig(
                temperature = 0.0,
                topP = 1.0,
                topK = 1,
            ),
        )

        activeEngine.createConversation(conversationConfig).use { conversation ->
            val started = SystemClock.elapsedRealtime()
            var firstChunkAt = 0L
            var chunkCount = 0
            val output = StringBuilder()
            conversation.sendMessageAsync(userPrompt).collect { chunk ->
                if (chunkCount == 0) {
                    firstChunkAt = SystemClock.elapsedRealtime()
                }
                chunkCount += 1
                output.append(chunk)
            }
            val completed = SystemClock.elapsedRealtime()
            LiteRtLmGeneration(
                text = output.toString().trim(),
                timeToFirstChunkMs = if (firstChunkAt == 0L) {
                    completed - started
                } else {
                    firstChunkAt - started
                },
                totalMs = completed - started,
                emittedChunkCount = chunkCount,
            )
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
