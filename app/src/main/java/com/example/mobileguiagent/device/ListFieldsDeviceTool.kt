package com.example.mobileguiagent.device

import android.os.Handler
import android.os.Looper
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.secret.SecretVault
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 금고에 어떤 필드가 등록돼 있는지 알려준다. 이름만, 값은 절대 아니다.
 *
 * 에이전트가 fill_field를 부르기 전에 "이 폰에 비밀번호가 등록돼 있나"를 알아야
 * 헛수고를 안 한다. 없는 필드를 채우려다 실패하고 다시 판단하는 왕복이 줄어든다.
 */
object ListFieldsDeviceTool : DeviceTool {
    const val NAME = "list_fields"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Lists which personal-data fields are stored on the device. " +
            "Returns field names only, never values.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
            .put("additionalProperties", false),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        val profile = SecretVault.storedProfileFields(service)
        // 계정은 지금 화면의 앱 것만 알려준다. 등록된 앱을 전부 늘어놓으면 그
        // 목록 자체가 개인정보다 — 지금 하는 일과 아무 상관 없는 앱을 쓴다는
        // 사실이 부르는 쪽에 남는다. 값을 꺼내는 쪽(fill_field·fill_secrets)은
        // 이미 화면의 앱 것만 쓰므로, 알려주는 범위도 같아야 어긋나지 않는다.
        // 접근성 트리를 읽으므로 메인 스레드에서 본다. 다른 device tool과 같다.
        val owner = AtomicReference<SecretVault.AccountOwner?>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            owner.set(SecretVault.accountOwner(service))
            latch.countDown()
        }
        latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val shown = owner.get()?.shown ?: "알 수 없는 화면"
        val account = owner.get()?.service
            ?.let { app -> SecretVault.storedAccountFields(service, app) }
            ?: emptyList()

        val parts = buildList {
            if (profile.isNotEmpty()) add("공통 정보: ${profile.joinToString()}")
            if (account.isNotEmpty()) {
                add("이 화면(${shown})의 계정: ${account.joinToString()}")
            }
        }
        return DeviceToolResult.Success(
            message = when {
                parts.isEmpty() ->
                    "등록된 값이 없습니다. 앱의 \"내 정보\" 화면에서 먼저 등록하세요."
                account.isEmpty() ->
                    parts.joinToString(" / ") +
                        " / 이 화면(${shown})의 계정은 등록돼 있지 않습니다."
                else -> parts.joinToString(" / ")
            },
        )
    }

    private const val MAIN_THREAD_TIMEOUT_MS = 3_000L
}
