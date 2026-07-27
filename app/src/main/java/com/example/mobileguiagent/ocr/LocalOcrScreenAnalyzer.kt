package com.example.mobileguiagent.ocr

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import com.example.mobileguiagent.device.CaptureScreenDeviceTool
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolExecutor
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.vision.CloseIconCandidate
import com.example.mobileguiagent.vision.CloseIconDetector
import com.example.mobileguiagent.vision.LumaImage
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class OcrTextLine(
    val text: String,
    /** Bounds in the resized screenshot coordinate space. */
    val bounds: Rect,
)

data class OcrScreenObservation(
    val lines: List<OcrTextLine>,
    val imageWidth: Int,
    val imageHeight: Int,
    val captureMs: Long,
    val recognitionMs: Long,
    val closeIcons: List<CloseIconCandidate> = emptyList(),
    val iconDetectionMs: Long = 0L,
) {
    val totalMs: Long = captureMs + recognitionMs + iconDetectionMs

    fun centerInDevicePixels(
        line: OcrTextLine,
        deviceWidth: Int,
        deviceHeight: Int,
    ): Pair<Float, Float> {
        val bounds = boundsInDevicePixels(line, deviceWidth, deviceHeight)
        return bounds.exactCenterX() to bounds.exactCenterY()
    }

    fun boundsInDevicePixels(
        line: OcrTextLine,
        deviceWidth: Int,
        deviceHeight: Int,
    ): Rect {
        val scaleX = deviceWidth.toFloat() / imageWidth.coerceAtLeast(1)
        val scaleY = deviceHeight.toFloat() / imageHeight.coerceAtLeast(1)
        return Rect(
            (line.bounds.left * scaleX).toInt(),
            (line.bounds.top * scaleY).toInt(),
            (line.bounds.right * scaleX).toInt(),
            (line.bounds.bottom * scaleY).toInt(),
        )
    }

    fun boundsInDevicePixels(
        candidate: CloseIconCandidate,
        deviceWidth: Int,
        deviceHeight: Int,
    ): Rect {
        val scaleX = deviceWidth.toFloat() / imageWidth.coerceAtLeast(1)
        val scaleY = deviceHeight.toFloat() / imageHeight.coerceAtLeast(1)
        return Rect(
            (candidate.bounds.left * scaleX).toInt(),
            (candidate.bounds.top * scaleY).toInt(),
            (candidate.bounds.right * scaleX).toInt(),
            (candidate.bounds.bottom * scaleY).toInt(),
        )
    }
}

/**
 * Small, fully on-device OCR stage used before asking a VLM to inspect pixels.
 *
 * The recognizer is retained so repeated agent turns measure inference rather
 * than repeatedly constructing the Korean OCR pipeline. Screenshots are still
 * captured on demand and are never uploaded.
 */
class LocalOcrScreenAnalyzer(
    private val deviceTools: DeviceToolExecutor,
) {
    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val closeIconDetector = CloseIconDetector()

    suspend fun recognizeCurrentScreen(
        maxDimension: Int = CaptureScreenDeviceTool.MAX_MAX_DIMENSION,
        preferCloseIcons: Boolean = false,
    ): Result<OcrScreenObservation> = withContext(Dispatchers.IO) {
        runCatching {
            val captureStarted = SystemClock.elapsedRealtime()
            val screenshot = deviceTools.execute(
                DeviceToolCall(
                    CaptureScreenDeviceTool.NAME,
                    JSONObject().put("max_dimension", maxDimension),
                ),
            )
            require(screenshot is DeviceToolResult.Screenshot) {
                when (screenshot) {
                    is DeviceToolResult.Error ->
                        "${screenshot.code}: ${screenshot.message}"
                    else -> "화면 캡처 결과가 이미지가 아닙니다."
                }
            }
            val captureMs = SystemClock.elapsedRealtime() - captureStarted
            recognizeJpegBytes(
                jpegBytes = screenshot.jpegBytes,
                captureMs = captureMs,
                preferCloseIcons = preferCloseIcons,
            )
        }
    }

    suspend fun recognizeJpeg(
        jpegBytes: ByteArray,
        preferCloseIcons: Boolean = false,
    ): Result<OcrScreenObservation> = withContext(Dispatchers.IO) {
        runCatching {
            recognizeJpegBytes(
                jpegBytes = jpegBytes,
                captureMs = 0L,
                preferCloseIcons = preferCloseIcons,
            )
        }
    }

    private fun recognizeJpegBytes(
        jpegBytes: ByteArray,
        captureMs: Long,
        preferCloseIcons: Boolean,
    ): OcrScreenObservation {
        val bitmap = BitmapFactory.decodeByteArray(
            jpegBytes,
            0,
            jpegBytes.size,
        ) ?: error("OCR용 스크린샷을 디코딩하지 못했습니다.")
        return try {
            val iconStarted = SystemClock.elapsedRealtime()
            val closeIcons = closeIconDetector.detect(bitmap.toLumaImage())
            val iconDetectionMs = SystemClock.elapsedRealtime() - iconStarted
            // Explicit popup-dismiss goals can use a high-confidence geometric
            // candidate immediately. General navigation still runs OCR because
            // surrounding labels are valuable context for the planner.
            val skipOcr = preferCloseIcons && closeIcons.isNotEmpty()
            val recognitionStarted = SystemClock.elapsedRealtime()
            val lines = if (skipOcr) {
                emptyList()
            } else {
                Tasks.await(
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                ).textBlocks
                    .flatMap { block -> block.lines }
                    .mapNotNull { line ->
                        line.boundingBox?.let { bounds ->
                            OcrTextLine(
                                text = line.text,
                                bounds = Rect(bounds),
                            )
                        }
                    }
            }
            val recognitionMs = if (skipOcr) {
                0L
            } else {
                SystemClock.elapsedRealtime() - recognitionStarted
            }
            OcrScreenObservation(
                lines = lines,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                captureMs = captureMs,
                recognitionMs = recognitionMs,
                closeIcons = closeIcons,
                iconDetectionMs = iconDetectionMs,
            )
        } finally {
            bitmap.recycle()
        }
    }

    fun selectPopupDismissCandidate(
        observation: OcrScreenObservation,
    ): OcrTextLine? = observation.lines
        .mapNotNull { line ->
            val normalized = line.text
                .lowercase()
                .replace(WHITESPACE, "")
                .trim()
            val semanticScore = when {
                // Prefer a reversible close icon over persistent choices such
                // as "오늘은 그만 보기" when both are visible.
                normalized in EXACT_CLOSE_MARKS -> 1_400
                CLOSE_PHRASES.any(normalized::contains) -> 1_200
                else -> return@mapNotNull null
            }
            // Close affordances normally sit near an outer edge. This score is
            // only a tie-breaker after an explicit close phrase/mark match.
            val edgeScore = maxOf(
                observation.imageWidth - line.bounds.centerX(),
                line.bounds.centerX(),
            ) / observation.imageWidth.coerceAtLeast(1).toDouble()
            ScoredLine(line, semanticScore + (edgeScore * 100).toInt())
        }
        .maxByOrNull(ScoredLine::score)
        ?.line

    private data class ScoredLine(
        val line: OcrTextLine,
        val score: Int,
    )

    private fun android.graphics.Bitmap.toLumaImage(): LumaImage {
        val argb = IntArray(width * height)
        getPixels(argb, 0, width, 0, 0, width, height)
        val luma = ByteArray(argb.size)
        argb.forEachIndexed { index, color ->
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            // Integer Rec. 601 approximation; sufficiently accurate for a
            // local-contrast shape detector and cheaper than float math.
            luma[index] = ((red * 77 + green * 150 + blue * 29) shr 8).toByte()
        }
        return LumaImage(width, height, luma)
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")
        val EXACT_CLOSE_MARKS = setOf("x", "×", "✕", "닫기")
        val CLOSE_PHRASES = listOf(
            "닫기",
            "close",
            "오늘하루보지않기",
            "오늘그만보기",
            "다시보지않기",
            "그만보기",
        )
    }
}
