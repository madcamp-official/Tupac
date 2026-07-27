package com.example.mobileguiagent.model

import android.content.Context
import java.io.File

/**
 * Coordinate convention expected by a model when it chooses a visual tap.
 *
 * Device tools continue to execute physical screen pixels. The agent controller
 * is responsible for translating a model's output from this space before it
 * invokes those tools.
 */
enum class ModelCoordinateSpace {
    DEVICE_PIXELS,
    NORMALIZED_1000,
}

enum class ModelPlannerPromptStyle {
    GENERIC,
    GUI_OWL,
}

enum class ModelToolCallProtocol {
    JSON,
    LFM2_NATIVE,
}

/**
 * Immutable runtime configuration for one locally installed vision-language model.
 *
 * Keeping filenames, native hints, and image limits together prevents a model
 * from accidentally loading another checkpoint's vision projector.
 */
data class OnDeviceModelProfile(
    val id: String,
    val displayName: String,
    val modelDirectory: String,
    val modelFileName: String,
    val visionProjectorFileName: String?,
    val visionProjectorRequired: Boolean,
    val nativeModelFamilyHint: Int,
    val imageMaxSliceNums: Int,
    val screenshotMaxDimension: Int,
    val coordinateSpace: ModelCoordinateSpace,
    val preferImagePlanning: Boolean,
    val plannerPromptStyle: ModelPlannerPromptStyle,
    val toolCallProtocol: ModelToolCallProtocol,
    /**
     * Adds the model-native non-thinking switch to every user turn.
     *
     * Tool planners need a short, complete JSON object more than a visible
     * reasoning trace. Qwen3 supports `/no_think` in either the system or user
     * message, so the runtime applies it without leaking model-specific syntax
     * into the shared device-tool prompts.
     */
    val disableThinking: Boolean,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(modelDirectory.isNotBlank())
        require(modelFileName.isNotBlank())
        require(nativeModelFamilyHint >= 0)
        require(imageMaxSliceNums > 0)
        require(screenshotMaxDimension > 0)
        require(!visionProjectorRequired || !visionProjectorFileName.isNullOrBlank())
    }

    fun modelFile(filesDir: File): File =
        File(filesDir, "$modelDirectory/$modelFileName")

    fun visionProjectorFile(filesDir: File): File? =
        visionProjectorFileName?.let { fileName ->
            File(filesDir, "$modelDirectory/$fileName")
        }
}

data class ResolvedOnDeviceModel(
    val profile: OnDeviceModelProfile,
    val modelFile: File,
    val visionProjectorFile: File?,
    val modelFilePresent: Boolean,
    val visionProjectorPresent: Boolean,
    val ready: Boolean,
) {
    val visionAvailable: Boolean
        get() = visionProjectorFile != null && visionProjectorPresent
}

/**
 * Selects the strongest complete installation without making an incomplete
 * download the active runtime.
 *
 * GUI-Owl and Qwen3-VL are visual-agent profiles, so both their language model
 * and matching projector must be present. The legacy MiniCPM profile remains a
 * text-capable fallback and uses vision when its projector is available.
 */
object OnDeviceModelProfileResolver {
    val qwen3Text06B = OnDeviceModelProfile(
        id = "qwen3-0.6b-tool",
        displayName = "Qwen3 0.6B · Tool",
        modelDirectory = "models/qwen3-0.6b-tool",
        modelFileName = "Qwen3-0.6B-Q8_0.gguf",
        visionProjectorFileName = null,
        visionProjectorRequired = false,
        nativeModelFamilyHint = 0,
        imageMaxSliceNums = 1,
        screenshotMaxDimension = 1024,
        coordinateSpace = ModelCoordinateSpace.DEVICE_PIXELS,
        preferImagePlanning = false,
        plannerPromptStyle = ModelPlannerPromptStyle.GENERIC,
        toolCallProtocol = ModelToolCallProtocol.JSON,
        disableThinking = true,
    )

    /**
     * Benchmark-only larger Qwen3 candidate.
     *
     * It deliberately stays out of [profiles] until a real-device comparison
     * shows that its tool-selection gain is worth the extra RAM and latency.
     */
    val qwen3Text17B = OnDeviceModelProfile(
        id = "qwen3-1.7b-tool",
        displayName = "Qwen3 1.7B · Tool",
        modelDirectory = "models/qwen3-1.7b-tool",
        modelFileName = "Qwen3-1.7B-Q4_K_M.gguf",
        visionProjectorFileName = null,
        visionProjectorRequired = false,
        nativeModelFamilyHint = 0,
        imageMaxSliceNums = 1,
        screenshotMaxDimension = 1024,
        coordinateSpace = ModelCoordinateSpace.DEVICE_PIXELS,
        preferImagePlanning = false,
        plannerPromptStyle = ModelPlannerPromptStyle.GENERIC,
        toolCallProtocol = ModelToolCallProtocol.JSON,
        disableThinking = true,
    )

    /**
     * Benchmark-only Korean/English on-device candidate.
     *
     * EXAONE 4.0 1.2B advertises agentic tool use, but it is kept out of
     * [profiles] until the same-device benchmark shows that its extra model
     * size improves valid and correct Device Tool selection enough to justify
     * the additional latency and RAM.
     */
    val exaone40Text12B = OnDeviceModelProfile(
        id = "exaone-4.0-1.2b-tool",
        displayName = "EXAONE 4.0 1.2B · Tool",
        modelDirectory = "models/exaone-4.0-1.2b-tool",
        modelFileName = "EXAONE-4.0-1.2B-Q4_K_M.gguf",
        visionProjectorFileName = null,
        visionProjectorRequired = false,
        nativeModelFamilyHint = 0,
        imageMaxSliceNums = 1,
        screenshotMaxDimension = 1024,
        coordinateSpace = ModelCoordinateSpace.DEVICE_PIXELS,
        preferImagePlanning = false,
        plannerPromptStyle = ModelPlannerPromptStyle.GENERIC,
        toolCallProtocol = ModelToolCallProtocol.JSON,
        disableThinking = false,
    )

    /**
     * Benchmark-only candidate.
     *
     * The official GGUF runs on desktop llama.cpp, but the current Android JNI
     * bridge does not preserve LFM2's native tool-call template reliably. Keep
     * the profile addressable by instrumented tests without making it eligible
     * for automatic production selection.
     */
    val lfm2Tool12B = OnDeviceModelProfile(
        id = "lfm2-1.2b-tool",
        displayName = "LFM2 1.2B · Tool",
        modelDirectory = "models/lfm2-1.2b-tool",
        modelFileName = "LFM2-1.2B-Tool-Q4_K_M.gguf",
        visionProjectorFileName = null,
        visionProjectorRequired = false,
        nativeModelFamilyHint = 0,
        imageMaxSliceNums = 1,
        screenshotMaxDimension = 1024,
        coordinateSpace = ModelCoordinateSpace.DEVICE_PIXELS,
        preferImagePlanning = false,
        plannerPromptStyle = ModelPlannerPromptStyle.GENERIC,
        toolCallProtocol = ModelToolCallProtocol.LFM2_NATIVE,
        disableThinking = false,
    )

    val guiOwl15TwoB = OnDeviceModelProfile(
        id = "gui-owl-1.5-2b",
        displayName = "GUI-Owl 1.5 2B",
        modelDirectory = "models/gui-owl-1.5-2b",
        modelFileName = "GUI-Owl-1.5-2B-Instruct.Q4_K_M.gguf",
        visionProjectorFileName = "GUI-Owl-1.5-2B-Instruct.mmproj-Q8_0.gguf",
        visionProjectorRequired = true,
        nativeModelFamilyHint = 0,
        imageMaxSliceNums = 4,
        screenshotMaxDimension = 1024,
        coordinateSpace = ModelCoordinateSpace.NORMALIZED_1000,
        preferImagePlanning = true,
        plannerPromptStyle = ModelPlannerPromptStyle.GUI_OWL,
        toolCallProtocol = ModelToolCallProtocol.JSON,
        disableThinking = false,
    )

    val qwen3VlTwoB = OnDeviceModelProfile(
        id = "qwen3-vl-2b-instruct",
        displayName = "Qwen3-VL 2B · GUI",
        modelDirectory = "models/qwen3-vl-2b-instruct",
        modelFileName = "Qwen3VL-2B-Instruct-Q4_K_M.gguf",
        visionProjectorFileName = "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf",
        visionProjectorRequired = true,
        nativeModelFamilyHint = 0,
        imageMaxSliceNums = 4,
        screenshotMaxDimension = 1024,
        coordinateSpace = ModelCoordinateSpace.DEVICE_PIXELS,
        preferImagePlanning = true,
        plannerPromptStyle = ModelPlannerPromptStyle.GENERIC,
        toolCallProtocol = ModelToolCallProtocol.JSON,
        disableThinking = false,
    )

    val miniCpmV46 = OnDeviceModelProfile(
        id = "minicpm-v-4.6-instruct",
        displayName = "MiniCPM-V 4.6",
        modelDirectory = "models/minicpm-v-4_6-instruct",
        modelFileName = "MiniCPM-V-4_6-Q4_K_M.gguf",
        visionProjectorFileName = "mmproj-model-f16.gguf",
        visionProjectorRequired = false,
        nativeModelFamilyHint = 46,
        imageMaxSliceNums = 4,
        screenshotMaxDimension = 1024,
        coordinateSpace = ModelCoordinateSpace.DEVICE_PIXELS,
        preferImagePlanning = false,
        plannerPromptStyle = ModelPlannerPromptStyle.GENERIC,
        toolCallProtocol = ModelToolCallProtocol.JSON,
        disableThinking = false,
    )

    val profiles: List<OnDeviceModelProfile> = listOf(
        qwen3Text06B,
        guiOwl15TwoB,
        qwen3VlTwoB,
        miniCpmV46,
    )

    /**
     * The small official Qwen text checkpoint is the first install target. It
     * handles UI-tree/tool decisions without paying for an image encoder on
     * every step. Existing visual checkpoints remain usable fallbacks when the
     * text model has not been installed yet.
     */
    val defaultInstallProfile: OnDeviceModelProfile = qwen3Text06B

    fun resolve(context: Context): ResolvedOnDeviceModel =
        resolve(context.applicationContext.filesDir)

    fun resolve(filesDir: File): ResolvedOnDeviceModel {
        val candidates = profiles.map { profile -> resolve(profile, filesDir) }
        return candidates.firstOrNull(ResolvedOnDeviceModel::ready)
            ?: resolve(defaultInstallProfile, filesDir)
    }

    private fun resolve(
        profile: OnDeviceModelProfile,
        filesDir: File,
    ): ResolvedOnDeviceModel {
        val modelFile = profile.modelFile(filesDir)
        val configuredProjector = profile.visionProjectorFile(filesDir)
        val modelPresent = modelFile.isFile && modelFile.canRead()
        val projectorPresent =
            configuredProjector?.let { it.isFile && it.canRead() } == true
        val ready = modelPresent &&
            (!profile.visionProjectorRequired || projectorPresent)
        return ResolvedOnDeviceModel(
            profile = profile,
            modelFile = modelFile,
            visionProjectorFile = configuredProjector?.takeIf { projectorPresent },
            modelFilePresent = modelPresent,
            visionProjectorPresent = projectorPresent,
            ready = ready,
        )
    }
}
