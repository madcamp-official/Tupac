package com.example.mobileguiagent.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * 방금 화면에 채워 넣은 값을 기억했다가, 화면을 내보낼 때 도로 가린다.
 *
 * 봉인이 반쪽이었다. device_fill_secrets는 값을 돌려주지 않지만, 넣고 나면 그
 * 값이 화면에 남는다. 바깥 모델이 다음에 device_observe를 부르면 그대로 실려
 * 나간다. 실측: 카카오톡 로그인 화면에서 아이디가 이렇게 보였다.
 *
 *     {"id":"node_12", "hint":"이메일 또는 전화번호", "text":"testuser"}
 *
 * ScreenPrivacy는 이걸 못 잡는다. 거기는 형식이 뚜렷한 것(주민번호, 카드번호)을
 * 찾는데, 아이디는 아무 형식도 아니고 짧다. 반면 우리는 무엇을 어디에 넣었는지
 * 알고 있다. 아는 것을 가리는 편이 훨씬 정확하다.
 *
 * 비밀번호는 애초에 접근성 트리에 안 나온다(점으로 표시된다). 문제가 되는 건
 * 아이디·이름·주소처럼 평범한 글자로 남는 값들이다.
 */
object FilledSecrets {

    /**
     * 값 -> 필드 이름. 값을 키로 두는 이유는 화면에서 값을 보고 되찾아야 해서다.
     * 같은 값이 두 필드에 들어가는 일은 드물고, 그때는 둘 중 하나로 표시돼도
     * 가려진다는 목적은 이룬다.
     */
    private val byValue = ConcurrentHashMap<String, String>()

    /**
     * 부분 일치로 가릴 최소 길이. 이보다 짧은 값은 칸의 글자가 통째로 같을 때만
     * 가린다. 짧은 값을 아무 데나 가리면 화면이 망가진다 — 이름이 "김"이라면
     * 화면의 모든 "김"이 사라져서 무엇을 누를지 알 수 없게 된다.
     */
    private const val PARTIAL_MIN = 4

    /** 한 칸을 채웠다고 알린다. SecretFiller가 넣는 즉시 부른다. */
    fun remember(field: String, value: String) {
        if (value.isNotBlank()) byValue[value] = field
    }

    /**
     * 다음 채우기를 시작할 때 이전 기억을 버린다.
     *
     * 계속 쌓아두면 평문 값이 메모리에 오래 남는다. 가려야 할 창은 "방금 채운
     * 화면을 바깥이 읽을 때"라, 그 사이만 들고 있으면 충분하다.
     */
    fun clear() = byValue.clear()

    /**
     * 내보낼 글자에서 우리가 넣은 값을 자리표시자로 바꾼다.
     *
     * 두 갈래로 본다. 칸의 글자가 값과 통째로 같으면 길이와 무관하게 가리고
     * (그 칸이 곧 우리가 채운 칸이다), 값이 넉넉히 길면 문장 속에 섞여 있어도
     * 가린다("받는사람: 홍길동" 같은 확인 문구).
     */
    fun mask(text: String): String {
        if (byValue.isEmpty() || text.isBlank()) return text
        byValue[text.trim()]?.let { field -> return "<$field 값>" }

        var masked = text
        for ((value, field) in byValue) {
            if (value.length >= PARTIAL_MIN && value in masked) {
                masked = masked.replace(value, "<$field 값>")
            }
        }
        return masked
    }
}
