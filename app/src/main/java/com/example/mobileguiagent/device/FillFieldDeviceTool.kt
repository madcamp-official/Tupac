package com.example.mobileguiagent.device

import android.os.Handler
import android.os.Looper
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.secret.SecretVault
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 포커스된 입력창을 금고의 값으로 채운다.
 *
 * device_type_text와 다른 점은 값이 어디서 오느냐다. type_text는 부르는 쪽이
 * 글자를 들고 와야 하므로, 개인정보를 넣으려면 그 값이 맥의 파이썬 프로세스와
 * adb를 거쳐야 한다. 이 도구는 필드 이름만 받고 값은 폰 안에서 꺼낸다.
 *
 * 응답에도 값을 싣지 않는다. "password를 넣었다"까지만 알려준다. 성공 여부를
 * 넘어선 정보를 주면 금고를 둔 의미가 없다.
 *
 * 먼저 채울 칸을 tap해서 포커스해야 한다. 포커스가 없으면 화면의 첫 입력창에
 * 들어가는데, 로그인 화면처럼 칸이 둘이면 엉뚱한 곳에 비밀번호가 들어간다.
 */
object FillFieldDeviceTool : DeviceTool {
    const val NAME = "fill_field"

    private const val TIMEOUT_MS = 3_000L

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Fills the focused input with a value stored on the device. " +
            "Takes only the field name; the value never leaves the phone and is not " +
            "returned. Tap the field first to focus it.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "field",
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(SecretVault.FIELDS.keys.toList()))
                        .put("description", fieldHints()),
                ),
            )
            .put("required", JSONArray().put("field"))
            .put("additionalProperties", false),
    )

    fun fieldHints(): String =
        SecretVault.FIELDS.entries.joinToString(", ") { (key, hint) -> "$key($hint)" }

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val field = arguments.optString("field")
        if (!SecretVault.FIELDS.containsKey(field)) {
            return DeviceToolResult.Error(
                code = "UNKNOWN_FIELD",
                message = "모르는 필드입니다: $field. 가능한 값: " +
                    SecretVault.FIELDS.keys.joinToString(),
            )
        }

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        val value = SecretVault.reveal(service, field)
            ?: return DeviceToolResult.Error(
                code = "FIELD_NOT_SET",
                message = "$field 값이 저장돼 있지 않습니다. 앱 화면에서 먼저 등록하세요.",
            )

        // 노드 액션은 메인 스레드에서 (click/type과 동일 패턴).
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(service.setTextOnFirstEditable(value))
            latch.countDown()
        }
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return DeviceToolResult.Error(
                code = "FILL_TIMEOUT",
                message = "입력 응답 시간이 초과됐습니다.",
            )
        }

        return if (result.get()) {
            // 값은 싣지 않는다. 무엇을 넣었는지만.
            DeviceToolResult.Success(message = "$field 값을 입력했습니다.")
        } else {
            DeviceToolResult.Error(
                code = "NO_EDITABLE_FIELD",
                message = "입력 가능한 칸을 찾지 못했습니다. 먼저 채울 칸을 tap 하세요.",
            )
        }
    }
}
