package com.example.minicpm_v_demo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal Kotlin bridge for the official MiniCPM-V Android native runtime.
 *
 * The package and class name intentionally match the official demo because
 * the prebuilt JNI entry points are bound to this fully-qualified name.
 * This PoC only exposes text inference; the vision projector is not loaded.
 */
class LlamaEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val initialization = CompletableDeferred<Unit>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    private var modelLoaded = false

    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    private external fun prepare(): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun setMinicpmvVersionNative(version: Int)
    private external fun fullReset()
    private external fun nativeCancelGeneration()
    private external fun unload()
    private external fun shutdown()

    init {
        runCatching {
            CpuFeatures.bestGgmlCpuVariant()?.let { variant ->
                runCatching { System.loadLibrary("ggml-cpu-$variant") }
                    .onFailure { Log.w(TAG, "Optimised CPU backend unavailable: $variant", it) }
            }
            System.loadLibrary("minicpm_v_demo")
            init(appContext.applicationInfo.nativeLibraryDir)
        }.onSuccess {
            Log.i(TAG, "MiniCPM-V native runtime initialized")
            initialization.complete(Unit)
        }.onFailure { error ->
            Log.e(TAG, "MiniCPM-V native runtime initialization failed", error)
            initialization.completeExceptionally(error)
        }
    }

    suspend fun loadModel(modelFile: File) = withContext(dispatcher) {
        initialization.await()
        if (modelLoaded) return@withContext
        require(modelFile.isFile && modelFile.canRead()) {
            "Model file is unavailable: ${modelFile.absolutePath}"
        }
        check(load(modelFile.absolutePath) == 0) { "Native model load failed" }

        // The vision projector normally supplies this hint in the demo.
        // UI-tree testing is text-only, so set the V4.6 family explicitly.
        setMinicpmvVersionNative(MINICPM_V46_VERSION)
        check(prepare() == 0) { "Native context preparation failed" }
        modelLoaded = true
        Log.i(TAG, "MiniCPM-V 4.6 text runtime ready")
    }

    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        predictLength: Int = DEFAULT_PREDICT_LENGTH,
    ): String = withContext(dispatcher) {
        initialization.await()
        check(modelLoaded) { "Model is not loaded" }
        require(systemPrompt.isNotBlank())
        require(userPrompt.isNotBlank())

        fullReset()
        val combinedPrompt = "$systemPrompt\n\n$userPrompt"
        check(processUserPrompt(combinedPrompt, predictLength) == 0) {
            "User prompt processing failed"
        }

        buildString {
            while (true) {
                val token = generateNextToken() ?: break
                append(token)
            }
        }.trim()
    }

    fun cancelGeneration() {
        if (modelLoaded) nativeCancelGeneration()
    }

    suspend fun close() = withContext(dispatcher) {
        if (modelLoaded) {
            unload()
            modelLoaded = false
        }
        shutdown()
    }

    companion object {
        private const val TAG = "UiTreeLlamaEngine"
        private const val MINICPM_V46_VERSION = 46
        private const val DEFAULT_PREDICT_LENGTH = 256

        @Volatile
        private var instance: LlamaEngine? = null

        fun getInstance(context: Context): LlamaEngine =
            instance ?: synchronized(this) {
                instance ?: LlamaEngine(context).also { instance = it }
            }
    }
}
