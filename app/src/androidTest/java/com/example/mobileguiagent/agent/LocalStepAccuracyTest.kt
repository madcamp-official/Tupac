package com.example.mobileguiagent.agent

import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mobileguiagent.model.UiNode
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 기기 안 모델이 실제 앱 화면에서 얼마나 맞추는지 넓게 잰다.
 *
 * LocalStepTest는 카카오톡 로그인 한 화면으로 붙는지를 봤다. 여기서는 그 다음
 * 질문에 답한다 — 다른 앱, 다른 값 모양에서도 되는가.
 *
 * 재는 대상은 확인 한 번이다. 모델은 계획을 읽고 지금 할 한 줄을 그대로 답해야
 * 하고, 글자 하나라도 다르면 불일치다. 실제 흐름에서는 불일치가 두 번이면 멈춘다.
 *
 * 화면은 전부 실기기에서 본 특징을 옮긴 것이다. 카카오톡 아이디 칸 라벨,
 * 쿠팡이 크롬 커스텀탭으로 여는 웹 로그인, 크롬 배송지 폼의 hint 전용 라벨.
 *
 *   adb shell am instrument -w \
 *     -e class com.example.mobileguiagent.agent.LocalStepAccuracyTest \
 *     com.example.mobileguiagent.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class LocalStepAccuracyTest {

    private val tag = "LocalStepAccuracy"

    private fun node(
        id: String,
        text: String? = null,
        hint: String? = null,
        viewId: String? = null,
        editable: Boolean = false,
        clickable: Boolean = false,
        password: Boolean = false,
    ) = UiNode(
        id = id, text = text, contentDescription = null, hint = hint,
        className = null, viewId = viewId, clickable = clickable, editable = editable,
        scrollable = false, enabled = true, checked = null,
        bounds = Rect(), depth = 1, password = password,
    )

    /** 카카오톡 로그인. 아이디 칸 라벨이 "이메일 또는 전화번호"라 라벨로는 못 가른다. */
    private val kakao = listOf(
        node("node_1", text = "카카오톡"),
        node("node_2", text = "카카오계정으로 로그인"),
        node("node_15", hint = "이메일 또는 전화번호", editable = true),
        node("node_18", hint = "비밀번호", editable = true, password = true),
        node("node_20", text = "로그인", clickable = true),
        node("node_21", text = "카카오계정 또는 비밀번호 찾기", clickable = true),
        node("node_22", text = "새로운 카카오계정 만들기", clickable = true),
    )

    /**
     * 쿠팡 로그인. 앱이 아니라 크롬 커스텀탭이라 패키지가 com.android.chrome이고,
     * 주소창이 editable로 섞여 들어온다. 웹 페이지 칸은 페이지가 정한 id를 단다.
     */
    private val coupang = listOf(
        node("node_47", text = "login.coupang.com",
             viewId = "com.android.chrome:id/url_bar", editable = true),
        node("node_9", text = "쿠팡"),
        node("node_12", hint = "아이디(이메일)", viewId = "login-email-input", editable = true),
        node("node_14", hint = "비밀번호", viewId = "login-password-input",
             editable = true, password = true),
        node("node_16", text = "로그인", viewId = "login-submit", clickable = true),
        node("node_18", text = "아이디/비밀번호 찾기", clickable = true),
    )

    /** 크롬 배송지 폼. 빈 칸은 text가 ""이고 라벨이 hint에만 있다. */
    private val form = listOf(
        node("node_15", text = "", hint = "받는사람", viewId = "a", editable = true),
        node("node_17", text = "", hint = "연락처", viewId = "b", editable = true),
        node("node_20", text = "", hint = "우편번호", viewId = "c", editable = true),
        node("node_22", text = "", hint = "주소", viewId = "d", editable = true),
        node("node_25", text = "", hint = "상세주소", viewId = "e", editable = true),
        node("node_90", text = "저장", clickable = true),
    )

    /** (이름, 화면, 패키지, 값, 채울 순서, 제출까지 하는가) */
    private val jobs = listOf(
        Job("카카오톡 로그인", kakao, "com.kakao.talk",
            mapOf("username" to "minsu", "password" to "hunter2!"),
            listOf("username", "password"), submit = true),
        Job("쿠팡 웹 로그인", coupang, "com.android.chrome",
            mapOf("username" to "minsu@example.com", "password" to "Cp!2026pass"),
            listOf("username", "password"), submit = true),
        Job("배송지 폼", form, "com.android.chrome",
            mapOf(
                "name" to "홍길동",
                "phone" to "01000000000",
                "postcode" to "04524",
                "address" to "서울시 중구 세종대로 110",
                "address_detail" to "3층 401호",
            ),
            listOf("name", "phone", "postcode", "address", "address_detail"), submit = false),
    )

    private class Job(
        val name: String,
        val nodes: List<UiNode>,
        val packageName: String,
        val values: Map<String, String>,
        val order: List<String>,
        val submit: Boolean,
    )

    /** 한 단계씩 [완료]가 쌓이는 계획을 순서대로 만든다. 실제 흐름과 같은 모양이다. */
    private fun stagesOf(job: Job): List<Pair<FieldAssign.Plan, FieldAssign.Step>> {
        val stages = mutableListOf<Pair<FieldAssign.Plan, FieldAssign.Step>>()
        val filled = mutableSetOf<String>()
        var submitted = false
        while (true) {
            val plan = FieldAssign.stepsNow(
                nodes = job.nodes, packageName = job.packageName, values = job.values,
                order = job.order, wantSubmit = job.submit,
                filled = filled, submitted = submitted,
            )
            val step = plan.current ?: break
            if (step.action == "done") break
            stages += plan to step
            if (step.action == "tap") submitted = true else step.field?.let { filled += it }
        }
        return stages
    }

    @Test
    fun modelHandlesRealScreens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "모델 파일이 없어 건너뜁니다",
            LocalStep.modelFile(context).isFile,
        )
        val local = LocalStep.open(context)
        requireNotNull(local) { "모델을 열지 못했습니다" }

        var hitAll = 0
        var stepAll = 0
        val allMillis = mutableListOf<Long>()
        try {
            for (job in jobs) {
                val stages = stagesOf(job)
                var hits = 0
                val millis = mutableListOf<Long>()
                for ((plan, step) in stages) {
                    val started = System.currentTimeMillis()
                    val verdict = local.confirm(
                        plan.steps, step, job.nodes, job.packageName, job.values.values,
                    )
                    val took = System.currentTimeMillis() - started
                    millis += took
                    val ok = verdict is LocalStep.Verdict.Confirmed
                    if (ok) hits++
                    // 계획 줄에는 금고 값이 들어 있다. 어느 칸인지(why)만 남긴다.
                    Log.i(tag, "  ${if (ok) "O" else "X"} ${job.name} / ${step.why} — $took ms")
                }
                hitAll += hits
                stepAll += stages.size
                allMillis += millis
                Log.i(
                    tag,
                    "${job.name}: $hits/${stages.size}, " +
                        "단계당 평균 ${millis.average().toInt()} ms, 합계 ${millis.sum()} ms",
                )
            }
        } finally {
            local.close()
        }
        Log.i(
            tag,
            "전체 $hitAll/$stepAll, 단계당 평균 ${allMillis.average().toInt()} ms, " +
                "최대 ${allMillis.max()} ms, RSS 최대 ${LlamaPeakMib()} MiB",
        )
    }

    private fun LlamaPeakMib(): Long =
        com.example.mobileguiagent.llm.LlamaBridge.peakRssKb() / 1024
}
