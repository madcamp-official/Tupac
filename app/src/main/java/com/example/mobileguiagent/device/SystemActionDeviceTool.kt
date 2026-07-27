package com.example.mobileguiagent.device

import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 시스템 버튼을 대신 누른다 (performGlobalAction).
 *
 * BackDeviceTool이 이미 GLOBAL_ACTION_BACK 하나를 감싸고 있었다. 접근성 서비스는
 * 그 밖에도 홈·최근 앱·알림창·빠른 설정을 같은 방식으로 누를 수 있는데, 쓰지
 * 않고 있었다. 권한은 이미 있다 — 부르기만 하면 된다.
 *
 * 그중 quick_settings가 특히 크다. 일반 앱은 Wi-Fi나 블루투스를 코드로 켤 수
 * 없고(Android 10부터 setWifiEnabled가 막혔다), 설정 앱을 열어 찾아가는 수밖에
 * 없었다. 빠른 설정 패널에는 그 토글들이 한 화면에 모여 있어서 훨씬 짧다.
 * 손전등·화면 회전·모바일 데이터처럼 설정 앱 상단에 없는 토글도 여기 있다.
 *
 * back은 BackDeviceTool로 이미 나가 있어 여기서 중복으로 내보내지 않는다.
 *
 * 여는 것까지가 이 tool의 일이다. 실제로 켜고 끄는 건 패널이 열린 뒤 평소대로
 * observe → tap 으로 한다.
 */
object SystemActionDeviceTool : DeviceTool {
    const val NAME = "system_action"

    /** @param since 이 상수가 생긴 API 레벨. minSdk(29) 이하면 0으로 둔다. */
    private data class GlobalAction(val id: Int, val since: Int = 0, val hint: String)

    private val actions: Map<String, GlobalAction> = linkedMapOf(
        "quick_settings" to GlobalAction(
            id = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
            hint = "빠른 설정 패널을 내린다. 와이파이, 블루투스, 손전등, 비행기 모드, " +
                "화면 회전, 모바일 데이터, 무음 토글이 한 화면에 모여 있다. " +
                "켜고 끄는 것은 패널이 열린 뒤 tap으로 한다",
        ),
        "home" to GlobalAction(
            id = AccessibilityService.GLOBAL_ACTION_HOME,
            hint = "홈 화면으로 나간다. 길을 잃었을 때 back을 반복하는 것보다 확실하다",
        ),
        "notifications" to GlobalAction(
            id = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            hint = "알림창을 내린다. 받은 알림을 읽거나 누를 때",
        ),
        "recents" to GlobalAction(
            id = AccessibilityService.GLOBAL_ACTION_RECENTS,
            hint = "최근 앱 목록. 방금까지 쓰던 앱으로 돌아갈 때",
        ),
        "lock" to GlobalAction(
            id = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
            hint = "화면을 잠근다. 이 뒤로는 에이전트가 아무것도 할 수 없다",
        ),
        "power" to GlobalAction(
            id = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
            hint = "전원 버튼을 길게 누른 화면(전원 끄기, 다시 시작)",
        ),
        "close_shade" to GlobalAction(
            id = AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE,
            since = Build.VERSION_CODES.S,
            hint = "내려둔 알림창이나 빠른 설정 패널을 닫는다",
        ),
        "all_apps" to GlobalAction(
            id = AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS,
            since = Build.VERSION_CODES.S,
            hint = "앱 서랍(설치된 앱 전체 목록)을 연다",
        ),
    )

    /** 이 기기에서 실제로 쓸 수 있는 것만. 없는 걸 알려주면 모델이 헛되이 부른다. */
    private fun available(): Map<String, GlobalAction> =
        actions.filterValues { action -> Build.VERSION.SDK_INT >= action.since }

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Presses a system-level button (quick settings, home, notifications, " +
            "recents, lock...) through the accessibility service. Quick settings is the only " +
            "way a non-system app can reach the Wi-Fi, Bluetooth, torch and rotation toggles " +
            "in one screen. This opens the panel; tapping the toggle is a separate step.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "key",
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(available().keys.toList()))
                        .put("description", actionHints()),
                ),
            )
            .put("required", JSONArray().put("key"))
            .put("additionalProperties", false),
    )

    /** "home(홈 화면으로 나간다), recents(최근 앱 목록)" 형태의 한 줄. */
    fun actionHints(): String =
        available().entries.joinToString(", ") { (key, action) -> "$key(${action.hint})" }

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val key = arguments.optString("key")
        val action = available()[key]
            ?: return DeviceToolResult.Error(
                code = "UNKNOWN_SYSTEM_ACTION",
                message = "모르는 시스템 동작입니다: $key. " +
                    "가능한 값: ${available().keys.joinToString()}",
            )

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        return if (service.performGlobalAction(action.id)) {
            DeviceToolResult.Success(message = "$key 실행했습니다. ${action.hint}")
        } else {
            // 화면 잠금 중이거나 다른 접근성 동작이 진행 중이면 시스템이 거부한다.
            DeviceToolResult.Error(
                code = "SYSTEM_ACTION_REJECTED",
                message = "$key 을(를) 시스템이 거부했습니다. 잠금화면이거나 " +
                    "이 상태에서는 할 수 없는 동작일 수 있습니다.",
            )
        }
    }
}
