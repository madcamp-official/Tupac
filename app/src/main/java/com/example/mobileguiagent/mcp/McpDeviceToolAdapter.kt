package com.example.mobileguiagent.mcp

import android.util.Base64
import com.example.mobileguiagent.device.CaptureScreenDeviceTool
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

    fun call(externalName: String, arguments: JSONObject): JSONObject {
        val deviceToolName = when (externalName) {
            EXTERNAL_SCREENSHOT_NAME -> CaptureScreenDeviceTool.NAME
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

            is DeviceToolResult.Error -> error(result.code, result.message)
        }
    }

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
    }
}
