package com.example.mobileguiagent.device

import com.example.mobileguiagent.model.UiSnapshot
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

    data class UiObservation(
        val snapshot: UiSnapshot,
    ) : DeviceToolResult

    data class Action(
        val action: String,
        val success: Boolean,
        val message: String,
    ) : DeviceToolResult

    /** 이미지가 아니라 동작 성공만 돌려주는 도구(go_back, swipe, set_text 등)의 결과. */
    data class Success(
        val message: String? = null,
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

/**
 * Executes already validated device-tool calls.
 *
 * Agent runtimes depend on this narrow contract instead of depending on a
 * model-specific adapter. This keeps Android actions reusable from local VLM,
 * cloud VLM, and MCP entry points without coupling those protocols together.
 */
fun interface DeviceToolExecutor {
    fun execute(call: DeviceToolCall): DeviceToolResult
}

class DeviceToolRegistry(
    tools: List<DeviceTool> = listOf(
        CaptureScreenDeviceTool,
        ObserveUiDeviceTool,
        GoHomeDeviceTool,
        GoBackDeviceTool,
        WaitDeviceTool,
        TapNodeDeviceTool,
        SelectOptionDeviceTool,
        SetTextDeviceTool,
        FillSecretDeviceTool,
        SubmitTextDeviceTool,
        TapDeviceTool,
        SwipeDeviceTool,
        ScrollDeviceTool,
        OpenScreenDeviceTool,
        OpenUriDeviceTool,
        SystemTaskDeviceTool,
        SystemActionDeviceTool,
        LaunchAppDeviceTool,
        ListAppsDeviceTool,
        ListFieldsDeviceTool,
    ),
) : DeviceToolExecutor {
    private val toolsByName = tools.associateBy { tool -> tool.definition.name }

    val definitions: List<DeviceToolDefinition> =
        tools.map(DeviceTool::definition)

    override fun execute(call: DeviceToolCall): DeviceToolResult {
        val tool = toolsByName[call.name]
            ?: return DeviceToolResult.Error(
                code = "UNKNOWN_TOOL",
                message = "등록되지 않은 Device Tool입니다: ${call.name}",
            )
        return tool.execute(call.arguments)
    }
}
