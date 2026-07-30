package com.example.mobileguiagent.agent

import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private const val TAG = "SecretFiller"

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
            return Outcome.Failed("FIELD_NOT_SET", whatToDoAbout(missing))
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

        // 이전 채우기의 기억을 버린다. 평문 값을 필요한 창 밖까지 들고 있지 않는다.
        FilledSecrets.clear()

        // 채우기가 끝나면 값을 덮어쓴다. 여기가 값을 가장 오래 들고 있는 자리다 —
        // 화면을 여러 번 훑는 동안 계속 살아 있다.
        return try {
            runPlan(service, values, submit, missing)
        } finally {
            values.values.forEach(SecretVault::wipe)
        }
    }

    private fun runPlan(
        service: AgentAccessibilityService,
        values: Map<String, CharArray>,
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
                wanted = values.keys,
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
                return Outcome.Done(summary(order, filled, submitted, unplaced, missing))
            }

            val node = screen.nodes.firstOrNull { it.id == step.nodeId }
                ?: return Outcome.Failed("NODE_GONE", "짚어둔 칸이 화면에서 사라졌습니다.")

            when (step.action) {
                "fill" -> {
                    val field = step.field ?: continue
                    // 값은 여기서만 꺼내 쓴다. 로그에도 결과 메시지에도 싣지 않는다.
                    val ok = onMainThread {
                        service.setTextOnSnapshotNode(node, screen.packageName, String(values.getValue(field)))
                    }
                    if (ok != true) {
                        return Outcome.Failed(
                            "TYPE_FAILED",
                            "${field}를 넣지 못했습니다. ${summary(order, filled, submitted, unplaced)}",
                        )
                    }
                    filled += field
                    // 넣은 값을 기억해둔다. 이 값이 화면에 남아 다음 관찰에
                    // 실려 나가는 것을 막으려면, 무엇을 넣었는지 알아야 한다.
                    FilledSecrets.remember(field, String(values.getValue(field)))
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

    /**
     * 없는 값에 대해 부르는 쪽이 다음에 할 일을 적는다.
     *
     * 종류에 따라 다르다. 이름·연락처·주소는 사람에게 물어 받아 넣으면 되는
     * 것이다 — 폼을 끝내는 평범한 방법이고, 그걸 설정 화면으로 돌려보내면
     * 하던 일이 끊긴다.
     *
     * 아이디·비밀번호는 물어선 안 된다. 사람이 부르는 쪽에게 적어 보내는 순간
     * 그 값은 이미 폰을 떠난다. 금고를 만든 이유가 그것이라, 없으면 없는 채로
     * 두고 등록할 자리를 알려준다.
     */
    private fun whatToDoAbout(missing: List<String>): String {
        val account = missing.filter { SecretVault.isAccountField(it) }
        val profile = missing.filterNot { SecretVault.isAccountField(it) }
        return listOfNotNull(
            profile.takeIf { it.isNotEmpty() }?.let { fields ->
                "${fields.joinToString()}이(가) 금고에 없습니다. 사람에게 물어보고 " +
                    "device_type_node로 넣으세요."
            },
            account.takeIf { it.isNotEmpty() }?.let { fields ->
                "${fields.joinToString()}은(는) 금고에 없습니다. 물어보지 마세요 — " +
                    "앱의 \"내 정보\" 화면에서 이 앱 계정을 등록해야 합니다."
            },
        ).joinToString(" ")
    }

    /**
     * 무엇을 했는지만 적는다. 무엇을 넣었는지는 적지 않는다.
     *
     * 못 채운 이유를 둘로 나눈다. 칸을 못 찾은 것과 금고에 값이 없는 것은
     * 다음에 할 일이 다르다 — 앞은 화면을 확인할 일이고, 뒤는 사람에게 묻거나
     * 등록할 일이다. 한 문장으로 뭉치면 부르는 쪽이 무엇을 해야 할지 모른다.
     */
    private fun summary(
        order: List<String>,
        filled: Set<String>,
        submitted: Boolean,
        unplaced: List<String>,
        missing: List<String> = emptyList(),
    ): String {
        val done = order.filter { it in filled }
        val head = when {
            done.isEmpty() -> "채운 칸이 없습니다."
            submitted -> "${done.joinToString()}을(를) 넣고 제출했습니다."
            else -> "${done.joinToString()}을(를) 넣었습니다. 제출은 하지 않았습니다."
        }
        val noRoom = unplaced.distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { " ${it.joinToString()}은(는) 넣을 칸을 찾지 못해 비워뒀습니다." }
            .orEmpty()
        val noValue = missing.distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { " " + whatToDoAbout(it) }
            .orEmpty()
        return head + noRoom + noValue
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
    ): Map<String, CharArray> {
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
