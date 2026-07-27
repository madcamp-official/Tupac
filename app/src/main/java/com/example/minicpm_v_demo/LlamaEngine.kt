package com.example.minicpm_v_demo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File

/**
 * llama.cpp layer placement used when the model is loaded.
 *
 * CPU keeps every transformer layer on the CPU. VULKAN asks llama.cpp to
 * offload every supported layer and automatically leaves unsupported
 * operations on the CPU.
 */
enum class LlamaInferenceBackend(
    internal val gpuLayers: Int,
) {
    CPU(gpuLayers = 0),
    VULKAN(gpuLayers = -1),
}

/**
 * Minimal Kotlin bridge for the llama.cpp-based Android native runtime.
 *
 * The package and class name intentionally match the official demo because
 * the prebuilt JNI entry points are bound to this fully-qualified name.
 */
class LlamaEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val initialization = CompletableDeferred<Unit>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    private var modelLoaded = false

    @Volatile
    private var visionProjectorLoaded = false

    @Volatile
    private var loadedModelPath: String? = null

    @Volatile
    private var loadedVisionProjectorPath: String? = null

    @Volatile
    private var loadedModelFamilyHint: Int? = null

    @Volatile
    private var loadedImageMaxSliceNums: Int? = null

    @Volatile
    private var loadedDisableThinking: Boolean? = null

    @Volatile
    private var loadedInferenceBackend: LlamaInferenceBackend? = null

    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String, gpuLayers: Int): Int
    private external fun loadMmproj(mmprojPath: String, imageMaxSliceNums: Int): Int
    private external fun prepare(): Int
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun prefillImage(imageData: ByteArray, imageSize: Int): Int
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
            Log.i(TAG, "Local VLM native runtime initialized")
            initialization.complete(Unit)
        }.onFailure { error ->
            Log.e(TAG, "Local VLM native runtime initialization failed", error)
            initialization.completeExceptionally(error)
        }
    }

    suspend fun loadModel(
        modelFile: File,
        visionProjectorFile: File? = null,
        modelFamilyHint: Int = GENERIC_MODEL_FAMILY_HINT,
        imageMaxSliceNums: Int = DEFAULT_IMAGE_MAX_SLICE_NUMS,
        modelDisplayName: String = modelFile.nameWithoutExtension,
        disableThinking: Boolean = false,
        inferenceBackend: LlamaInferenceBackend = LlamaInferenceBackend.VULKAN,
    ) = withContext(dispatcher) {
        initialization.await()
        require(modelFamilyHint >= 0) { "Model-family hint must be non-negative" }
        require(imageMaxSliceNums > 0) { "Image max slice count must be positive" }

        val requestedModelPath = modelFile.absolutePath
        val requestedProjectorPath = visionProjectorFile?.absolutePath
        val compatibleProjector =
            requestedProjectorPath == null ||
                (
                    visionProjectorLoaded &&
                        loadedVisionProjectorPath == requestedProjectorPath &&
                        loadedImageMaxSliceNums == imageMaxSliceNums
                    )
        val sameRuntime = modelLoaded &&
            loadedModelPath == requestedModelPath &&
            loadedModelFamilyHint == modelFamilyHint &&
            loadedDisableThinking == disableThinking &&
            loadedInferenceBackend == inferenceBackend &&
            compatibleProjector
        if (sameRuntime) {
            return@withContext
        }

        // A prepared native context cannot safely switch checkpoint, projector, or
        // model-family template in place. Recreate it whenever the profile changes.
        if (modelLoaded) {
            unload()
            clearLoadedRuntimeState()
        }

        require(modelFile.isFile && modelFile.canRead()) {
            "Model file is unavailable: ${modelFile.absolutePath}"
        }
        try {
            check(load(requestedModelPath, inferenceBackend.gpuLayers) == 0) {
                "Native model load failed (${inferenceBackend.name})"
            }

            if (visionProjectorFile != null) {
                require(visionProjectorFile.isFile && visionProjectorFile.canRead()) {
                    "Vision projector is unavailable: ${visionProjectorFile.absolutePath}"
                }
                check(
                    loadMmproj(
                        requestedProjectorPath!!,
                        imageMaxSliceNums,
                    ) == 0,
                ) {
                    "Native vision projector load failed"
                }
                visionProjectorLoaded = true
            }

            // The current native mtmd API needs the model-family hint before prepare().
            setMinicpmvVersionNative(modelFamilyHint)
            check(prepare() == 0) { "Native context preparation failed" }
            modelLoaded = true
            loadedModelPath = requestedModelPath
            loadedVisionProjectorPath = requestedProjectorPath
            loadedModelFamilyHint = modelFamilyHint
            loadedImageMaxSliceNums = requestedProjectorPath?.let { imageMaxSliceNums }
            loadedDisableThinking = disableThinking
            loadedInferenceBackend = inferenceBackend
            Log.i(
                TAG,
                "$modelDisplayName runtime ready " +
                    "(family=$modelFamilyHint, vision=$visionProjectorLoaded, " +
                    "backend=${inferenceBackend.name})",
            )
        } catch (error: Throwable) {
            runCatching { unload() }
                .onFailure { Log.w(TAG, "Failed to unload partial model runtime", it) }
            clearLoadedRuntimeState()
            throw error
        }
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
        if (usesLegacyMiniCpmTemplate()) {
            // MiniCPM-V 4.6's bundled GGUF template rejects a standalone
            // `system` role inside common_chat_format_single and the prebuilt
            // JNI bridge does not catch that C++ exception. Keep its
            // instructions in the user turn so a bad template cannot abort the
            // whole Android process.
            check(
                processUserPrompt(
                    combinedLegacyPrompt(systemPrompt, userPrompt),
                    predictLength,
                ) == 0,
            ) {
                "User prompt processing failed"
            }
        } else {
            // Qwen3-VL/GUI-Owl benefit from keeping tool instructions in the
            // native system role rather than mixing them with visible UI text.
            check(processSystemPrompt(systemPrompt) == 0) {
                "System prompt processing failed"
            }
            check(
                processUserPrompt(
                    userPromptForRuntime(userPrompt),
                    predictLength,
                ) == 0,
            ) {
                "User prompt processing failed"
            }
        }

        collectGeneratedText()
    }

    /**
     * Starts a fresh multimodal turn, encodes the captured JPEG through mmproj,
     * then asks the loaded local VLM to answer the user's original query.
     */
    suspend fun generateWithImage(
        systemPrompt: String,
        userPrompt: String,
        imageBytes: ByteArray,
        predictLength: Int = DEFAULT_PREDICT_LENGTH,
    ): String = withContext(dispatcher) {
        initialization.await()
        check(modelLoaded) { "Model is not loaded" }
        check(visionProjectorLoaded) { "Vision projector is not loaded" }
        require(systemPrompt.isNotBlank())
        require(userPrompt.isNotBlank())
        require(imageBytes.isNotEmpty()) { "Image data is empty" }

        fullReset()
        if (usesLegacyMiniCpmTemplate()) {
            // The official MiniCPM demo flow is image -> question. Its legacy
            // template cannot safely represent a separate system message.
            check(prefillImage(imageBytes, imageBytes.size) == 0) {
                "Image prefill failed"
            }
            check(
                processUserPrompt(
                    combinedLegacyPrompt(systemPrompt, userPrompt),
                    predictLength,
                ) == 0,
            ) {
                "User prompt processing failed"
            }
        } else {
            // Preserve the native role order for newer VLMs:
            // system instructions -> current image -> user task.
            check(processSystemPrompt(systemPrompt) == 0) {
                "System prompt processing failed"
            }
            check(prefillImage(imageBytes, imageBytes.size) == 0) {
                "Image prefill failed"
            }
            check(
                processUserPrompt(
                    userPromptForRuntime(userPrompt),
                    predictLength,
                ) == 0,
            ) {
                "User prompt processing failed"
            }
        }

        collectGeneratedText()
    }

    private fun usesLegacyMiniCpmTemplate(): Boolean =
        loadedModelFamilyHint == MINICPM_V46_FAMILY_HINT

    private fun combinedLegacyPrompt(
        systemPrompt: String,
        userPrompt: String,
    ): String = buildString {
        appendLine("Instructions:")
        appendLine(systemPrompt)
        appendLine()
        appendLine("Task:")
        append(userPrompt)
    }

    /**
     * Qwen3 defaults to a visible reasoning pass. Local GUI decisions are
     * bounded tool selections, so thinking wastes latency and can consume the
     * output budget before the closing JSON brace. The switch is appended to
     * each fresh user turn because the native engine resets conversation state
     * before every generation.
     */
    private fun userPromptForRuntime(userPrompt: String): String =
        if (
            loadedDisableThinking == true &&
            !userPrompt.contains(NO_THINK_DIRECTIVE)
        ) {
            "$userPrompt\n$NO_THINK_DIRECTIVE"
        } else {
            userPrompt
        }

    private fun collectGeneratedText(): String = buildString {
        while (true) {
            val token = generateNextToken() ?: break
            append(token)
        }
    }.trim()

    fun cancelGeneration() {
        if (modelLoaded) nativeCancelGeneration()
    }

    suspend fun close() = withContext(dispatcher) {
        if (modelLoaded) {
            unload()
            clearLoadedRuntimeState()
        }
        shutdown()
    }

    private fun clearLoadedRuntimeState() {
        modelLoaded = false
        visionProjectorLoaded = false
        loadedModelPath = null
        loadedVisionProjectorPath = null
        loadedModelFamilyHint = null
        loadedImageMaxSliceNums = null
        loadedDisableThinking = null
        loadedInferenceBackend = null
    }

    companion object {
        private const val TAG = "UiTreeLlamaEngine"
        private const val GENERIC_MODEL_FAMILY_HINT = 0
        private const val MINICPM_V46_FAMILY_HINT = 46
        // Four slices preserve UI detail while leaving enough of the 4K reset
        // context for the user's query and generated answer.
        private const val DEFAULT_IMAGE_MAX_SLICE_NUMS = 4
        private const val DEFAULT_PREDICT_LENGTH = 256
        private const val NO_THINK_DIRECTIVE = "/no_think"

        @Volatile
        private var instance: LlamaEngine? = null

        fun getInstance(context: Context): LlamaEngine =
            instance ?: synchronized(this) {
                instance ?: LlamaEngine(context).also { instance = it }
            }
    }
}
