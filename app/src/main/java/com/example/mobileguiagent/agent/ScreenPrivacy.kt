package com.example.mobileguiagent.agent

import com.example.mobileguiagent.model.UiNode

/**
 * 화면을 밖으로 내보내기 전에 개인정보를 거른다. eval/privacy.py를 옮긴 것이다.
 *
 * 왜 앱 안에 있어야 하는가:
 *   지금까지 이 문지기는 맥북의 파이썬이었다. 클라우드 모델을 부르기 직전에 화면
 *   글을 다듬었다. 그런데 Claude가 MCP로 직접 붙으면 device_observe의 응답이
 *   그대로 대화창으로 간다 — 그 사이에 파이썬이 없다. 문지기를 앱으로 옮기지
 *   않으면, 개인정보를 지키려고 만든 구조가 정반대가 된다.
 *
 * 이 파일이 지키려는 두 가지:
 *
 * 1. 민감한 화면은 관찰 자체가 나가면 안 된다.
 *    입력 도구만 앱 안에서 처리하는 걸로는 부족하다. 화면 읽기가 텍스트를 통째로
 *    주기 때문에, 이미 입력된 주민번호·계좌번호가 노드 텍스트에 그대로 들어있다.
 *    그래서 판정 단위는 행동이 아니라 화면이다.
 *
 * 2. 평범한 화면에도 개인정보는 섞인다.
 *    실측: Wi-Fi 설정 화면의 SSID가 사용자 실명이었고, 홈 화면 날씨 위젯에 사는
 *    동네가 떠 있었다. 민감 화면이 아니어도 눈에 띄는 값은 가리고 보낸다.
 *
 * 스크린샷을 밖으로 보내지 않는 것도 같은 이유다. 픽셀은 이렇게 가릴 수 없다.
 */
object ScreenPrivacy {

    /**
     * 값 자체를 가린다. 순서가 중요하다 — 앞의 패턴이 먼저 잡는다.
     *
     * 전화번호가 계좌번호보다 먼저 와야 한다. 뒤에 두면 010-1234-5678이 계좌번호로
     * 잡힌다. 계좌번호의 마지막 묶음을 4자리 이상으로 묶은 것도 같은 이유다.
     * \d{2,7}로 두면 "2026-07-26" 같은 날짜가 전부 계좌번호가 됐다.
     */
    private val PATTERNS: List<Pair<String, Regex>> = listOf(
        "주민번호" to Regex("""\b\d{6}\s*[-–]\s*[1-4]\d{6}\b"""),
        "카드번호" to Regex("""\b(?:\d{4}[ -]?){3}\d{4}\b"""),
        "전화번호" to Regex("""\b01[016-9][ -]?\d{3,4}[ -]?\d{4}\b"""),
        "계좌번호" to Regex("""\b\d{2,6}-\d{2,6}-\d{4,7}\b"""),
        "이메일" to Regex("""\b[\w.+-]+@[\w-]+\.[\w.]+\b"""),
        "생년월일" to Regex("""\b(19|20)\d{2}[.\-/](0?[1-9]|1[0-2])[.\-/](0?[1-9]|[12]\d|3[01])\b"""),
    )

    /** 앱 자체가 민감한 경우. 화면 라벨이 무해해 보여도 통째로 막는다. */
    private val SENSITIVE_PACKAGES = listOf(
        "bank", "shinhan", "kookmin", "kbstar", "wooribank", "hanabank", "nonghyup",
        "ibk", "kakaobank", "kbank", "tossbank", "toss", "payco", "kakaopay",
        "samsungpay", "cert", "pass", "npki", "yessign", "keychain",
    )

    /**
     * 화면에 남의 이야기가 그대로 떠 있는 앱들. 대화 내용·메일 본문·사진 설명은
     * 길을 찾는 데 필요 없다. 어느 항목을 누를지만 알면 된다.
     */
    private val CONTENT_PACKAGES = listOf(
        "kakao.talk", "line", "telegram", "whatsapp", "messenger", "facebook",
        "instagram", "discord", "slack", "mms", "messaging", "android.email",
        "gm", "mail", "gallery", "photos", "band", "everytime",
    )

    /**
     * 앱이 사용자에게 건네는 말. 대화라면 좀처럼 쓰지 않는 낱말만 골랐다.
     * "확인", "다시" 같은 흔한 말은 일부러 뺐다 — 대화에도 자주 나온다.
     */
    private val UI_MARKERS = listOf(
        "오류", "실패", "일치하지", "올바르지", "유효하지", "잘못된",
        "인증", "로그인", "비밀번호", "계정", "권한", "네트워크",
        "업데이트", "다시 시도", "다시시도", "사용할 수 없", "입력해", "입력하세요",
    )

    /**
     * 앱이 사용자에게 말할 때 쓰는 격식체 어미. 대화에서는 좀처럼 이렇게 끝나지
     * 않는다("...할래?", "...하자", "...야").
     */
    private val FORMAL_ENDINGS = listOf(
        "습니다", "합니다", "됩니다", "입니다", "없습니다",
        "하세요", "주세요", "세요", "십시오", "하십시오", "하시겠습니까",
    )

    /** 이 길이를 넘는 글은 항목 이름이 아니라 내용으로 본다. */
    private const val CONTENT_LENGTH = 14

    /**
     * 항목이 이보다 적으면 대화상자나 로딩 화면으로 본다. 대화 목록은 항목이 많다.
     * 실측: 로그인 실패 안내가 "안내문 + 확인 버튼" 두 개짜리 화면으로 떴다.
     */
    private const val DIALOG_NODES = 6

    fun labelOf(node: UiNode): String =
        listOfNotNull(node.text, node.contentDescription, node.hint)
            .firstOrNull { it.isNotBlank() }.orEmpty().trim()

    /**
     * 알아볼 수 있는 개인정보를 자리표시자로 바꾼다.
     *
     * 구조는 남긴다. 부르는 쪽은 "여기에 전화번호가 있다"는 것만 알면 되고, 값이
     * 무엇인지는 알 필요가 없다. 통째로 삭제하면 화면을 잘못 읽으므로 자리표시자를
     * 남긴다.
     */
    fun mask(text: String): String {
        var masked = text
        for ((name, pattern) in PATTERNS) {
            masked = pattern.replace(masked, "<$name>")
        }
        return masked
    }

    /**
     * 앱이 건네는 안내·오류 문구로 보이는지.
     *
     * 대화 내용과 안내문을 가르는 확실한 표시가 접근성 트리에는 없다. 그래서
     * 낱말로 가늠하되, 틀렸을 때 손해가 적은 쪽으로 기운다. 안내문을 가려버리면
     * 왜 실패했는지 몰라 막힐 뿐이지만, 대화를 안 가리면 그대로 유출이다.
     *
     * 낱말만으로는 샌다. "비밀번호 알려줄게 나중에 지워라" 같은 대화가 그대로
     * 나갔다. 그래서 말투까지 함께 본다 — 앱은 격식체로 말하고 대화는 그렇지
     * 않다. 둘 다 맞아야 UI로 인정한다.
     */
    fun isUiText(label: String): Boolean {
        if (UI_MARKERS.none { marker -> marker in label }) return false
        val stripped = label.trimEnd(' ', '.', '!', '?', '~', '…')
        return FORMAL_ENDINGS.any { ending -> stripped.endsWith(ending) }
    }

    /**
     * 밖으로 내보낼 라벨을 다듬는다.
     *
     * 두 단계다. 먼저 형식이 뚜렷한 식별번호를 자리표시자로 바꾸고, 그다음
     * 메신저·메일 같은 앱에서는 긴 글을 내용으로 보고 통째로 가린다.
     *
     * 무턱대고 길이로만 가르면 안내문까지 가려진다(실측: "비밀번호가 일치하지
     * 않습니다"가 통째로 사라져, 로그인 실패 이유를 볼 수 없었다). 그래서 두 가지를
     * 예외로 둔다. 항목이 적은 화면(대화상자·로딩)과, 앱이 건네는 말로 보이는 문구다.
     *
     * 사람 이름은 남는다. 짧아서 걸러지지 않고, 누를 항목을 가리키려면 필요하다.
     * 이름도 개인정보라는 점에서 이 방식은 완전하지 않다.
     */
    fun redact(label: String, packageName: String, nodeCount: Int): String {
        val masked = mask(label)
        if (masked.length <= CONTENT_LENGTH) return masked

        val lowered = packageName.lowercase()
        if (CONTENT_PACKAGES.none { marker -> marker in lowered }) return masked
        if (nodeCount < DIALOG_NODES || isUiText(masked)) return masked
        return "<내용 ${masked.length}자>"
    }

    /**
     * 화면 자체를 밖으로 보내면 안 되는 앱이면 이유를, 아니면 null.
     *
     * 민감 화면 전체를 막던 것에서 앱 단위로 좁혔다. 부르는 쪽이 로그인 화면을
     * 보고 "여긴 아이디·비밀번호가 필요하다"까지 판단해야 흐름이 이어지기 때문이다.
     * 비밀번호 칸이 있다는 이유로 막으면 그 판단 자체를 못 한다.
     *
     * 대신 은행·결제·인증 앱은 그대로 막는다. 거기서는 화면에 뜬 것 자체가
     * 잔액·거래내역이고, 밖에서 볼 이유가 없다.
     */
    fun blockedApp(packageName: String): String? {
        val lowered = packageName.lowercase()
        val marker = SENSITIVE_PACKAGES.firstOrNull { it in lowered } ?: return null
        return "민감한 앱($packageName)이라 화면을 내보내지 않습니다 (${marker})"
    }
}
