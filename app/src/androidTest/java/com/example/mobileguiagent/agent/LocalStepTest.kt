package com.example.mobileguiagent.agent

import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mobileguiagent.llm.LlamaBridge
import com.example.mobileguiagent.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * 기기 안 모델이 계획의 한 줄을 그대로 답하는지 실기기에서 잰다.
 *
 * 카카오톡을 띄우지 않고도 같은 것을 잰다 — 화면은 실기기에서 본 특징(아이디 칸
 * 라벨이 "이메일 또는 전화번호")을 그대로 옮겼고, 계획은 FieldAssign이 실제로
 * 만든다. 여기서 재는 것은 칸 배정이 아니라 모델이 그 계획을 읽어내는지다.
 *
 * `gradlew connectedAndroidTest`로 돌리지 말 것. 그 태스크는 끝나고 앱을
 * 언인스톨하는데, 그러면 금고(SecretVault)와 기기에 올려둔 모델이 함께 지워진다.
 *
 *   adb install -r app-debug.apk && adb install -r app-debug-androidTest.apk
 *   adb shell am instrument -w \
 *     -e class com.example.mobileguiagent.agent.LocalStepTest \
 *     com.example.mobileguiagent.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class LocalStepTest {

    private val tag = "LocalStepSpike"

    private fun node(
        id: String,
        text: String? = null,
        hint: String? = null,
        editable: Boolean = false,
        clickable: Boolean = false,
        password: Boolean = false,
    ) = UiNode(
        id = id,
        text = text,
        contentDescription = null,
        hint = hint,
        className = null,
        viewId = null,
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = true,
        checked = null,
        bounds = Rect(),
        depth = 1,
        password = password,
    )

    /** 카카오톡 로그인 화면. 주변 버튼까지 넣어야 화면 목록의 길이가 실제와 비슷해진다. */
    private fun loginScreen() = listOf(
        node("node_1", text = "카카오톡"),
        node("node_2", text = "카카오계정으로 로그인"),
        node("node_15", hint = "이메일 또는 전화번호", editable = true),
        node("node_18", hint = "비밀번호", editable = true, password = true),
        node("node_20", text = "로그인", clickable = true),
        node("node_21", text = "카카오계정 또는 비밀번호 찾기", clickable = true),
        node("node_22", text = "새로운 카카오계정 만들기", clickable = true),
        // "QR코드 로그인"으로 두면 제출 버튼 후보가 둘이 되어 FieldAssign이 막는다.
        // 여기서 재려는 것은 그 판단이 아니라 모델이 계획을 읽는지다.
        node("node_23", text = "QR코드로 연결", clickable = true),
    )

    private val values = mapOf("username" to "minsu", "password" to "hunter2!")

    /** 로그인 3단계의 (계획, 지금 할 줄). filled가 늘어나며 [완료] 줄이 붙는다. */
    private fun stages(nodes: List<UiNode>): List<Pair<FieldAssign.Plan, FieldAssign.Step>> =
        listOf(
            emptySet(),
            setOf("username"),
            setOf("username", "password"),
        ).mapNotNull { filled ->
            val plan = FieldAssign.stepsNow(
                nodes = nodes,
                packageName = PACKAGE,
                values = values,
                order = listOf("username", "password"),
                wantSubmit = true,
                filled = filled,
                submitted = false,
            )
            plan.current?.takeIf { it.action != "done" }?.let { plan to it }
        }

    /**
     * 화면 목록을 넣을 때와 뺄 때를 나란히 잰다.
     *
     * 화면 목록은 파이썬 쪽 프롬프트에 있던 것이라 그대로 옮겨왔는데, 실기기에서는
     * 프롬프트가 242토큰이 되고 그 prefill이 시간의 대부분을 먹었다. 계획 줄에 이미
     * 노드 번호가 박혀 있으니 정말 필요한지 확인한다.
     */
    @Test
    fun modelRepeatsEachPlannedLine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "모델 파일이 없어 건너뜁니다: ${LocalStep.modelFile(context).absolutePath}",
            LocalStep.modelFile(context).isFile,
        )
        val nodes = loginScreen()
        val screen = LocalStep.renderScreen(nodes, PACKAGE)
        val total = stages(nodes).size
        val scored = mutableMapOf<String, Int>()

        // (설명, 스레드 수, 화면 목록을 넣을지)
        val configs = listOf(
            Triple("화면 있음/4스레드", 4, true),
            Triple("화면 있음/6스레드", 6, true),
            Triple("화면 없음/4스레드", 4, false),
        )
        for ((label, threads, withScreen) in configs) {
            val handle = LlamaBridge.nativeLoadModel(LocalStep.modelFile(context).absolutePath, 2048, threads)
            assertTrue("모델을 열지 못했습니다", handle != 0L)
            var hits = 0
            try {
                for ((plan, step) in stages(nodes)) {
                    val prompt = LocalStep.promptFor(plan.steps, step, screen.takeIf { withScreen })
                    var answer = ""
                    val elapsed = measureTimeMillis {
                        answer = LlamaBridge.nativeChat(handle, LocalStep.SYSTEM_PROMPT, prompt, 48, 0.0f)
                    }
                    val ok = LocalStep.firstLine(answer) == step.line
                    if (ok) hits++
                    // 답에는 금고 값이 들어 있다. 맞았는지와 길이만 남긴다.
                    Log.i(tag, "[$label] ${step.why} -> ${if (ok) "일치" else "불일치"}, " +
                        "${elapsed} ms (답 ${LocalStep.firstLine(answer).length}자)")
                }
            } finally {
                LlamaBridge.nativeFree(handle)
            }
            scored[label] = hits
            Log.i(tag, "[$label] 대조 $hits/$total, RSS 최대 ${LlamaBridge.peakRssKb() / 1024} MiB")
        }

        // 화면 목록을 빼면 실측으로 비밀번호 단계에서 틀렸다(2/3). 이 단언이 아니라
        // 위 로그가 화면 목록을 계속 싣는 이유의 기록이다.
        assertEquals("화면 목록을 넣고도 계획을 못 읽는다", total, scored["화면 있음/4스레드"])
    }

    private companion object {
        const val PACKAGE = "com.kakao.talk"
    }
}
