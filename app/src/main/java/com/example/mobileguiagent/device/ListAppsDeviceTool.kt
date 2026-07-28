package com.example.mobileguiagent.device

import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONObject

/**
 * 설치된 앱 이름 목록. 모델이 "어떤 앱이 있는지" 모를 때 쓴다.
 *
 * launch_app이 이름으로 알아서 찾아주므로 대개는 필요 없다. 다만 목표가
 * 모호할 때("음악 틀어줘") 무엇이 깔려 있는지 봐야 고를 수 있다.
 *
 * 폰에는 앱이 백 개 넘게 깔려 있어 전부 프롬프트에 넣으면 토큰만 먹는다.
 * query로 걸러 쓰고, 그래도 많으면 MAX_APPS에서 자른다.
 */
object ListAppsDeviceTool : DeviceTool {
    const val NAME = "list_apps"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Lists installed app names. Use the optional query to filter; " +
            "the result is truncated when too many apps match.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "query",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "이름에 이 글자가 든 앱만. 비우면 전체."),
                ),
            )
            .put("additionalProperties", false),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val rawQuery = arguments.optString("query").trim()
        // "네이버"로 물어도 "NAVER"가 걸려야 한다. 두 이름이 같은 앱을
        // 가리킨다는 걸 아는 것은 여기뿐이다.
        val queries = if (rawQuery.isEmpty()) {
            emptyList()
        } else {
            AppAliases.expand(rawQuery).map { it.lowercase() }
        }

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        val matched = LaunchAppDeviceTool.launchableApps(service.packageManager)
            .map { (label, _) -> label }
            .filter { label ->
                queries.isEmpty() || queries.any { query -> label.lowercase().contains(query) }
            }

        if (matched.isEmpty()) {
            return DeviceToolResult.Success(
                message = if (rawQuery.isEmpty()) {
                    "설치된 앱을 찾지 못했습니다."
                } else {
                    "\"$rawQuery\"에 해당하는 앱이 없습니다."
                },
            )
        }

        val shown = matched.take(MAX_APPS)
        val suffix = if (matched.size > shown.size) {
            " ... (총 ${matched.size}개 중 ${shown.size}개만 표시)"
        } else {
            ""
        }
        return DeviceToolResult.Success(message = shown.joinToString(", ") + suffix)
    }

    private const val MAX_APPS = 60
}
