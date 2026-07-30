package com.example.mobileguiagent.cloud

import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.GoBackDeviceTool
import com.example.mobileguiagent.device.GoHomeDeviceTool
import com.example.mobileguiagent.device.LaunchAppDeviceTool
import com.example.mobileguiagent.device.ListAppsDeviceTool
import com.example.mobileguiagent.device.ScrollDeviceTool
import com.example.mobileguiagent.device.SelectOptionDeviceTool
import com.example.mobileguiagent.device.SetTextDeviceTool
import com.example.mobileguiagent.device.SubmitTextDeviceTool
import com.example.mobileguiagent.device.SwipeDeviceTool
import com.example.mobileguiagent.device.TapDeviceTool
import com.example.mobileguiagent.device.TapNodeDeviceTool
import com.example.mobileguiagent.device.WaitDeviceTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single planner-facing action catalog.
 *
 * This deliberately exposes a smaller reversible subset than the full device
 * registry. Mapping and schema live together so the planner cannot see an
 * action that the runtime cannot translate.
 */
internal object PlannerActionCatalog {
    const val TAP = "tap"
    const val TAP_ELEMENT = "tap_element"
    const val TAP_NODE = "tap_node"
    const val SELECT_OPTION = "select_option"
    const val SWIPE = "swipe"
    const val SCROLL = "scroll"
    const val TYPE = "type"
    const val SUBMIT = "submit"
    const val LAUNCH_APP = "launch_app"
    const val LIST_APPS = "list_apps"
    const val HOME = "home"
    const val BACK = "back"
    const val WAIT = "wait"
    const val REQUEST_VISUAL = "request_visual"
    const val FINISH_SUCCESS = "finish_success"
    const val FINISH_FAILURE = "finish_failure"

    val contract: Map<String, String> = linkedMapOf(
        TAP to "x and y",
        TAP_ELEMENT to "element_id from the current SCREEN_ELEMENTS",
        TAP_NODE to "node_id from the current ACCESSIBILITY_NODES",
        SELECT_OPTION to
            "node_id of a visible selector and option with the exact requested label",
        SCROLL to
            "node_id of a visible scrollable container and direction " +
            "(content direction: down reveals later/below content)",
        SWIPE to "x, y, end_x, and end_y (all four are mandatory)",
        TYPE to "text and node_id of the exact editable ACCESSIBILITY_NODES field",
        SUBMIT to "no arguments; invokes Search/Go/Done on the current field",
        LAUNCH_APP to "app_name, using the visible Korean or English app name",
        LIST_APPS to "optional query; use only when launch_app cannot resolve a name",
        HOME to "no arguments",
        BACK to "no arguments",
        WAIT to "optional duration_ms",
        REQUEST_VISUAL to "no arguments; request a screenshot when the tree is insufficient",
        FINISH_SUCCESS to "optional message; only with current-screen completion evidence",
        FINISH_FAILURE to "optional message; only when safe recovery is exhausted",
    )

    fun responseSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "action",
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(contract.keys.toList())),
                )
                .put("reason_code", JSONObject().put("type", "string"))
                .put("target", JSONObject().put("type", "string"))
                .put("expected_change", JSONObject().put("type", "string"))
                .put("x", normalizedCoordinateSchema())
                .put("y", normalizedCoordinateSchema())
                .put("end_x", normalizedCoordinateSchema())
                .put("end_y", normalizedCoordinateSchema())
                .put("node_id", JSONObject().put("type", "string"))
                .put("option", JSONObject().put("type", "string"))
                .put(
                    "direction",
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(SCROLL_DIRECTIONS.toList())),
                )
                .put("element_id", JSONObject().put("type", "string"))
                .put("text", JSONObject().put("type", "string"))
                .put("app_name", JSONObject().put("type", "string"))
                .put("query", JSONObject().put("type", "string"))
                .put(
                    "duration_ms",
                    JSONObject()
                        .put("type", "integer")
                        .put("minimum", WaitDeviceTool.MIN_DURATION_MS)
                        .put("maximum", WaitDeviceTool.MAX_DURATION_MS),
                )
                .put("message", JSONObject().put("type", "string"))
                .put(
                    "plan",
                    JSONObject()
                        .put("type", "array")
                        .put("items", JSONObject().put("type", "string"))
                        .put("maxItems", 8),
                )
                .put("progress_summary", JSONObject().put("type", "string")),
        )
        .put(
            "required",
            JSONArray(listOf("action", "reason_code", "target", "expected_change")),
        )
        .put("additionalProperties", false)

    fun toDeviceToolCall(
        action: GeminiPlannerAction,
        screenWidth: Int,
        screenHeight: Int,
        screenElements: List<ScreenElement>,
    ): DeviceToolCall = when (action.action) {
        TAP -> DeviceToolCall(
            TapDeviceTool.NAME,
            JSONObject()
                .put("x", action.x.requiredCoordinate("x").toPixels(screenWidth))
                .put("y", action.y.requiredCoordinate("y").toPixels(screenHeight)),
        )

        TAP_NODE -> DeviceToolCall(
            TapNodeDeviceTool.NAME,
            JSONObject().put(
                "node_id",
                action.nodeId?.takeIf(String::isNotBlank)
                    ?: error("tap_node에는 node_id가 필요합니다."),
            ),
        )

        SELECT_OPTION -> DeviceToolCall(
            SelectOptionDeviceTool.NAME,
            JSONObject()
                .put(
                    "node_id",
                    action.nodeId?.takeIf(String::isNotBlank)
                        ?: error("select_option에는 node_id가 필요합니다."),
                )
                .put(
                    "option",
                    action.option?.takeIf(String::isNotBlank)
                        ?: error("select_option에는 option이 필요합니다."),
                ),
        )

        TAP_ELEMENT -> {
            val requestedId = action.elementId?.takeIf(String::isNotBlank)
                ?: error("tap_element에는 element_id가 필요합니다.")
            val element = screenElements.firstOrNull { it.id == requestedId }
                ?: error("현재 화면에 element_id=$requestedId 요소가 없습니다.")
            element.nodeId?.let { nodeId ->
                DeviceToolCall(
                    TapNodeDeviceTool.NAME,
                    JSONObject().put("node_id", nodeId),
                )
            } ?: DeviceToolCall(
                TapDeviceTool.NAME,
                JSONObject()
                    .put(
                        "x",
                        element.bounds.exactCenterX()
                            .coerceIn(0f, (screenWidth - 1).coerceAtLeast(1).toFloat()),
                    )
                    .put(
                        "y",
                        element.bounds.exactCenterY()
                            .coerceIn(0f, (screenHeight - 1).coerceAtLeast(1).toFloat()),
                    ),
            )
        }

        SWIPE -> DeviceToolCall(
            SwipeDeviceTool.NAME,
            JSONObject()
                .put(
                    "start_x",
                    action.x.requiredCoordinate("x").toPixels(screenWidth),
                )
                .put("start_y", action.y.requiredCoordinate("y").toPixels(screenHeight))
                .put(
                    "end_x",
                    action.endX.requiredCoordinate("end_x")
                        .toPixels(screenWidth),
                )
                .put("end_y", action.endY.requiredCoordinate("end_y").toPixels(screenHeight))
                .put("duration_ms", DEFAULT_SWIPE_MS),
        )

        SCROLL -> DeviceToolCall(
            ScrollDeviceTool.NAME,
            JSONObject()
                .put(
                    "node_id",
                    action.nodeId?.takeIf(String::isNotBlank)
                        ?: error("scroll에는 scrollable node_id가 필요합니다."),
                )
                .put(
                    "direction",
                    action.direction?.takeIf { it in SCROLL_DIRECTIONS }
                        ?: error("scroll에는 up/down/left/right direction이 필요합니다."),
                ),
        )

        TYPE -> DeviceToolCall(
            SetTextDeviceTool.NAME,
            JSONObject()
                .put(
                    "node_id",
                    action.nodeId?.takeIf(String::isNotBlank)
                        ?: error("type에는 editable node_id가 필요합니다."),
                )
                .put(
                    "text",
                    action.text?.takeIf(String::isNotBlank)
                        ?: error("type에는 text가 필요합니다."),
                ),
        )

        SUBMIT -> DeviceToolCall(SubmitTextDeviceTool.NAME)
        LAUNCH_APP -> DeviceToolCall(
            LaunchAppDeviceTool.NAME,
            JSONObject().put(
                "name",
                action.appName?.takeIf(String::isNotBlank)
                    ?: error("launch_app에는 app_name이 필요합니다."),
            ),
        )
        LIST_APPS -> DeviceToolCall(
            ListAppsDeviceTool.NAME,
            JSONObject().apply {
                action.query?.takeIf(String::isNotBlank)?.let { put("query", it) }
            },
        )
        HOME -> DeviceToolCall(GoHomeDeviceTool.NAME)
        BACK -> DeviceToolCall(GoBackDeviceTool.NAME)
        WAIT -> DeviceToolCall(
            WaitDeviceTool.NAME,
            JSONObject().put(
                "duration_ms",
                (action.durationMs ?: WaitDeviceTool.DEFAULT_DURATION_MS)
                    .coerceIn(
                        WaitDeviceTool.MIN_DURATION_MS,
                        WaitDeviceTool.MAX_DURATION_MS,
                    ),
            ),
        )

        else -> error("지원하지 않는 planner action입니다: ${action.action}")
    }

    private fun normalizedCoordinateSchema(): JSONObject = JSONObject()
        .put("type", "number")
        .put("minimum", 0)
        .put("maximum", NORMALIZED_COORDINATE_MAX)

    private fun Double?.requiredCoordinate(name: String): Double {
        val value = this ?: error("$name 좌표가 없습니다.")
        require(value in 0.0..NORMALIZED_COORDINATE_MAX) {
            "$name=$value 좌표가 0..1000 범위를 벗어났습니다."
        }
        return value
    }

    private fun Double.toPixels(dimension: Int): Double =
        this / NORMALIZED_COORDINATE_MAX * (dimension - 1).coerceAtLeast(1)

    private const val DEFAULT_SWIPE_MS = 400L
    private const val NORMALIZED_COORDINATE_MAX = 1_000.0
    private val SCROLL_DIRECTIONS = setOf("up", "down", "left", "right")
}
