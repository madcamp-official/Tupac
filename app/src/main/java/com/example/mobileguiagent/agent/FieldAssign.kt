package com.example.mobileguiagent.agent

import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.secret.SecretVault

/**
 * 어느 칸에 어느 값이 들어가는지 앱이 정한다.
 *
 * 왜 앱 안에서 정하는가:
 *   한동안 이 판단은 맥북의 파이썬이 했다. 그러려면 금고 값이 도구 호출로 폰을
 *   나와 맥북 메모리를 거쳐 다시 폰으로 들어와야 했다. 클라우드에 가지 않을 뿐,
 *   "개인정보는 기기에서 처리한다"는 말과는 어긋난다. 판단이 앱 안에 있으면 값이
 *   앱 프로세스 밖으로 한 번도 나가지 않고, 값을 내주던 도구도 없앨 수 있다.
 *
 * 왜 모델이 아니라 규칙이 정하는가:
 *   실측으로 1.2B는 "화면을 보고 아이디 칸을 찾아라" 수준도 해내지 못했다 —
 *   비밀번호만 세 번 반복해 넣거나, 이력 형식을 흉내낸 문자열을 그대로 칸에
 *   집어넣었다. 프롬프트를 줄여도 나아지지 않았다. 반면 칸을 고르는 일은 규칙으로
 *   충분히 정확하다(아래 근거). 모델에게는 "이 줄을 실행하라"만 남긴다.
 *
 * 칸을 고르는 근거는 둘이다.
 *
 *   password 플래그   접근성 트리가 알려주는 값이라 라벨보다 확실하다. 로그인
 *                     화면에서 비밀번호가 아닌 입력창은 곧 계정 식별자다.
 *   라벨 매칭         칸이 여럿인 폼에서 쓴다. 실측으로 라벨 10종 10/10.
 *
 * 두 근거 모두 실기기에서 검증했다. 카카오톡 로그인 화면(아이디 칸 라벨이
 * "이메일 또는 전화번호"라 라벨로는 못 맞추는 곳)과 크롬 배송지 폼에서다.
 */
object FieldAssign {

    /** 제출 버튼으로 볼 말과, 로그인 화면에 같이 있어서 눌러선 안 되는 말. */
    private val SUBMIT_WORDS = listOf("로그인", "signin", "login", "확인", "다음", "계속")
    private val NOT_SUBMIT_WORDS = listOf(
        "찾기", "가입", "취소", "다른", "간편", "재설정", "도움", "문의", "만들기",
    )

    /** 브라우저로 볼 패키지 조각. 로그인을 웹으로 넘기는 앱이 많아 자주 마주친다. */
    private val BROWSER_MARKERS = listOf(
        "chrome", "browser", "firefox", "sbrowser", "whale", "opera", "edge",
    )

    /** 제출 버튼 라벨로 보기에는 너무 긴 글자 수. 안내 문구를 거르려는 것이다. */
    private const val SUBMIT_LABEL_MAX = 12

    /**
     * 브라우저가 그린 자기 UI(주소창·탭 버튼)인지. 웹 페이지 내용이 아니다.
     *
     * 크롬 주소창은 editable이라 입력창 목록에 그대로 섞인다. 아이디를 먼저 받고
     * 다음 화면에서 비밀번호를 받는 로그인에서는 "비밀번호 아닌 입력창"이 주소창
     * 하나뿐이 되어, 아이디가 주소창에 입력된다.
     *
     * 갈라내는 기준은 viewId다. 브라우저 자기 위젯은 "<패키지>:id/..."를 달고
     * 있고(com.android.chrome:id/url_bar), 웹 페이지 칸은 페이지가 정한
     * id("otp-easy-login")거나 아예 없다.
     */
    fun isBrowserUi(node: UiNode, packageName: String): Boolean {
        val lowered = packageName.lowercase()
        if (BROWSER_MARKERS.none { marker -> marker in lowered }) return false
        return node.viewId.orEmpty().startsWith("$packageName:id/")
    }

    /**
     * 그 칸이 무엇인지 알려주는 글자.
     *
     * 빈 입력창은 text가 비어 있고 안내 문구가 hint에만 있는 경우가 있다(크롬의
     * 웹 폼이 그렇다). 셋 다 봐야 어느 앱에서든 칸을 알아본다.
     */
    fun labelOf(node: UiNode): String {
        // 빈 문자열도 없는 것으로 본다. 크롬의 웹 폼은 비어 있는 칸의 text를
        // null이 아니라 ""로 준다. ?: 로만 넘기면 ""가 라벨이 되어 어느 필드에도
        // 안 걸리고, 그 칸은 "화면에 없음"으로 보고된다(실측: 배송지 폼 5칸을
        // 전부 놓쳤다).
        val text = node.text?.takeIf { it.isNotBlank() }
        val description = node.contentDescription?.takeIf { it.isNotBlank() }
        val hint = node.hint?.takeIf { it.isNotBlank() }
        // 입력창은 hint를 먼저 본다. 빈 칸이면 안내 문구가 hint에만 있고, 이미
        // 값이 든 칸이면 text에 라벨이 아니라 그 값이 들어 있다. text를 먼저 보면
        // "받는사람" 칸에 남아 있던 "홍길동"이 라벨로 잡힌다.
        return if (node.editable) {
            (hint ?: description ?: text).orEmpty().trim()
        } else {
            (text ?: description ?: hint).orEmpty().trim()
        }
    }

    /** 공백을 지우고 소문자로. "이메일 주소"와 "이메일주소"를 같게 보려는 것. */
    private fun squash(text: String) = text.lowercase().replace(Regex("\\s+"), "")

    /**
     * 입력창 라벨이 어느 필드인지. 못 정하면 null.
     *
     * 설명은 쉼표로만 쪼갠다. 공백으로도 쪼개면 여러 낱말로 된 이름이 조각나서
     * 엉뚱한 필드에 걸린다. 실측: "이메일 주소"가 ["이메일", "주소"]로 갈라져,
     * 배송지 화면의 "주소" 칸이 email로 잡혔다.
     */
    fun fieldFor(label: String, fields: Map<String, String> = SecretVault.FIELDS): String? {
        val haystack = squash(label)
        if (haystack.isEmpty()) return null

        var best: String? = null
        var bestScore = 0
        for ((key, description) in fields) {
            for (part in description.split(",")) {
                val word = squash(part)
                if (word.length >= 2 && word in haystack && word.length > bestScore) {
                    best = key
                    bestScore = word.length
                }
            }
            if (key in haystack && key.length > bestScore) {
                best = key
                bestScore = key.length
            }
        }
        return best
    }

    /**
     * (노드, 필드) 목록. 화면에 나온 순서 그대로.
     *
     * 아이디 한 칸 + 비밀번호 한 칸이면 플래그로 가른다. 라벨은 앱마다 제각각이라
     * 믿을 게 못 된다 — 카카오톡은 아이디 칸을 "이메일 또는 전화번호"라고 부르는데,
     * 라벨로 맞추면 email(공통 정보)로 가지만 정작 필요한 건 그 앱의 username이다.
     *
     * 칸 구성이 다르면(배송지 폼, 인증번호 화면) 라벨을 본다. 확실한 것만 고르고
     * 나머지는 건드리지 않는다. 모르는 칸을 채우느니 비워두는 편이 낫다.
     */
    fun inputTargets(
        nodes: List<UiNode>,
        packageName: String,
        wanted: Set<String>,
        fields: Map<String, String> = SecretVault.FIELDS,
    ): List<Pair<UiNode, String>> {
        val editables = nodes.filter { it.editable && !isBrowserUi(it, packageName) }
        val passwords = editables.count { it.password }
        val others = editables.size - passwords

        val pairs = if ("password" in wanted && passwords == 1 && others == 1) {
            editables.map { node -> node to if (node.password) "password" else "username" }
        } else {
            editables.mapNotNull { node ->
                val field = if (node.password) "password" else fieldFor(labelOf(node), fields)
                field?.let { node to it }
            }
        }
        return pairs.filter { (_, field) -> field in wanted }
    }

    /**
     * 제출 버튼 노드. 확실하지 않으면 null.
     *
     * 후보가 여럿이면 고르지 않는다. 로그인 화면에서 엉뚱한 버튼을 누르는 건
     * 되돌리기 어렵고(회원가입 흐름으로 빠질 수 있다), 사람이 한 번 누르는 비용보다
     * 크다. 실측: 카카오톡 로그인 화면에서 후보가 정확히 하나("로그인")였다.
     */
    fun submitButton(nodes: List<UiNode>, packageName: String): UiNode? {
        val found = nodes.filter { node ->
            if (node.editable || !node.clickable) return@filter false
            if (isBrowserUi(node, packageName)) return@filter false
            val label = squash(labelOf(node))
            if (label.isEmpty() || label.length > SUBMIT_LABEL_MAX) return@filter false
            if (NOT_SUBMIT_WORDS.any { word -> word in label }) return@filter false
            SUBMIT_WORDS.any { word -> word in label }
        }
        return found.singleOrNull()
    }

    /**
     * 채울 필드를 화면에 나온 순서대로. 노드 번호는 담지 않는다.
     *
     * 노드 번호를 계획에 박아두면 안 된다. 번호는 스냅샷마다 새로 매겨지는데,
     * 한 칸을 채우면 화면이 바뀌면서(예: "입력한 내용 삭제" 버튼이 생긴다) 뒤 칸의
     * 번호가 밀린다. 실측: 아이디를 넣은 뒤 계획의 node_15가 비밀번호 칸이 아니게
     * 되어 "입력창이 아닌 노드입니다"로 세 번 연속 실패했다.
     *
     * 그래서 계획은 "무엇을 채울지"만 정하고, "어느 칸인지"는 매 스텝 다시 찾는다.
     */
    fun planFields(
        nodes: List<UiNode>,
        packageName: String,
        held: Set<String>,
        fields: Map<String, String> = SecretVault.FIELDS,
    ): List<String> = inputTargets(nodes, packageName, held, fields).map { (_, field) -> field }

    /** 계획의 한 단계. line은 기기 안 모델에게 그대로 베끼라고 보여줄 줄이다. */
    data class Step(
        val action: String,
        val field: String?,
        val nodeId: String?,
        val line: String,
        val why: String,
        val done: Boolean,
    )

    /**
     * @param steps   사람과 모델에게 보여줄 전체 계획
     * @param current 지금 할 단계. blocked가 있으면 null
     * @param blocked 짚어줄 줄이 없는 이유. 있으면 부르는 쪽이 멈춘다
     */
    data class Plan(val steps: List<Step>, val current: Step?, val blocked: String?)

    /**
     * 지금 화면 기준의 단계 목록, 지금 할 단계, 막힌 이유.
     *
     * 기기 안 모델의 일은 "짚어준 한 줄을 실행하기" 하나로 한정한다. 그래서 짚어줄
     * 줄이 없으면 — 계획한 칸이 화면에 없거나 제출 버튼을 못 찾으면 — 모델에게
     * 묻지 않고 막힌 이유를 돌려준다. 부르는 쪽이 거기서 멈춘다.
     *
     * 앞선 단계를 건너뛰고 뒤 단계를 짚지 않는다. 아이디를 못 넣었는데 비밀번호로
     * 넘어가거나, 값을 덜 채운 채 제출을 누르는 일이 생긴다.
     */
    fun stepsNow(
        nodes: List<UiNode>,
        packageName: String,
        wanted: Set<String>,
        order: List<String>,
        wantSubmit: Boolean,
        filled: Set<String>,
        submitted: Boolean,
        fields: Map<String, String> = SecretVault.FIELDS,
    ): Plan {
        val found = inputTargets(nodes, packageName, wanted, fields)
            .associate { (node, field) -> field to node }

        val steps = mutableListOf<Step>()
        var current: Step? = null
        var blocked: String? = null

        for (field in order) {
            // 이미 넣은 칸은 화면에서 다시 찾지 않는다. 값을 넣으면 라벨이 값으로
            // 바뀌어 더는 그 필드로 안 잡히는데(빈 칸의 라벨은 hint에만 있다),
            // 그걸 "칸이 없음"으로 적으면 끝난 일을 못 한 일처럼 보여주게 된다.
            if (field in filled) {
                steps += Step("fill", field, found[field]?.id,
                              "fill ${found[field]?.id ?: "-"} $field",
                              "끝남", done = true)
                continue
            }
            val node = found[field]
            // 줄에는 필드 이름만 둔다. 예전에는 값을 함께 적었는데, 그건 기기 안
            // 모델이 곁의 예시를 베껴 답하게 하려던 것이었다. 그 모델을 걷어낸
            // 뒤로 이 줄을 읽는 것은 code뿐이라 값을 담을 이유가 없다 — 담으면
            // 계획이 오가는 내내 평문이 함께 다닌다.
            val step = Step(
                action = "fill",
                field = field,
                nodeId = node?.id,
                line = if (node != null) "fill ${node.id} $field"
                       else "(화면에 $field 칸이 없음)",
                why = if (node != null) "${labelOf(node).take(20)} 칸" else "$field — 못 찾음",
                done = false,
            )
            steps += step
            if (current == null && blocked == null) {
                if (node != null) current = step else blocked = "${field}를 넣을 칸이 지금 화면에 없습니다"
            }
        }

        if (wantSubmit) {
            val button = submitButton(nodes, packageName)
            val step = Step(
                action = "tap",
                field = null,
                nodeId = button?.id,
                line = if (button != null) "tap ${button.id}" else "(제출 버튼을 못 찾음)",
                why = "제출 — ${button?.let { labelOf(it).take(20) } ?: "못 찾음"}",
                done = submitted,
            )
            steps += step
            if (current == null && blocked == null && !submitted) {
                if (button != null) current = step else blocked = "제출 버튼을 화면에서 찾지 못했습니다"
            }
        }

        val finish = Step("done", null, null, "done", "마무리", done = false)
        steps += finish
        if (current == null && blocked == null) current = finish
        return Plan(steps, current, blocked)
    }
}
