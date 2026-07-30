package com.example.mobileguiagent.device

import android.os.Handler
import android.os.Looper
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 화면 위 두 좌표 사이를 쓸어 넘긴다.
 *
 * scroll과 무엇이 다른가: scroll은 "아래를 보여줘"라고 방향만 말하면 화면 중앙을
 * 기준으로 알아서 민다. 목록을 훑을 때는 그게 맞다. 하지만 배너를 옆으로 넘기거나,
 * 아래에서 올라온 시트를 내려 닫거나, 목록 항목을 옆으로 밀어 버튼을 꺼내는 일은
 * 어디서 어디까지 미는지가 곧 의미라서 방향만으로는 안 된다.
 *
 * 좌표는 device_observe가 돌려주는 것과 같은 화면 좌표계다(왼쪽 위가 0,0).
 *
 * 접근성 서비스에는 swipe가 이미 있었다. 도구로 내주지 않았을 뿐이다.
 */
object SwipeDeviceTool : DeviceTool {
    const val NAME = "swipe"

    private const val DEFAULT_DURATION_MS = 400L
    private const val MIN_DURATION_MS = 100L
    private const val MAX_DURATION_MS = 2_000L
    private const val SWIPE_TIMEOUT_MS = 5_000L

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Swipes between two absolute screen coordinates. Use it for " +
            "carousels, dismissing bottom sheets, or swipe-to-reveal rows — cases where " +
            "the exact path matters. For plain list scrolling use scroll instead. " +
            "Coordinates match device_observe: (0,0) is the top-left of the screen.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("start_x", coordinate("Swipe start X in pixels."))
                    .put("start_y", coordinate("Swipe start Y in pixels."))
                    .put("end_x", coordinate("Swipe end X in pixels."))
                    .put("end_y", coordinate("Swipe end Y in pixels."))
                    .put(
                        "duration_ms",
                        JSONObject()
                            .put("type", "integer")
                            .put("minimum", MIN_DURATION_MS)
                            .put("maximum", MAX_DURATION_MS)
                            .put("default", DEFAULT_DURATION_MS)
                            .put(
                                "description",
                                "How long the swipe takes. Longer drags content; " +
                                    "shorter flings it.",
                            ),
                    ),
            )
            .put(
                "required",
                JSONArray().put("start_x").put("start_y").put("end_x").put("end_y"),
            )
            .put("additionalProperties", false),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        val points = POINT_NAMES.map { name ->
            val value = arguments.opt(name)
            if (value !is Number || value.toInt() < 0) {
                return DeviceToolResult.Error(
                    code = "INVALID_COORDINATE",
                    message = "$name 은(는) 0 이상의 정수여야 합니다.",
                )
            }
            value.toInt()
        }
        val (startX, startY, endX, endY) = points

        val durationMs = arguments.optLong("duration_ms", DEFAULT_DURATION_MS)
        if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) {
            return DeviceToolResult.Error(
                code = "INVALID_DURATION",
                message = "duration_ms는 $MIN_DURATION_MS~$MAX_DURATION_MS 범위여야 합니다.",
            )
        }

        // 화면 밖 좌표는 제스처가 조용히 취소된다. 미리 거절하고 화면 크기를 알려주면
        // 부르는 쪽이 좌표를 고쳐 다시 시도할 수 있다.
        val metrics = service.resources.displayMetrics
        if (
            startX >= metrics.widthPixels || endX >= metrics.widthPixels ||
            startY >= metrics.heightPixels || endY >= metrics.heightPixels
        ) {
            return DeviceToolResult.Error(
                code = "COORDINATE_OUT_OF_BOUNDS",
                message = "좌표가 화면을 벗어났습니다. " +
                    "화면 크기는 ${metrics.widthPixels}x${metrics.heightPixels} 입니다.",
            )
        }

        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            service.swipe(
                startX = startX.toFloat(),
                startY = startY.toFloat(),
                endX = endX.toFloat(),
                endY = endY.toFloat(),
                durationMs = durationMs,
            ) { completed ->
                success.set(completed)
                latch.countDown()
            }
        }

        if (!latch.await(SWIPE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return DeviceToolResult.Error(
                code = "SWIPE_TIMEOUT",
                message = "스와이프 제스처 응답 시간이 초과됐습니다.",
            )
        }

        return if (success.get()) {
            DeviceToolResult.Success(
                message = "($startX,$startY)에서 ($endX,$endY)로 쓸어 넘겼습니다.",
            )
        } else {
            DeviceToolResult.Error(
                code = "SWIPE_CANCELLED",
                message = "스와이프 제스처가 취소됐습니다.",
            )
        }
    }

    private fun coordinate(description: String): JSONObject = JSONObject()
        .put("type", "integer")
        .put("minimum", 0)
        .put("description", description)

    private val POINT_NAMES = listOf("start_x", "start_y", "end_x", "end_y")
}
