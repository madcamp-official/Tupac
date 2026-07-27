package com.example.mobileguiagent.agent

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 방금 채운 값이 화면을 내보낼 때 도로 가려지는지 본다.
 *
 * 실측으로 새어나갔던 것이 여기 있다. device_fill_secrets는 값을 안 돌려주지만,
 * 넣고 나면 화면에 남아서 다음 device_observe에 실려 나갔다. 카카오톡 로그인
 * 화면에서 아이디가 그대로 보였다.
 */
class FilledSecretsTest {

    @Before
    fun clean() = FilledSecrets.clear()

    @Test
    fun `채운 칸의 글자를 통째로 가린다`() {
        FilledSecrets.remember("username", "minsu")
        assertEquals(
            "아이디는 형식이 없어서 ScreenPrivacy가 못 잡는다. 우리가 넣은 것은 우리가 안다",
            "<username 값>", FilledSecrets.mask("minsu"),
        )
    }

    @Test
    fun `짧은 값은 문장 속에서는 건드리지 않는다`() {
        FilledSecrets.remember("name", "김")
        assertEquals(
            "한 글자를 아무 데나 가리면 화면이 망가져 무엇을 누를지 알 수 없다",
            "김밥천국 주문내역", FilledSecrets.mask("김밥천국 주문내역"),
        )
    }

    @Test
    fun `긴 값은 문장 속에 섞여 있어도 가린다`() {
        FilledSecrets.remember("address", "서울시 중구 세종대로 110")
        assertEquals(
            "확인 문구에 값이 섞여 나오는 화면이 있다",
            "배송지: <address 값> 로 보냅니다",
            FilledSecrets.mask("배송지: 서울시 중구 세종대로 110 로 보냅니다"),
        )
    }

    @Test
    fun `다음 채우기를 시작하면 잊는다`() {
        FilledSecrets.remember("username", "minsu")
        FilledSecrets.clear()
        assertEquals(
            "평문 값을 필요한 창 밖까지 들고 있지 않는다",
            "minsu", FilledSecrets.mask("minsu"),
        )
    }

    @Test
    fun `기억한 것이 없으면 그대로 둔다`() {
        assertEquals("가릴 것이 없으면 손대지 않는다", "로그인", FilledSecrets.mask("로그인"))
    }
}
