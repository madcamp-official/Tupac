package com.example.mobileguiagent.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 실측(NAVER): "네이버"로 찾으면 화면 이름이 "NAVER"라 글자가 하나도 안
 * 겹쳐서 launch_app이 못 찾았다. 이 표가 그 간극을 메운다.
 */
class AppAliasesTest {

    @Test
    fun `등록된 한글 이름은 영문 이름도 함께 내놓는다`() {
        assertEquals(listOf("네이버", "NAVER"), AppAliases.expand("네이버"))
    }

    @Test
    fun `대소문자와 앞뒤 공백은 가리지 않는다`() {
        assertEquals(listOf(" 네이버 ", "NAVER"), AppAliases.expand(" 네이버 "))
    }

    @Test
    fun `등록되지 않은 이름은 그대로만 돌아온다`() {
        assertEquals(listOf("계산기"), AppAliases.expand("계산기"))
    }

    @Test
    fun `원래 이미 영문이면 그대로만 돌아온다`() {
        // "NAVER"는 별칭 표의 값 쪽이지 키가 아니다. 다시 찾을 이유가 없다.
        assertEquals(listOf("NAVER"), AppAliases.expand("NAVER"))
    }
}
