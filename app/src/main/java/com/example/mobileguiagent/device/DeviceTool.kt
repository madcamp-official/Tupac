package com.example.mobileguiagent.device

import org.json.JSONObject

data class DeviceToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
)

data class DeviceToolCall(
    val name: String,
    val arguments: JSONObject = JSONObject(),
)

sealed interface DeviceToolResult {
    data class Screenshot(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
    ) : DeviceToolResult

    data class Error(
        val code: String,
        val message: String,
    ) : DeviceToolResult
}

interface DeviceTool {
    val definition: DeviceToolDefinition

    fun execute(arguments: JSONObject): DeviceToolResult
}

class DeviceToolRegistry(
    tools: List<DeviceTool> = listOf(CaptureScreenDeviceTool),
) {
    private val toolsByName = tools.associateBy { tool -> tool.definition.name }

    val definitions: List<DeviceToolDefinition> =
        tools.map(DeviceTool::definition)

    fun execute(call: DeviceToolCall): DeviceToolResult {
        val tool = toolsByName[call.name]
            ?: return DeviceToolResult.Error(
                code = "UNKNOWN_TOOL",
                message = "등록되지 않은 Device Tool입니다: ${call.name}",
            )
        return tool.execute(call.arguments)
    }
}
