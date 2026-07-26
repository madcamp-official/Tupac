package com.example.mobileguiagent.device

import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.secret.SecretVault
import org.json.JSONObject

/**
 * 금고에 어떤 필드가 등록돼 있는지 알려준다. 이름만, 값은 절대 아니다.
 *
 * 에이전트가 fill_field를 부르기 전에 "이 폰에 비밀번호가 등록돼 있나"를 알아야
 * 헛수고를 안 한다. 없는 필드를 채우려다 실패하고 다시 판단하는 왕복이 줄어든다.
 */
object ListFieldsDeviceTool : DeviceTool {
    const val NAME = "list_fields"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Lists which personal-data fields are stored on the device. " +
            "Returns field names only, never values.",
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

        val stored = SecretVault.storedFields(service)
        return DeviceToolResult.Success(
            message = if (stored.isEmpty()) {
                "등록된 값이 없습니다. 앱 화면에서 먼저 등록하세요."
            } else {
                "등록된 필드: ${stored.joinToString()}"
            },
        )
    }
}
