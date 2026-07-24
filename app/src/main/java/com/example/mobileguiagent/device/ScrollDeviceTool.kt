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
 * "스크롤" tool.
 *
 * back과 다른 점 2가지를 여기서 익힌다:
 *  1) 입력 인자를 받는다 — {"direction": "down"} 를 schema로 받고 execute()에서 꺼내 쓴다.
 *  2) 비동기 동작을 기다린다 — swipe()는 제스처가 끝나면 콜백으로 알려주므로,
 *     CaptureScreenDeviceTool처럼 CountDownLatch로 결과를 기다렸다가 리턴한다.
 */
object ScrollDeviceTool : DeviceTool {
    const val NAME = "scroll"

    private val DIRECTIONS = listOf("up", "down", "left", "right")
    private const val SWIPE_TIMEOUT_MS = 5_000L

    // 화면 밖 시스템 제스처(뒤로가기/알림 내리기) 영역을 피하려고 30%~70% 구간만 스와이프한다.
    private const val NEAR = 0.3f
    private const val FAR = 0.7f

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Scrolls the current screen to reveal off-screen content. Use it when " +
            "the node you need is not in the latest observe result. 'down' reveals content " +
            "further down a list; 'up' scrolls back toward the top.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "direction",
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(DIRECTIONS))
                        .put("description", "Scroll direction: up, down, left, or right."),
                ),
            )
            .put("required", JSONArray().put("direction"))
            .put("additionalProperties", false),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        // 1) 인자 꺼내기 + 검증
        val direction = arguments.optString("direction").trim().lowercase()
        if (direction !in DIRECTIONS) {
            return DeviceToolResult.Error(
                code = "INVALID_DIRECTION",
                message = "direction은 up/down/left/right 중 하나여야 합니다: '$direction'",
            )
        }

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        // 2) 화면 크기로 스와이프 좌표 계산.
        //    direction은 "보고 싶은" 방향이고, 손가락은 그 반대로 움직인다.
        //    (아래를 더 보려면 손가락은 위로 밀어 올린다.)
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        val (startX, startY, endX, endY) = when (direction) {
            "down" -> listOf(centerX, height * FAR, centerX, height * NEAR)
            "up" -> listOf(centerX, height * NEAR, centerX, height * FAR)
            "left" -> listOf(width * FAR, centerY, width * NEAR, centerY)
            else -> listOf(width * NEAR, centerY, width * FAR, centerY) // right
        }

        // 3) swipe는 콜백으로 끝을 알려주는 비동기 함수 → latch로 기다린다.
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            service.swipe(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
            ) { completed ->
                success.set(completed)
                latch.countDown()
            }
        }

        if (!latch.await(SWIPE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return DeviceToolResult.Error(
                code = "SCROLL_TIMEOUT",
                message = "스크롤 제스처 응답 시간이 초과됐습니다.",
            )
        }

        return if (success.get()) {
            DeviceToolResult.Success(message = "$direction 방향으로 스크롤했습니다.")
        } else {
            DeviceToolResult.Error(
                code = "SCROLL_CANCELLED",
                message = "스크롤 제스처가 취소됐습니다.",
            )
        }
    }
}
