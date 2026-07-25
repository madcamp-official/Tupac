package com.example.mobileguiagent.device

import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 안드로이드가 기본 제공하는 설정 화면으로 바로 이동한다.
 *
 * 접근성 트리를 더듬어 "설정 → 연결 → Wi-Fi"를 찾아가면 화면마다 모델을 불러야 해서
 * 느리고 부정확하다(실측: Wi-Fi 화면 도달까지 9스텝, 모델 호출 9회). 안드로이드는
 * 주요 설정 화면마다 인텐트 액션을 공개하므로, 목적지를 아는 경우에는 한 번에
 * 점프하는 편이 낫다. 빅스비 같은 기본 비서도 같은 방식을 쓴다.
 *
 * 임의의 인텐트는 받지 않는다. 모델이 만들어낸 문자열로 아무 액티비티나 띄우면
 * 무엇이 열릴지 통제할 수 없기 때문에, 아래 표에 있는 화면만 연다.
 *
 * 여기 있는 건 "화면을 여는 것"까지다. 실제 설정을 바꾸는 건 화면에 도착한 뒤
 * 평소대로 tap으로 한다.
 */
object OpenScreenDeviceTool : DeviceTool {
    const val NAME = "open_screen"

    /**
     * 키 -> (인텐트 액션, 모델에게 보여줄 설명).
     *
     * 키는 모델이 고르는 값이라 짧고 뜻이 분명해야 한다. 설명은 목표 문장과
     * 키를 잇는 다리다("글자 크기"가 display로 가는 걸 모델이 알아야 한다).
     */
    private val screens: Map<String, Pair<String, String>> = linkedMapOf(
        "settings" to (Settings.ACTION_SETTINGS to "설정 첫 화면"),
        "wifi" to (Settings.ACTION_WIFI_SETTINGS to "Wi-Fi 목록과 연결"),
        "bluetooth" to (Settings.ACTION_BLUETOOTH_SETTINGS to "블루투스"),
        "airplane" to (Settings.ACTION_AIRPLANE_MODE_SETTINGS to "비행기 탑승 모드"),
        "connections" to (Settings.ACTION_WIRELESS_SETTINGS to "연결/네트워크 전체"),
        "data_usage" to (Settings.ACTION_DATA_USAGE_SETTINGS to "데이터 사용량"),
        "nfc" to (Settings.ACTION_NFC_SETTINGS to "NFC"),
        "display" to (Settings.ACTION_DISPLAY_SETTINGS to "화면 밝기, 글자 크기, 다크 모드"),
        "sound" to (Settings.ACTION_SOUND_SETTINGS to "소리와 진동, 벨소리, 음량"),
        "notification" to (ACTION_NOTIFICATION_SETTINGS to "알림"),
        "battery" to (ACTION_BATTERY_SETTINGS to "배터리와 절전"),
        "storage" to (Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "저장공간"),
        "apps" to (Settings.ACTION_APPLICATION_SETTINGS to "앱 목록과 앱별 설정"),
        "location" to (Settings.ACTION_LOCATION_SOURCE_SETTINGS to "위치"),
        "security" to (Settings.ACTION_SECURITY_SETTINGS to "보안, 화면 잠금"),
        "privacy" to (Settings.ACTION_PRIVACY_SETTINGS to "개인정보 보호, 권한"),
        "accessibility" to (Settings.ACTION_ACCESSIBILITY_SETTINGS to "접근성"),
        "language" to (Settings.ACTION_LOCALE_SETTINGS to "언어와 키보드"),
        "date" to (Settings.ACTION_DATE_SETTINGS to "날짜와 시간"),
        "developer" to (
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS to "개발자 옵션"
            ),
    )

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Jumps straight to a well-known Android settings screen instead of " +
            "navigating the UI step by step. Only the listed screens can be opened.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "screen",
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(screens.keys.toList()))
                        .put("description", screenHints()),
                ),
            )
            .put("required", JSONArray().put("screen"))
            .put("additionalProperties", false),
    )

    /** "wifi(Wi-Fi 목록과 연결), display(화면 밝기, 글자 크기...)" 형태의 한 줄. */
    fun screenHints(): String =
        screens.entries.joinToString(", ") { (key, value) -> "$key(${value.second})" }

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val key = arguments.optString("screen")
        val target = screens[key]
            ?: return DeviceToolResult.Error(
                code = "UNKNOWN_SCREEN",
                message = "열 수 없는 화면입니다: $key. 가능한 값: ${screens.keys.joinToString()}",
            )

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        val intent = Intent(target.first)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // 제조사에 따라 없는 화면이 있다(예: 일부 기기엔 NFC 설정이 없다).
        // 그럴 때 startActivity는 ActivityNotFoundException을 던지므로, 미리
        // 확인해 모델에게 "이 화면은 없다"고 알려주고 다른 길을 찾게 한다.
        if (intent.resolveActivity(service.packageManager) == null) {
            return DeviceToolResult.Error(
                code = "SCREEN_NOT_AVAILABLE",
                message = "이 기기에는 $key 화면이 없습니다. 화면을 눌러 찾아가세요.",
            )
        }

        return runCatching {
            service.startActivity(intent)
            DeviceToolResult.Success(message = "$key 화면을 열었습니다 (${target.second})")
        }.getOrElse { error ->
            Log.e(TAG, "Unable to open screen: $key", error)
            DeviceToolResult.Error(
                code = "OPEN_SCREEN_FAILED",
                message = "$key 화면을 열지 못했습니다: ${error.message}",
            )
        }
    }

    private const val TAG = "OpenScreenDeviceTool"

    // Settings 상수로 공개돼 있지 않아 문자열을 직접 쓴다. 두 액션 모두 오래전부터
    // 안정적으로 동작하지만, 없는 기기를 대비해 위에서 resolveActivity로 거른다.
    private const val ACTION_NOTIFICATION_SETTINGS = "android.settings.NOTIFICATION_SETTINGS"
    private const val ACTION_BATTERY_SETTINGS = "android.intent.action.POWER_USAGE_SUMMARY"
}
