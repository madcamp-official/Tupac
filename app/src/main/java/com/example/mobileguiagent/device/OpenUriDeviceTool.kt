package com.example.mobileguiagent.device

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Opens a web URL without exposing a generic Intent launcher.
 *
 * Only HTTPS is accepted. App-specific deep links can be added to
 * [registeredTargets] as reviewed templates instead of accepting arbitrary
 * schemes supplied by a remote model.
 */
object OpenUriDeviceTool : DeviceTool {
    const val NAME = "open_uri"

    private val registeredTargets: Map<String, (JSONObject) -> Uri?> = emptyMap()

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Opens an HTTPS URL or a reviewed registered deep-link target. " +
            "Exactly one of uri or target must be supplied; arbitrary URI schemes are rejected.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "uri",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Absolute HTTPS URL to open."),
                    )
                    .put(
                        "target",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray(registeredTargets.keys.toList()))
                            .put("description", "Reviewed app deep-link target."),
                    )
                    .put(
                        "id",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Opaque identifier used by a registered target."),
                    ),
            )
            .put("additionalProperties", false),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val rawUri = arguments.optString("uri").trim()
        val targetName = arguments.optString("target").trim()
        if ((rawUri.isEmpty()) == (targetName.isEmpty())) {
            return DeviceToolResult.Error(
                "INVALID_URI_ARGUMENTS",
                "uri 또는 target 중 정확히 하나가 필요합니다.",
            )
        }
        val uri = if (rawUri.isNotEmpty()) {
            parseSafeHttpsUri(rawUri)
        } else {
            registeredTargets[targetName]?.invoke(arguments)
        } ?: return DeviceToolResult.Error(
            "URI_NOT_ALLOWED",
            if (rawUri.isNotEmpty()) {
                "사용자 정보가 포함되지 않은 유효한 HTTPS URL만 열 수 있습니다."
            } else {
                "등록되지 않았거나 인자가 잘못된 딥링크 target입니다: $targetName"
            },
        )

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                "ACCESSIBILITY_NOT_CONNECTED",
                "접근성 서비스가 연결되지 않았습니다.",
            )
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(service.packageManager) == null) {
            return DeviceToolResult.Error("URI_HANDLER_NOT_FOUND", "이 주소를 열 수 있는 앱이 없습니다.")
        }
        return runCatching {
            service.startActivity(intent)
            DeviceToolResult.Success("HTTPS 주소를 열었습니다: ${uri.host}")
        }.getOrElse { error ->
            Log.e(TAG, "Unable to open URI host=${uri.host}", error)
            DeviceToolResult.Error("OPEN_URI_FAILED", "주소를 열지 못했습니다.")
        }
    }

    private fun parseSafeHttpsUri(value: String): Uri? {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return uri
    }

    private const val TAG = "OpenUriDeviceTool"
}
