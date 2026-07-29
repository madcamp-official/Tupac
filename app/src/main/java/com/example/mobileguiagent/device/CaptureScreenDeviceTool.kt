package com.example.mobileguiagent.device

import android.os.Handler
import android.os.Looper
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.accessibility.ScreenCaptureResult
import com.example.mobileguiagent.agent.ScreenPrivacy
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 화면을 그림으로 찍는다.
 *
 * 이 도구에도 민감한 앱 차단이 걸려야 한다. device_observe는 은행·결제·인증
 * 앱에서 응답 자체를 거절하는데(ScreenPrivacy.blockedApp), 여기에 같은 문이
 * 없으면 그 앱을 열고 사진을 찍는 것으로 그 문을 그냥 지나갈 수 있다. 글자를
 * 가려서 내보내는 방식은 픽셀에는 아예 통하지 않으므로, 그림은 통째로 막는
 * 것 말고는 방법이 없다.
 */
object CaptureScreenDeviceTool : DeviceTool {
    const val NAME = "capture_screen"
    const val DEFAULT_MAX_DIMENSION = 1200
    const val MIN_MAX_DIMENSION = 320
    const val MAX_MAX_DIMENSION = 1600

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Captures the current Android screen as a JPEG image. " +
            "Use it when the task depends on visible screen content.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "max_dimension",
                    JSONObject()
                        .put("type", "integer")
                        .put("minimum", MIN_MAX_DIMENSION)
                        .put("maximum", MAX_MAX_DIMENSION)
                        .put("default", DEFAULT_MAX_DIMENSION),
                ),
            )
            .put("additionalProperties", false),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val maxDimension = arguments.optInt(
            "max_dimension",
            DEFAULT_MAX_DIMENSION,
        ).coerceIn(MIN_MAX_DIMENSION, MAX_MAX_DIMENSION)
        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        // 찍기 전에 어느 앱인지 본다. 찍고 나서 거르는 것으로는 늦다.
        service.rootInActiveWindow?.packageName?.toString()?.let { packageName ->
            ScreenPrivacy.blockedApp(packageName)?.let { reason ->
                return DeviceToolResult.Error(
                    code = "SENSITIVE_APP",
                    message = "$reason 이 앱은 사람이 직접 다뤄야 합니다.",
                )
            }
        }

        val result = AtomicReference<ScreenCaptureResult?>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            service.captureScreen(maxDimension) { capture ->
                result.set(capture)
                latch.countDown()
            }
        }
        if (!latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return DeviceToolResult.Error(
                code = "SCREENSHOT_TIMEOUT",
                message = "화면 캡처 응답 시간이 초과됐습니다.",
            )
        }

        return when (val capture = result.get()) {
            is ScreenCaptureResult.Success -> DeviceToolResult.Screenshot(
                jpegBytes = capture.jpegBytes,
                width = capture.width,
                height = capture.height,
            )

            is ScreenCaptureResult.Error -> DeviceToolResult.Error(
                code = capture.code,
                message = capture.message,
            )

            null -> DeviceToolResult.Error(
                code = "SCREENSHOT_NO_RESULT",
                message = "화면 캡처 결과가 없습니다.",
            )
        }
    }

    private const val SCREENSHOT_TIMEOUT_MS = 8_000L
}
