package com.example.mobileguiagent.agent

import android.content.Context
import android.util.Log
import com.example.mobileguiagent.llm.LlamaBridge
import com.example.mobileguiagent.model.UiNode
import java.io.File

/**
 * 기기 안 모델에게 "지금 할 한 줄"을 확인받는다.
 *
 * 무엇을 채울지는 이미 규칙이 정해뒀다(FieldAssign). 모델이 하는 일은 그 한 줄을
 * 그대로 답하는 것뿐이고, 이 클래스는 그 답이 계획과 **글자 하나까지 같은지**
 * 확인한다. 같지 않으면 실행하지 않는다.
 *
 * 왜 대조가 필수인가:
 *   1.2B는 그럴듯하게 틀린다. 실측으로 "서울시 중구 세종대로 110"을 "서울 중구
 *   세종대로 110"으로 한 글자 흘렸다. 사람 눈에는 같은 주소로 보이지만 배송지로는
 *   다른 값이다. 모델 답을 그대로 입력하면 이런 것이 그대로 들어간다. 그래서
 *   모델 답은 실행할 값이 아니라 계획과 맞춰볼 대조본으로만 쓴다.
 *
 * 왜 그러면서도 모델을 부르는가:
 *   모델이 계획을 못 읽으면 화면이 계획과 어긋났다는 뜻이다. 규칙만으로는
 *   "칸은 찾았는데 화면이 예상과 다르다"를 알아채지 못한다.
 *
 * 프롬프트에는 금고 값이 그대로 실린다. 기기 밖으로 나가지 않지만 logcat도
 * 기기 밖이다 — 이 파일은 프롬프트도 모델 답도 로그에 적지 않는다.
 */
class LocalStep private constructor(private val handle: Long) {

    sealed interface Verdict {
        /** 모델이 계획과 같은 줄을 냈다. */
        data object Confirmed : Verdict

        /** 다른 줄을 냈다. [answered]에는 값이 들어 있을 수 있으니 로그로 내보내지 않는다. */
        data class Mismatch(val answered: String, val attempts: Int) : Verdict

        /** 모델을 부르지 못했다. 부르는 쪽이 코드 실행으로 넘어간다. */
        data class Unavailable(val reason: String) : Verdict
    }

    /**
     * [current]를 실행해도 되는지 모델에게 물어본다.
     *
     * 두 번 묻되 두 번째는 온도를 준다. 프롬프트는 두 번 다 같다 — 실측으로
     * 화면 목록을 빼면 정확도가 3/3에서 2/3으로 떨어져서(비밀번호 단계에서
     * 틀렸다), 다시 물을 때 프롬프트를 줄이는 것은 더 나쁜 쪽으로 가는 일이다.
     * 대신 그리디를 풀어 다른 표본을 뽑는다. 그리디로 다시 물으면 글자 하나까지
     * 같은 답이 돌아와 재시도가 아무 일도 하지 않는다.
     */
    /**
     * @param secrets 로그에서 가릴 값들. 어긋났을 때 무엇이 어긋났는지 보려면
     *   모델 답을 봐야 하는데, 그 답에는 금고 값이 들어 있다. 값만 자리표시자로
     *   바꾸면 형태는 그대로 남아서 원인은 보이고 값은 새지 않는다.
     */
    fun confirm(
        steps: List<FieldAssign.Step>,
        current: FieldAssign.Step,
        nodes: List<UiNode>,
        packageName: String,
        secrets: Collection<String>,
    ): Verdict {
        val prompt = promptFor(steps, current, renderScreen(nodes, packageName))
        var last = ""
        for ((attempt, temperature) in listOf(0.0f, RETRY_TEMPERATURE).withIndex()) {
            val raw = LlamaBridge.nativeChat(handle, SYSTEM_PROMPT, prompt, MAX_TOKENS, temperature)
            if (raw.startsWith("ERROR:")) {
                return Verdict.Unavailable(raw.removePrefix("ERROR:").trim())
            }
            last = firstLine(raw)
            if (last == current.line) {
                if (attempt > 0) Log.i(TAG, "2차 시도에서 일치")
                return Verdict.Confirmed
            }
            Log.i(TAG, "${attempt + 1}차 대조 불일치\n  계획: ${redact(current.line, secrets)}" +
                "\n  모델: ${redact(last, secrets)}")
        }
        return Verdict.Mismatch(last, attempts = 2)
    }

    fun close() = LlamaBridge.nativeFree(handle)

    companion object {
        private const val TAG = "LocalStep"

        /** eval/brains.py의 LOCAL_SYSTEM_PROMPT를 그대로 옮긴 것. */
        const val SYSTEM_PROMPT = "휴대폰 화면을 조작하는 도우미. 한 줄로만 답한다."

        const val MODEL_FILE = "model.gguf"

        /** 계획 + 화면 목록이 들어가므로 확인 테스트의 512로는 모자란다. */
        private const val N_CTX = 2048
        private const val N_THREADS = 4

        /** 한 줄이면 충분하다. 넘치면 어차피 대조에서 걸린다. */
        private const val MAX_TOKENS = 48

        /** 다시 물을 때만 쓴다. 그리디에서 살짝 벗어날 만큼만. */
        private const val RETRY_TEMPERATURE = 0.3f

        /** 화면 줄 수 상한. eval/agent.py의 MAX_PROMPT_NODES와 같은 뜻이다. */
        private const val MAX_PROMPT_NODES = 40

        fun modelFile(context: Context): File = File(context.getExternalFilesDir(null), MODEL_FILE)

        /**
         * 모델을 연다. 파일이 없거나 로딩에 실패하면 null — 부르는 쪽은 모델 없이
         * 하던 대로 진행한다. 모델이 없다고 로그인이 막히면 안 된다.
         */
        fun open(context: Context): LocalStep? {
            val file = modelFile(context)
            if (!file.isFile || !file.canRead()) {
                Log.i(TAG, "모델 파일이 없어 코드 실행으로 진행합니다: ${file.absolutePath}")
                return null
            }
            val started = System.currentTimeMillis()
            val handle = runCatching { LlamaBridge.nativeLoadModel(file.absolutePath, N_CTX, N_THREADS) }
                .onFailure { Log.w(TAG, "모델 로딩 중 예외", it) }
                .getOrDefault(0L)
            if (handle == 0L) {
                Log.w(TAG, "모델을 열지 못해 코드 실행으로 진행합니다")
                return null
            }
            Log.i(TAG, "모델 준비 ${System.currentTimeMillis() - started} ms")
            return LocalStep(handle)
        }

        /**
         * 모델이 낸 답에서 대조할 한 줄을 고른다.
         *
         * 앞뒤 공백만 턴다. 안쪽은 손대지 않는다 — 값이 한 글자라도 달라졌다면
         * 그건 다른 값이고, 여기서 다듬어 맞춰주면 대조하는 의미가 없어진다.
         */
        fun firstLine(raw: String): String =
            raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        /**
         * 로그에 남길 수 있게 값만 가린다.
         *
         * 긴 값부터 지운다. 짧은 값이 긴 값의 조각이면(아이디가 비밀번호에 들어
         * 있는 경우가 실제로 있다) 짧은 것을 먼저 지웠을 때 긴 값의 나머지가
         * 그대로 남는다.
         */
        fun redact(text: String, secrets: Collection<String>): String =
            secrets.filter { it.isNotBlank() }
                .sortedByDescending { it.length }
                .fold(text) { acc, secret -> acc.replace(secret, "<값 ${secret.length}자>") }

        /**
         * eval/skills.py의 handoff_text()를 옮긴 것. 지킬 것이 셋 있고 모두 실측이다.
         *
         *   한 번에 한 줄만    한 칸씩 10/10, 한꺼번에 0/10
         *   fill 형식         type 형식 0/10, fill 형식 10/10 (FieldAssign이 만든다)
         *   끝난 단계도 값과 함께   값 예시 0개면 0/20, 3개면 20/20
         *
         * [screen]이 null이면 화면 목록을 뺀다. 2차 시도에서 쓴다.
         */
        fun promptFor(
            steps: List<FieldAssign.Step>,
            current: FieldAssign.Step,
            screen: String?,
        ): String = buildString {
            append("정해진 순서대로 하나씩 실행합니다.\n")
            steps.forEach { step ->
                val mark = when {
                    step.done -> "[완료]"
                    step === current -> "→"
                    else -> "     "
                }
                append("  $mark ${step.line}   (${step.why})\n")
            }
            if (screen != null) {
                append("\n").append(screen).append("\n")
            }
            // 베낄 한 줄을 맨 끝, "답:" 바로 앞에 둔다.
            //
            // 답 형식 안내("형태: fill node_번호 값 / tap node_번호 / done")는 넣지
            // 않는다. 파이썬 쪽에는 있지만 거기서는 안내문의 동사가 type이고 계획
            // 줄은 fill이라 서로 달랐다. 앱에서는 둘 다 fill이라 안내문 자체가
            // 베낄 대상이 된다 — 실기기에서 모델이 이렇게 답했다.
            //
            //     계획: fill node_16 <값>
            //     모델: fill node_16 <값> / tap node_16 / done
            //
            // 값까지 정확히 옮겨 적고는 안내문의 나머지를 이어 붙였다.
            append("\n지금 할 것: ${current.line}\n이 한 줄을 그대로 답하세요.\n답:")
        }

        /** eval/agent.py의 render_screen()을 옮긴 것. JSON보다 토큰이 훨씬 적다. */
        fun renderScreen(nodes: List<UiNode>, packageName: String): String = buildString {
            append("SCREEN (app: $packageName)")
            var shown = 0
            for (node in nodes) {
                val label = (node.text ?: node.contentDescription ?: node.hint.orEmpty())
                    .replace("\n", " ")
                if (label.isBlank() && node.range == null) continue
                val flag = when {
                    node.range != null -> "set"
                    node.editable && node.password -> "type,비밀번호"
                    node.editable -> "type"
                    node.scrollable && !node.clickable && label.isBlank() -> "scroll"
                    else -> "tap"
                }
                append("\n${node.id} [$flag] ${label.take(60)}")
                if (++shown >= MAX_PROMPT_NODES) {
                    append("\n... (더 있음)")
                    break
                }
            }
        }
    }
}
