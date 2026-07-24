package com.example.mobileguiagent.mcp

import android.util.Base64
import com.example.mobileguiagent.device.BackDeviceTool
import com.example.mobileguiagent.device.CaptureScreenDeviceTool
import com.example.mobileguiagent.device.ScrollDeviceTool
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolDefinition
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.DeviceToolResult
import org.json.JSONArray
import org.json.JSONObject

class McpDeviceToolAdapter(
    private val registry: DeviceToolRegistry,
) {
    fun screenshotDefinition(): JSONObject =
        registry.definitions
            .first { definition -> definition.name == CaptureScreenDeviceTool.NAME }
            .toMcpDefinition(EXTERNAL_SCREENSHOT_NAME)

    fun backDefinition(): JSONObject =
        registry.definitions
            .first { definition -> definition.name == BackDeviceTool.NAME }
            .toMcpDefinition(EXTERNAL_BACK_NAME)

    fun scrollDefinition(): JSONObject =
        registry.definitions
            .first { definition -> definition.name == ScrollDeviceTool.NAME }
            .toMcpDefinition(EXTERNAL_SCROLL_NAME)

    fun call(externalName: String, arguments: JSONObject): JSONObject {
        val deviceToolName = when (externalName) {
            EXTERNAL_SCREENSHOT_NAME -> CaptureScreenDeviceTool.NAME
            EXTERNAL_BACK_NAME -> BackDeviceTool.NAME
            EXTERNAL_SCROLL_NAME -> ScrollDeviceTool.NAME
            else -> return error(
                code = "UNKNOWN_TOOL",
                message = "등록되지 않은 MCP Device Tool입니다: $externalName",
            )
        }
        return when (
            val result = registry.execute(
                DeviceToolCall(
                    name = deviceToolName,
                    arguments = arguments,
                ),
            )
        ) {
            is DeviceToolResult.Screenshot -> JSONObject()
                .put(
                    "content",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "text")
                                .put(
                                    "text",
                                    JSONObject()
                                        .put("success", true)
                                        .put("format", "image/jpeg")
                                        .put("width", result.width)
                                        .put("height", result.height)
                                        .put("bytes", result.jpegBytes.size)
                                        .toString(),
                                ),
                        )
                        .put(
                            JSONObject()
                                .put("type", "image")
                                .put(
                                    "data",
                                    Base64.encodeToString(
                                        result.jpegBytes,
                                        Base64.NO_WRAP,
                                    ),
                                )
                                .put("mimeType", "image/jpeg"),
                        ),
                )
                .put("isError", false)

            is DeviceToolResult.Success -> success(result.message)

            is DeviceToolResult.Error -> error(result.code, result.message)
        }
    }

    private fun success(message: String?): JSONObject = JSONObject()
        .put(
            "content",
            JSONArray().put(
                JSONObject()
                    .put("type", "text")
                    .put(
                        "text",
                        JSONObject()
                            .put("success", true)
                            .apply { if (message != null) put("message", message) }
                            .toString(),
                    ),
            ),
        )
        .put("isError", false)

    private fun DeviceToolDefinition.toMcpDefinition(externalName: String): JSONObject =
        JSONObject()
            .put("name", externalName)
            .put("description", description)
            .put("inputSchema", JSONObject(inputSchema.toString()))

    private fun error(code: String, message: String): JSONObject = JSONObject()
        .put(
            "content",
            JSONArray().put(
                JSONObject()
                    .put("type", "text")
                    .put(
                        "text",
                        JSONObject()
                            .put("success", false)
                            .put("error", code)
                            .put("message", message)
                            .toString(),
                    ),
            ),
        )
        .put("isError", true)

    companion object {
        const val EXTERNAL_SCREENSHOT_NAME = "device_screenshot"
        const val EXTERNAL_BACK_NAME = "device_back"
        const val EXTERNAL_SCROLL_NAME = "device_scroll"
    }
}
