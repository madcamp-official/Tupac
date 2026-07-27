package com.example.mobileguiagent.agent

import android.os.Handler
import android.os.Looper
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.secret.SecretVault
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 금고 값을 화면에 채우는 전 과정. 값이 이 파일 밖으로 나가지 않는다.
 *
 * 바깥 모델은 "username과 password가 필요하다"까지만 말한다. 그다음 일 — 금고를
 * 열고, 어느 칸인지 정하고, 넣고, 제출하는 것 — 은 전부 여기서 끝난다. 돌려주는
 * 것은 "무엇을 했는지"뿐이고 "무엇을 넣었는지"는 담지 않는다.
 *
 * 왜 한 덩어리인가:
 *   낱개 도구(fill_field, type_node)로도 같은 일을 할 수 있다. 하지만 그러면
 *   어느 칸에 무엇을 넣을지 바깥에서 정해야 하고, 그러려면 화면과 값을 함께
 *   봐야 한다. 그 순간 개인정보가 나간다.
 *
 * 매 단계 화면을 다시 읽는 이유:
 *   노드 번호는 스냅샷마다 새로 매겨진다. 한 칸을 채우면 화면이 바뀌면서(예:
 *   "입력한 내용 삭제" 버튼이 생긴다) 뒤 칸의 번호가 밀린다. 실측으로 아이디를
 *   넣은 뒤 계획의 node_15가 비밀번호 칸이 아니게 되어 세 번 연속 실패했다.
 *   그래서 계획은 "무엇을 채울지"만 담고, "어느 칸인지"는 매번 다시 찾는다.
 */
object SecretFiller {

    /** 한 칸 채운 뒤 화면이 자리를 잡을 때까지 기다리는 시간. */
    private const val SETTLE_MS = 600L
    private const val MAIN_THREAD_TIMEOUT_MS = 2_000L

    /** 계획 밖으로 새지 않게 두는 상한. 칸 수보다 넉넉하되 무한 반복은 막는다. */
    private const val MAX_ROUNDS = 12

    sealed interface Outcome {
        data class Done(val message: String) : Outcome
        data class Failed(val code: String, val message: String) : Outcome
    }

    /** 도구 설명에 실을 필드 목록. 금고에 필드를 더하면 여기가 따라온다. */
    fun fieldHint(): String =
        SecretVault.FIELDS.entries.joinToString(", ") { (key, hint) -> "$key($hint)" }

    fun fill(
        service: AgentAccessibilityService,
        requested: List<String>,
        submit: Boolean,
    ): Outcome {
        val unknown = requested.filterNot { SecretVault.FIELDS.containsKey(it) }
        if (unknown.isNotEmpty()) {
            return Outcome.Failed(
                "UNKNOWN_FIELD",
                "모르는 필드입니다: ${unknown.joinToString()}. 가능한 값: ${fieldHint()}",
            )
        }

        val first = snapshot(service)
            ?: return Outcome.Failed("NO_SCREEN", "화면을 읽지 못했습니다.")
        ScreenPrivacy.blockedApp(first.packageName)?.let { reason ->
            return Outcome.Failed("SENSITIVE_APP", "$reason 사람이 직접 다뤄야 합니다.")
        }

        val values = readVault(service, requested, first.packageName)
        val missing = requested.filterNot { values.containsKey(it) }
        if (values.isEmpty()) {
            return Outcome.Failed(
                "FIELD_NOT_SET",
                "요청한 값이 이 기기에 등록돼 있지 않습니다: ${missing.joinToString()}. " +
                    "앱의 \"내 정보\" 화면에서 먼저 등록하세요.",
            )
        }
        // 계정은 쌍으로만 쓴다. 아이디 없이 비밀번호만 넣고 로그인을 누르면 반드시
        // 실패하는데, 그 실패가 앱에 따라 시도 횟수로 잡혀 계정이 잠긴다. 되돌릴
        // 수 없는 쪽이므로 화면을 건드리기 전에 멈춘다.
        val halfAccount = missing.filter { SecretVault.isAccountField(it) }
        if (halfAccount.isNotEmpty()) {
            return Outcome.Failed(
                "INCOMPLETE_ACCOUNT",
                "이 앱의 ${halfAccount.joinToString()}이(가) 없어 로그인을 시도하지 않았습니다. " +
                    "반쪽으로 시도하면 계정이 잠길 수 있습니다.",
            )
        }

        return runPlan(service, values, submit, missing)
    }

    private fun runPlan(
        service: AgentAccessibilityService,
        values: Map<String, String>,
        submit: Boolean,
        missing: List<String>,
    ): Outcome {
        val order = mutableListOf<String>()
        val filled = mutableSetOf<String>()
        var submitted = false

        // repeat 대신 for를 쓴다. 람다 안에서는 break/continue를 못 쓴다.
        for (round in 0 until MAX_ROUNDS) {
            val screen = snapshot(service)
                ?: return Outcome.Failed("NO_SCREEN", "화면을 읽지 못했습니다.")

            // 화면에 새로 보이는 칸을 계획에 더한다. 폼이 한 화면보다 길면 아래쪽
            // 칸은 접근성 트리에 아예 없다가 스크롤해야 나타난다.
            FieldAssign.planFields(screen.nodes, screen.packageName, values.keys)
                .forEach { field -> if (field !in order) order += field }

            // 아직 어느 칸에도 못 놓은 값이 있으면 제출하지 않는다. 덜 채운 폼을
            // 보내는 건 되돌리기 어렵다.
            val unplaced = values.keys.filterNot { it in order }
            val plan = FieldAssign.stepsNow(
                nodes = screen.nodes,
                packageName = screen.packageName,
                values = values,
                order = order,
                wantSubmit = submit && unplaced.isEmpty(),
                filled = filled,
                submitted = submitted,
            )

            plan.blocked?.let { reason ->
                return Outcome.Failed(
                    "BLOCKED",
                    "$reason. ${summary(order, filled, submitted, unplaced)} " +
                        "예상과 다른 화면이라 여기서 멈췄습니다.",
                )
            }
            val step = plan.current ?: break
            if (step.action == "done") {
                return Outcome.Done(summary(order, filled, submitted, unplaced + missing))
            }

            val node = screen.nodes.firstOrNull { it.id == step.nodeId }
                ?: return Outcome.Failed("NODE_GONE", "짚어둔 칸이 화면에서 사라졌습니다.")

            when (step.action) {
                "type" -> {
                    val field = step.field ?: continue
                    // 값은 여기서만 꺼내 쓴다. 로그에도 결과 메시지에도 싣지 않는다.
                    val ok = onMainThread {
                        service.setTextOnSnapshotNode(node, screen.packageName, values.getValue(field))
                    }
                    if (ok != true) {
                        return Outcome.Failed(
                            "TYPE_FAILED",
                            "${field}를 넣지 못했습니다. ${summary(order, filled, submitted, unplaced)}",
                        )
                    }
                    filled += field
                }

                "tap" -> {
                    val result = onMainThread { service.clickSnapshotNode(node, screen.packageName) }
                    if (result?.success != true) {
                        return Outcome.Failed(
                            "SUBMIT_FAILED",
                            "값은 다 넣었지만 제출 버튼을 누르지 못했습니다. 직접 눌러주세요.",
                        )
                    }
                    submitted = true
                }
            }
            Thread.sleep(SETTLE_MS)
        }
        return Outcome.Done(summary(order, filled, submitted, emptyList()))
    }

    /** 무엇을 했는지만 적는다. 무엇을 넣었는지는 적지 않는다. */
    private fun summary(
        order: List<String>,
        filled: Set<String>,
        submitted: Boolean,
        skipped: List<String>,
    ): String {
        val done = order.filter { it in filled }
        val head = when {
            done.isEmpty() -> "채운 칸이 없습니다."
            submitted -> "${done.joinToString()}을(를) 넣고 제출했습니다."
            else -> "${done.joinToString()}을(를) 넣었습니다. 제출은 하지 않았습니다."
        }
        val tail = skipped.distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { " ${it.joinToString()}은(는) 넣을 칸을 찾지 못해 비워뒀습니다." }
            .orEmpty()
        return head + tail
    }

    /**
     * 금고에서 값을 꺼낸다. 계정 필드는 지금 화면의 앱 것만 쓴다.
     *
     * 부르는 쪽이 어느 앱 계정인지 고르게 하면, 한 앱의 자격증명이 다른 앱 화면에
     * 들어갈 수 있다. 브라우저면 주소창을 읽어 등록해둔 앱과 맞춘다 — 로그인을
     * 웹으로 넘기는 앱이 많아서다(쿠팡은 크롬 커스텀탭으로 login.coupang.com을 연다).
     */
    private fun readVault(
        service: AgentAccessibilityService,
        requested: List<String>,
        packageName: String,
    ): Map<String, String> {
        val owner = onMainThread {
            service.browserHost()?.let { host -> SecretVault.serviceForHost(service, host) }
                ?: packageName
        }
        return requested.mapNotNull { field ->
            val value = if (SecretVault.isAccountField(field)) {
                owner?.let { SecretVault.reveal(service, field, it) }
            } else {
                SecretVault.reveal(service, field)
            }
            value?.let { field to it }
        }.toMap()
    }

    private fun snapshot(service: AgentAccessibilityService): UiSnapshot? =
        onMainThread { service.captureSnapshot() }

    private fun <T> onMainThread(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<T?>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(runCatching(block).getOrNull())
            latch.countDown()
        }
        latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }
}
