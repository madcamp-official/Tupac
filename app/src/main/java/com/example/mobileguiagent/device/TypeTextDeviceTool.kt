package com.example.mobileguiagent.device

import android.os.Handler
import android.os.Looper
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "텍스트 입력" tool.
 *
 * back/scroll과 같은 뼈대. 팀원의 setTextOnFirstEditable(text)를 감싼다.
 * 현재 화면의 첫 editable(입력창)에 텍스트를 넣는다 — 보통 검색창 하나짜리 화면용.
 * LLM은 입력창을 tap으로 먼저 포커스한 뒤 이 tool을 부르는 흐름이 자연스럽다.
 *
 * MVP 한계: "포커스된" 칸이 아니라 "첫" editable을 대상으로 한다. id+비밀번호처럼
 * 입력창이 여러 개면 잘못된 칸에 들어갈 수 있음 → 나중에 포커스/노드지정 버전으로 개선.
 */
object TypeTextDeviceTool : DeviceTool {
    const val NAME = "type_text"

    private const val TIMEOUT_MS = 3_000L

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Types text into the first editable text field on the current screen " +
            "(e.g. a search or input box). Tap the field first to focus it, then call this. " +
            "Replaces the field's current content.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "text",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "The text to enter into the field."),
                ),
            )
            .put("required", JSONArray().put("text"))
            .put("additionalProperties", false),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        if (!arguments.has("text")) {
            return DeviceToolResult.Error(
                code = "MISSING_TEXT",
                message = "text 인자가 필요합니다.",
            )
        }
        val text = arguments.optString("text")

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        // 노드 액션은 메인 스레드에서 수행 후 latch로 결과를 기다린다(click/tap과 동일 패턴).
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(service.setTextOnFirstEditable(text))
            latch.countDown()
        }

        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return DeviceToolResult.Error(
                code = "TYPE_TIMEOUT",
                message = "텍스트 입력 응답 시간이 초과됐습니다.",
            )
        }

        return if (result.get()) {
            DeviceToolResult.Success(message = "입력했습니다: \"$text\"")
        } else {
            DeviceToolResult.Error(
                code = "NO_EDITABLE_FIELD",
                message = "입력 가능한 텍스트 필드를 찾지 못했습니다. 먼저 입력창을 tap해 포커스하세요.",
            )
        }
    }
}
