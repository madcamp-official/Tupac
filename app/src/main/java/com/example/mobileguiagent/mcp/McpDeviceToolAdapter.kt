package com.example.mobileguiagent.mcp

import android.util.Base64
import com.example.mobileguiagent.device.CaptureScreenDeviceTool
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolDefinition
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.GoBackDeviceTool
import com.example.mobileguiagent.device.GoHomeDeviceTool
import com.example.mobileguiagent.device.SetTextDeviceTool
import com.example.mobileguiagent.device.SubmitTextDeviceTool
import com.example.mobileguiagent.device.SwipeDeviceTool
import com.example.mobileguiagent.device.TapDeviceTool
import org.json.JSONArray
import org.json.JSONObject

class McpDeviceToolAdapter(
    private val registry: DeviceToolRegistry,
) {
    fun definitions(): List<JSONObject> = EXTERNAL_TO_DEVICE_TOOL.map { (external, device) ->
        registry.definitions
            .first { definition -> definition.name == device }
            .toMcpDefinition(external)
    }

    fun call(externalName: String, arguments: JSONObject): JSONObject {
        val deviceToolName = EXTERNAL_TO_DEVICE_TOOL[externalName]
            ?: return error(
                code = "UNKNOWN_TOOL",
                message = "등록되지 않은 MCP Device Tool입니다: $externalName",
            )
        return when (
            val result = registry.execute(
                DeviceToolCall(
                    name = deviceToolName,
                    arguments = arguments,
                ),
            )
        ) {
            is DeviceToolResult.Action -> actionResult(result)

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

            is DeviceToolResult.UiObservation -> JSONObject()
                .put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "text")
                            .put(
                                "text",
                                JSONObject()
                                    .put("success", true)
                                    .put(
                                        "foreground_package",
                                        result.snapshot.packageName,
                                    )
                                    .put(
                                        "node_count",
                                        result.snapshot.nodes.size,
                                    )
                                    .put(
                                        "fingerprint",
                                        result.snapshot.fingerprint.hash,
                                    )
                                    .toString(),
                            ),
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

    private fun actionResult(result: DeviceToolResult.Action): JSONObject = JSONObject()
        .put(
            "content",
            JSONArray().put(
                JSONObject()
                    .put("type", "text")
                    .put(
                        "text",
                        JSONObject()
                            .put("success", result.success)
                            .put("action", result.action)
                            .put("message", result.message)
                            .toString(),
                    ),
            ),
        )
        .put("isError", !result.success)

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
        const val EXTERNAL_HOME_NAME = "device_home"
        const val EXTERNAL_BACK_NAME = "device_back"
        const val EXTERNAL_TAP_NAME = "device_tap"
        const val EXTERNAL_SWIPE_NAME = "device_swipe"
        const val EXTERNAL_TYPE_TEXT_NAME = "device_type_text"
        const val EXTERNAL_SUBMIT_TEXT_NAME = "device_submit_text"

        private val EXTERNAL_TO_DEVICE_TOOL = linkedMapOf(
            EXTERNAL_SCREENSHOT_NAME to CaptureScreenDeviceTool.NAME,
            EXTERNAL_HOME_NAME to GoHomeDeviceTool.NAME,
            EXTERNAL_BACK_NAME to GoBackDeviceTool.NAME,
            EXTERNAL_TAP_NAME to TapDeviceTool.NAME,
            EXTERNAL_SWIPE_NAME to SwipeDeviceTool.NAME,
            EXTERNAL_TYPE_TEXT_NAME to SetTextDeviceTool.NAME,
            EXTERNAL_SUBMIT_TEXT_NAME to SubmitTextDeviceTool.NAME,
        )
    }
}
