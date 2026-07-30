package com.example.mobileguiagent.device

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONObject

/**
 * 웹 주소를 연다. 아무 인텐트나 던질 수 있는 창구는 되지 않는다.
 *
 * launch_app은 앱을 열고, open_screen은 정해둔 설정 화면으로 간다. 그 사이에
 * "이 링크를 열어라"가 비어 있었다. 검색 결과나 공유받은 주소로 바로 가는 일은
 * 화면을 더듬어 가는 것보다 한 번에 끝난다.
 *
 * 다만 여는 주소를 부르는 쪽이 정한다는 게 이 도구의 위험이다. 그래서 좁힌다:
 *
 *   https만        intent:·file:·content: 같은 것은 앱 내부나 기기 파일로 가는
 *                  통로다. 여는 주소를 밖에서 정하는 도구가 그쪽까지 열면 안 된다.
 *   userInfo 거절  https://kakao.com@evil.example 은 호스트가 evil.example인데
 *                  사람 눈에는 카카오로 보인다. 주소를 읽고 승인하는 게 사람이라
 *                  이 형태는 승인 자체를 무력화한다.
 *
 * 팀원 브랜치에서 가져왔다. 딥링크가 필요해지면 registeredTargets에 검토한
 * 템플릿으로 넣는다 — 임의의 스킴을 받는 쪽으로 넓히지 않는다.
 */
object OpenUriDeviceTool : DeviceTool {
    const val NAME = "open_uri"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Opens an absolute HTTPS URL in whichever app handles it. " +
            "Other URI schemes are rejected. Use launch_app to open an app by name and " +
            "open_screen for Android settings pages.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "uri",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Absolute HTTPS URL to open."),
                ),
            )
            .put("required", org.json.JSONArray().put("uri"))
            .put("additionalProperties", false),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val raw = arguments.optString("uri").trim()
        if (raw.isEmpty()) {
            return DeviceToolResult.Error(
                code = "MISSING_URI",
                message = "열 주소(uri)가 필요합니다.",
            )
        }
        val uri = safeHttpsUri(raw)
            ?: return DeviceToolResult.Error(
                code = "URI_NOT_ALLOWED",
                message = "사용자 정보가 붙지 않은 https 주소만 열 수 있습니다.",
            )

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(service.packageManager) == null) {
            return DeviceToolResult.Error(
                code = "URI_HANDLER_NOT_FOUND",
                message = "이 주소를 열 수 있는 앱이 없습니다.",
            )
        }

        return runCatching {
            service.startActivity(intent)
            DeviceToolResult.Success(message = "${uri.host} 주소를 열었습니다.")
        }.getOrElse { error ->
            // 주소 전체는 로그에 남기지 않는다. 경로와 질의에 개인정보가 실린다.
            Log.e(TAG, "Unable to open URI host=${uri.host}", error)
            DeviceToolResult.Error(
                code = "OPEN_URI_FAILED",
                message = "주소를 열지 못했습니다.",
            )
        }
    }

    /**
     * 안드로이드가 한 번만 파싱하고, 판단은 [SafeUri]에 맡긴다. 여는 데 쓰는 것과
     * 검사하는 것이 같은 파싱 결과여야 "검사는 통과했는데 다른 곳이 열리는" 일이 없다.
     */
    private fun safeHttpsUri(value: String): Uri? {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        return uri.takeIf { SafeUri.allowed(it.scheme, it.host, it.userInfo) }
    }

    private const val TAG = "OpenUriDeviceTool"
}
