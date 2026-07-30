package com.example.mobileguiagent.device

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * "스크롤" tool.
 *
 * back과 다른 점 2가지를 여기서 익힌다:
 *  1) 입력 인자를 받는다 — {"direction": "down", "node_id": "node_1"} 를
 *     schema로 받고 execute()에서 꺼내 쓴다.
 *  2) 비동기 동작을 기다린다 — swipe()는 제스처가 끝나면 콜백으로 알려주므로,
 *     CaptureScreenDeviceTool처럼 CountDownLatch로 결과를 기다렸다가 리턴한다.
 */
object ScrollDeviceTool : DeviceTool {
    const val NAME = "scroll"

    private val DIRECTIONS = listOf("up", "down", "left", "right")
    private const val SWIPE_TIMEOUT_MS = 5_000L
    private const val MIN_AXIS_TRAVEL_DP = 72f

    // 화면 밖 시스템 제스처(뒤로가기/알림 내리기) 영역을 피하려고 30%~70% 구간만 스와이프한다.
    private const val NEAR = 0.3f
    private const val FAR = 0.7f

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Scrolls the current screen to reveal off-screen content. Use it when " +
            "the node you need is not in the latest observe result. Pass the exact scrollable " +
            "container node_id so the gesture stays inside that list instead of dragging an " +
            "unrelated control. The direction names the " +
            "content you want to reveal: 'down' reveals content further down a list, 'up' goes " +
            "back toward the top, 'right' reveals content to the right such as the next " +
            "home-screen page, and 'left' goes back to the left.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("snapshot_id", snapshotIdSchema())
                    .put(
                        "direction",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray(DIRECTIONS))
                            .put(
                                "description",
                                "Content direction to reveal, not finger-motion direction.",
                            ),
                    )
                    .put(
                        "node_id",
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "description",
                                "Exact visible scrollable container id from the latest observe_ui.",
                            ),
                    ),
            )
            .put(
                "required",
                JSONArray(listOf("snapshot_id", "direction", "node_id")),
            )
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
        val nodeId = arguments.optString("node_id").trim()
        val snapshot = UiObservationStore.resolve(arguments)
            ?: return DeviceToolResult.Error(
                code = "OBSERVE_UI_REQUIRED",
                message = "scroll 전에 observe_ui를 실행해야 합니다.",
            )
        val target = snapshot.nodes.firstOrNull { node -> node.id == nodeId }
            ?: return DeviceToolResult.Error(
                code = "NODE_NOT_FOUND",
                message = "최근 UI Tree에 $nodeId 노드가 없습니다.",
            )
        if (!target.visibleToUser || !target.enabled || !target.scrollable) {
            return DeviceToolResult.Error(
                code = "NODE_NOT_SCROLLABLE",
                message = "보이고 활성화된 scrollable 노드만 스크롤할 수 있습니다.",
            )
        }
        if (target.bounds.width() <= 0 || target.bounds.height() <= 0) {
            return DeviceToolResult.Error(
                code = "INVALID_NODE_BOUNDS",
                message = "스크롤 대상 노드의 화면 영역이 비어 있습니다.",
            )
        }

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        val semanticSuccess = AtomicBoolean(false)
        val semanticLatch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            semanticSuccess.set(
                service.scrollSnapshotNode(
                    target = target,
                    expectedPackage = snapshot.packageName,
                    direction = direction,
                ),
            )
            semanticLatch.countDown()
        }
        if (
            semanticLatch.await(SWIPE_TIMEOUT_MS, TimeUnit.MILLISECONDS) &&
            semanticSuccess.get()
        ) {
            Thread.sleep(SEMANTIC_SETTLE_MS)
            val after = AtomicReference<com.example.mobileguiagent.model.UiSnapshot?>()
            val verifyLatch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                after.set(service.captureSnapshot())
                verifyLatch.countDown()
            }
            if (
                verifyLatch.await(SWIPE_TIMEOUT_MS, TimeUnit.MILLISECONDS) &&
                after.get()?.fingerprint?.hash != snapshot.fingerprint.hash
            ) {
                return DeviceToolResult.Success(
                    message = "$nodeId 접근성 노드에서 $direction 방향 콘텐츠를 스크롤했습니다.",
                )
            }
        }

        // 2) 대상 컨테이너 안쪽으로 스와이프 좌표를 제한한다.
        //    direction은 "드러낼(보고 싶은) 방향"이고, 손가락은 그 반대로 움직인다.
        //    예) down = 아래 내용을 보려고 손가락을 위로 밀어 올림.
        //        right = 오른쪽 페이지를 보려고 손가락을 왼쪽으로 민다.
        val bounds = target.bounds
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val nearX = bounds.left + width * NEAR
        val farX = bounds.left + width * FAR
        val nearY = bounds.top + height * NEAR
        val farY = bounds.top + height * FAR

        val (startX, startY, endX, endY) = when (direction) {
            "down" -> listOf(centerX, farY, centerX, nearY)
            "up" -> listOf(centerX, nearY, centerX, farY)
            "right" -> listOf(farX, centerY, nearX, centerY)
            else -> listOf(nearX, centerY, farX, centerY) // left
        }
        val axisTravel = if (direction == "up" || direction == "down") {
            kotlin.math.abs(endY - startY)
        } else {
            kotlin.math.abs(endX - startX)
        }
        val minimumAxisTravel =
            MIN_AXIS_TRAVEL_DP * service.resources.displayMetrics.density
        Log.i(
            TAG,
            "scroll_plan node=$nodeId direction=$direction bounds=$bounds " +
                "start=($startX,$startY) end=($endX,$endY) " +
                "axis_travel=$axisTravel minimum=$minimumAxisTravel",
        )
        if (axisTravel < minimumAxisTravel) {
            return DeviceToolResult.Error(
                code = "SCROLL_AXIS_TOO_SHORT",
                message = "$nodeId 영역은 $direction 방향 스크롤 제스처를 만들기에 " +
                    "너무 짧습니다(bounds=$bounds, travel=${axisTravel.toInt()}px). " +
                    "그 방향으로 충분히 긴 scrollable 상위 컨테이너를 관찰해서 사용하세요.",
            )
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
            DeviceToolResult.Success(
                message = "$nodeId 안에서 $direction 방향 콘텐츠를 스크롤했습니다.",
            )
        } else {
            DeviceToolResult.Error(
                code = "SCROLL_CANCELLED",
                message = "스크롤 제스처가 취소됐습니다.",
            )
        }
    }

    private const val TAG = "ScrollDeviceTool"
    private const val SEMANTIC_SETTLE_MS = 300L
}
