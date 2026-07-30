package com.example.mobileguiagent.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * open_uri가 무엇을 열고 무엇을 거절하는지.
 *
 * 이 도구는 여는 주소를 부르는 쪽(클라우드 모델)이 정한다. 그래서 규칙이 느슨해지면
 * 그대로 공격 통로가 된다. 단언마다 무엇을 막는 것인지 적어뒀다.
 */
class SafeUriTest {

    @Test
    fun `평범한 https 주소는 연다`() {
        assertTrue(SafeUri.allowed("https", "www.coupang.com", null))
    }

    @Test
    fun `대문자 스킴도 https다`() {
        // Uri.getScheme()은 보통 소문자로 주지만, 규칙이 대소문자에 걸려
        // 정상 주소를 막는 일이 없어야 한다.
        assertTrue(SafeUri.allowed("HTTPS", "www.coupang.com", null))
    }

    @Test
    fun `http는 거절한다`() {
        // 평문이라 중간에서 바꿔치기가 된다. 우리가 여는 주소는 사람이 로그인까지
        // 하게 될 화면이다.
        assertFalse(SafeUri.allowed("http", "www.coupang.com", null))
    }

    @Test
    fun `userInfo가 붙으면 거절한다`() {
        // https://kakao.com@evil.example — 사람 눈에는 카카오로 보이는데 실제
        // 호스트는 evil.example이다. 주소를 읽고 승인하는 게 사람이므로 이 형태는
        // 승인 절차 자체를 무력화한다.
        assertFalse(SafeUri.allowed("https", "evil.example", "kakao.com"))
    }

    @Test
    fun `빈 userInfo도 거절한다`() {
        // "https://@evil.example" 같은 모양. 빈 문자열은 null이 아니므로,
        // null 검사만 하면 통과해버린다.
        assertFalse(SafeUri.allowed("https", "evil.example", ""))
    }

    @Test
    fun `호스트가 없으면 거절한다`() {
        // "https:///path" 처럼 어디로 가는지 말할 수 없는 주소.
        assertFalse(SafeUri.allowed("https", null, null))
        assertFalse(SafeUri.allowed("https", "", null))
    }

    @Test
    fun `앱 내부와 기기 파일로 가는 스킴은 거절한다`() {
        // 여는 주소를 밖에서 정하는 도구가 이쪽까지 열면 안 된다.
        assertFalse(SafeUri.allowed("intent", "com.example", null))
        assertFalse(SafeUri.allowed("file", "", null))
        assertFalse(SafeUri.allowed("content", "com.android.contacts", null))
        assertFalse(SafeUri.allowed("javascript", null, null))
    }

    @Test
    fun `스킴이 없으면 거절한다`() {
        // "www.coupang.com"처럼 스킴을 빠뜨린 입력. 상대 주소로 읽히면 무엇이
        // 열릴지 알 수 없다.
        assertFalse(SafeUri.allowed(null, "www.coupang.com", null))
    }
}
