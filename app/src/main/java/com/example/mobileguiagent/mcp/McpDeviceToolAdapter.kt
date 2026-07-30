package com.example.mobileguiagent.mcp

import android.util.Base64
import com.example.mobileguiagent.device.CaptureScreenDeviceTool
import com.example.mobileguiagent.device.LaunchAppDeviceTool
import com.example.mobileguiagent.device.ListFieldsDeviceTool
import com.example.mobileguiagent.device.ListAppsDeviceTool
import com.example.mobileguiagent.device.OpenScreenDeviceTool
import com.example.mobileguiagent.device.OpenUriDeviceTool
import com.example.mobileguiagent.device.SystemActionDeviceTool
import com.example.mobileguiagent.device.SystemTaskDeviceTool
import com.example.mobileguiagent.device.ScrollDeviceTool
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolDefinition
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.GoBackDeviceTool
import com.example.mobileguiagent.device.GoHomeDeviceTool
import com.example.mobileguiagent.device.SetTextDeviceTool
import com.example.mobileguiagent.device.SubmitTextDeviceTool
import com.example.mobileguiagent.device.SwipeDeviceTool
import com.example.mobileguiagent.device.TapNodeDeviceTool
import com.example.mobileguiagent.device.TapDeviceTool
import org.json.JSONArray
import org.json.JSONObject

class McpDeviceToolAdapter(
    private val registry: DeviceToolRegistry,
) {
    // MCP에 노출되는 external 이름 <-> 내부 DeviceTool 이름.
    // 새 tool 추가 = 여기 한 줄 + DeviceToolRegistry 등록. (HTTP 서버는 안 건드림)
    private val externalToInternal = linkedMapOf(
        EXTERNAL_SCREENSHOT_NAME to CaptureScreenDeviceTool.NAME,
        EXTERNAL_HOME_NAME to GoHomeDeviceTool.NAME,
        EXTERNAL_BACK_NAME to GoBackDeviceTool.NAME,
        EXTERNAL_CLICK_NODE_NAME to TapNodeDeviceTool.NAME,
        EXTERNAL_TAP_NAME to TapDeviceTool.NAME,
        EXTERNAL_SWIPE_NAME to SwipeDeviceTool.NAME,
        EXTERNAL_SCROLL_NAME to ScrollDeviceTool.NAME,
        EXTERNAL_TYPE_TEXT_NAME to SetTextDeviceTool.NAME,
        EXTERNAL_SUBMIT_TEXT_NAME to SubmitTextDeviceTool.NAME,
        EXTERNAL_OPEN_SCREEN_NAME to OpenScreenDeviceTool.NAME,
        EXTERNAL_OPEN_URI_NAME to OpenUriDeviceTool.NAME,
        EXTERNAL_START_TASK_NAME to SystemTaskDeviceTool.NAME,
        EXTERNAL_SYSTEM_ACTION_NAME to SystemActionDeviceTool.NAME,
        EXTERNAL_LAUNCH_APP_NAME to LaunchAppDeviceTool.NAME,
        EXTERNAL_LIST_APPS_NAME to ListAppsDeviceTool.NAME,
        EXTERNAL_LIST_FIELDS_NAME to ListFieldsDeviceTool.NAME,
    )

    /** tools/list에 실을 이 어댑터가 담당하는 모든 tool의 MCP 정의. */
    fun definitions(): List<JSONObject> =
        externalToInternal.map { (externalName, internalName) ->
            registry.definitions
                .first { definition -> definition.name == internalName }
                .toMcpDefinition(externalName)
        }

    /** 이 어댑터가 처리할 수 있는 tool 이름인지. */
    fun handles(externalName: String): Boolean =
        externalToInternal.containsKey(externalName)

    fun call(externalName: String, arguments: JSONObject): JSONObject {
        val deviceToolName = externalToInternal[externalName]
            ?: return error(
                code = "UNKNOWN_TOOL",
                message = "등록되지 않은 MCP Device Tool입니다: $externalName",
            )
        return when (
            val result = registry.execute(
                DeviceToolCall(name = deviceToolName, arguments = arguments),
            )
        ) {
            is DeviceToolResult.Action -> actionResult(result)
            is DeviceToolResult.Screenshot -> screenshotResult(result)
            is DeviceToolResult.Success -> success(result.message)
            is DeviceToolResult.UiObservation -> observationResult(result)
            is DeviceToolResult.Error -> error(result.code, result.message)
        }
    }

    private fun screenshotResult(result: DeviceToolResult.Screenshot): JSONObject = JSONObject()
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
                            Base64.encodeToString(result.jpegBytes, Base64.NO_WRAP),
                        )
                        .put("mimeType", "image/jpeg"),
                ),
        )
        .put("isError", false)

    private fun observationResult(result: DeviceToolResult.UiObservation): JSONObject = JSONObject()
        .put(
            "content",
            JSONArray().put(
                JSONObject()
                    .put("type", "text")
                    .put(
                        "text",
                        JSONObject()
                            .put("success", true)
                            .put("foreground_package", result.snapshot.packageName)
                            .put("node_count", result.snapshot.nodes.size)
                            .put("fingerprint", result.snapshot.fingerprint.hash)
                            .toString(),
                    ),
            ),
        )
        .put("isError", false)

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
        const val EXTERNAL_CLICK_NODE_NAME = "device_click_node"
        const val EXTERNAL_TAP_NAME = "device_tap"
        const val EXTERNAL_SWIPE_NAME = "device_swipe"
        const val EXTERNAL_SCROLL_NAME = "device_scroll"
        const val EXTERNAL_TYPE_TEXT_NAME = "device_type_text"
        const val EXTERNAL_SUBMIT_TEXT_NAME = "device_submit_text"
        const val EXTERNAL_OPEN_SCREEN_NAME = "device_open_screen"
        const val EXTERNAL_OPEN_URI_NAME = "device_open_uri"
        const val EXTERNAL_START_TASK_NAME = "device_start_task"
        const val EXTERNAL_SYSTEM_ACTION_NAME = "device_system_action"
        const val EXTERNAL_LAUNCH_APP_NAME = "device_launch_app"
        const val EXTERNAL_LIST_APPS_NAME = "device_list_apps"
        const val EXTERNAL_LIST_FIELDS_NAME = "device_list_fields"
    }
}
