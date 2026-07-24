package com.example.mobileguiagent.device

import android.accessibilityservice.AccessibilityService
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONObject

/**
 * "뒤로가기" tool.
 *
 * CaptureScreenDeviceTool과 뼈대가 동일하다. 차이는 execute() 안에서 부르는
 * 접근성 함수뿐: 여기서는 팀원이 만든 AccessibilityService에 이미 내장된
 * performGlobalAction(GLOBAL_ACTION_BACK)을 그대로 감싼다(wrapping).
 */
object BackDeviceTool : DeviceTool {
    const val NAME = "back"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Presses the Android system Back button to return to the previous " +
            "screen. Takes no arguments.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
            .put("additionalProperties", false),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        val dispatched = service.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK,
        )

        return if (dispatched) {
            DeviceToolResult.Success(message = "뒤로가기 동작을 실행했습니다.")
        } else {
            DeviceToolResult.Error(
                code = "BACK_DISPATCH_FAILED",
                message = "뒤로가기 동작을 실행하지 못했습니다.",
            )
        }
    }
}
