package com.example.mobileguiagent.device

import android.os.Handler
import android.os.Looper
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 입력창에 글자를 넣은 뒤 "검색"을 누르는 일을 대신한다.
 *
 * 검색창 옆 돋보기 아이콘은 라벨이 없을 때가 많다. 그러면 부르는 쪽이 화면에서
 * 그럴듯한 것을 골라 누르게 되는데, 그 자리에 있는 게 돋보기가 아니라 필터나
 * 음성검색인 경우가 있다. 안드로이드가 입력창마다 정해둔 확인 동작(검색·이동·완료)이
 * 있으므로 그것을 부른다. 무엇을 누를지 고르는 문제가 아예 사라진다.
 *
 * 왜 화면이 바뀌었는지까지 보는가:
 *   ACTION_IME_ENTER가 true를 돌려주는 것은 "동작을 받았다"까지다. 실제로 제출이
 *   일어났다는 뜻이 아니다. 실측으로 구글 검색창(WebView)에서 true를 받고도 검색이
 *   실행되지 않았다. 그대로 성공이라고 답하면 부르는 쪽은 검색 결과 화면에 있다고
 *   믿고 다음 단계로 넘어간다 — 확인 안 하느니만 못한 답이다. 그래서 전후 화면을
 *   비교해 바뀌지 않았으면 바뀌지 않았다고 말한다.
 *
 * 팀원 브랜치에서 가져왔다. 접근성 트리를 읽으므로 메인 스레드에서 실행한다.
 */
object SubmitTextDeviceTool : DeviceTool {
    const val NAME = "submit_text"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Submits the current input using its Android keyboard action " +
            "(Search, Go, or Done). Use this after typing instead of hunting for an " +
            "unlabeled icon next to the field. Takes no arguments.",
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

        val before = AtomicReference<String?>(null)
        val submitted = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            before.set(service.captureSnapshot()?.fingerprint?.hash)
            submitted.set(service.submitFirstEditable())
            latch.countDown()
        }
        if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return DeviceToolResult.Error(
                code = "ACTION_TIMEOUT",
                message = "submit_text 응답 시간이 초과됐습니다.",
            )
        }
        if (!submitted.get()) {
            return DeviceToolResult.Error(
                code = "NO_SUBMITTABLE_INPUT",
                message = "확인 동작을 지원하는 입력창을 찾지 못했습니다. " +
                    "입력창을 먼저 채웠는지 확인하세요.",
            )
        }

        // 화면이 반응할 시간을 준다. 검색은 대개 새 화면을 그린다.
        val changed = screenChangedFrom(service, before.get())
        return DeviceToolResult.Success(
            message = if (changed) {
                "입력창의 검색/완료 동작을 실행했고 화면이 바뀌었습니다."
            } else {
                "검색/완료 동작을 보냈지만 화면이 그대로입니다. " +
                    "제출되지 않았을 수 있습니다 — device_observe로 확인하고, " +
                    "필요하면 화면의 검색 버튼을 직접 누르세요."
            },
        )
    }

    /** 제출 뒤 화면이 실제로 바뀌었는지 본다. 바뀔 때까지 짧게 되묻는다. */
    private fun screenChangedFrom(
        service: AgentAccessibilityService,
        before: String?,
    ): Boolean {
        if (before == null) return false
        repeat(SETTLE_ATTEMPTS) {
            Thread.sleep(SETTLE_INTERVAL_MS)
            val after = AtomicReference<String?>(null)
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                after.set(service.captureSnapshot()?.fingerprint?.hash)
                latch.countDown()
            }
            latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val hash = after.get()
            if (hash != null && hash != before) return true
        }
        return false
    }

    private const val MAIN_THREAD_TIMEOUT_MS = 3_000L
    private const val SETTLE_ATTEMPTS = 4
    private const val SETTLE_INTERVAL_MS = 400L
}
