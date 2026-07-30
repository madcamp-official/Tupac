package com.example.mobileguiagent.device

/**
 * open_uri가 열어도 되는 주소인지 정하는 규칙.
 *
 * 파싱은 하지 않는다. 안드로이드가 한 번 파싱한 결과(스킴·호스트·userInfo)만
 * 받아서 판단한다. 파서를 둘 두면 — 검사에 쓰는 파서와 실제로 여는 파서가
 * 다르면 — 한쪽은 안전한 호스트로, 다른 쪽은 공격자 호스트로 읽는 문자열이
 * 생긴다. 검사를 통과시키고 다른 곳을 여는 것이야말로 막으려는 일이다.
 *
 * 파싱을 뺀 덕에 이 규칙은 폰 없이 검사할 수 있다. SamePlace와 같은 이유다.
 */
object SafeUri {
    /**
     * @param scheme   Uri.getScheme()
     * @param host     Uri.getHost()
     * @param userInfo Uri.getUserInfo()
     */
    fun allowed(scheme: String?, host: String?, userInfo: String?): Boolean {
        // https만. intent:·file:·content:는 앱 내부나 기기 파일로 가는 통로다.
        // 여는 주소를 밖에서 정하는 도구가 그쪽까지 열면 안 된다.
        if (!scheme.equals("https", ignoreCase = true)) return false

        // 호스트가 없으면 어디로 가는지 말할 수 없다.
        if (host.isNullOrBlank()) return false

        // https://kakao.com@evil.example 은 실제 호스트가 evil.example인데
        // 사람 눈에는 카카오로 보인다. 주소를 읽고 승인하는 게 사람이므로
        // 이 형태는 승인 절차 자체를 무력화한다.
        if (userInfo != null) return false

        return true
    }
}
