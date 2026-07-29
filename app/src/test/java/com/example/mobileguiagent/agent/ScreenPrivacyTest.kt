package com.example.mobileguiagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScreenPrivacy가 파이썬(eval/privacy.py)과 같은 판단을 하는지 본다.
 *
 * 케이스는 eval/cases.py에서 옮겼다. 여기 있는 것은 대부분 한 번씩 새어나갔거나
 * 반대로 너무 많이 가려서 막혔던 기록이다. 단언마다 그 사연을 붙였다.
 *
 * 이 검사가 특히 중요해진 이유는 Claude가 MCP로 직접 붙기 때문이다. 예전에는
 * 맥북의 파이썬이 한 번 더 걸렀지만, 이제 여기서 새면 그대로 대화창으로 간다.
 */
class ScreenPrivacyTest {

    private val KAKAO = "com.kakao.talk"
    private val MANY = 12      // 대화 목록처럼 항목이 많은 화면
    private val FEW = 2        // 대화상자처럼 항목이 적은 화면

    @Test
    fun `식별번호는 자리표시자로 바뀐다`() {
        val cases = listOf(
            "901010-1234567" to "<주민번호>",
            "1234-5678-9012-3456" to "<카드번호>",
            "010-1234-5678" to "<전화번호>",
            "123-456-7890" to "<계좌번호>",
            "minsu@example.com" to "<이메일>",
        )
        for ((raw, want) in cases) {
            assertEquals("$raw 가 $want 로 바뀌어야 한다", want, ScreenPrivacy.mask(raw))
        }
    }

    @Test
    fun `패턴 순서가 결과를 바꾼다`() {
        assertEquals(
            "전화번호가 계좌번호보다 먼저 와야 한다. 뒤에 두면 계좌로 잡힌다",
            "<전화번호>", ScreenPrivacy.mask("010-1234-5678"),
        )
        assertEquals(
            "계좌번호를 \\d{2,7}로 두면 날짜가 전부 계좌번호로 잡혔다",
            "<생년월일>", ScreenPrivacy.mask("2026-07-26"),
        )
    }

    @Test
    fun `메신저의 긴 글은 통째로 가린다`() {
        val chat = "내일 회의 자료 준비되면 미리 좀 보내줄 수 있을까?"
        val out = ScreenPrivacy.redact(chat, KAKAO, MANY)
        assertTrue("대화 내용은 길을 찾는 데 필요 없다: $out", out.startsWith("<내용"))
    }

    @Test
    fun `마스킹한 뒤에도 긴 글이면 한 겹 더 가린다`() {
        val chat = "주민번호 901010-1234567 이거 맞지?"
        val out = ScreenPrivacy.redact(chat, KAKAO, MANY)
        assertTrue("두 겹이어야 한다. 자리표시자만 남기면 대화 맥락이 나간다: $out",
                   out.startsWith("<내용"))
    }

    @Test
    fun `낱말만 보면 대화를 안내문으로 오인한다`() {
        val chat = "비밀번호 알려줄게 나중에 꼭 지워라"
        val out = ScreenPrivacy.redact(chat, KAKAO, MANY)
        assertTrue("'비밀번호'가 있다고 UI로 보면 이 대화가 그대로 나간다: $out",
                   out.startsWith("<내용"))
    }

    @Test
    fun `안내문은 남긴다 - 항목이 적은 대화상자`() {
        val notice = "비밀번호가 일치하지 않습니다"
        assertEquals(
            "안내문을 가리면 왜 실패했는지 몰라 막힌다",
            notice, ScreenPrivacy.redact(notice, KAKAO, FEW),
        )
    }

    @Test
    fun `안내문은 남긴다 - 항목이 많아도 말투로 가른다`() {
        val notice = "네트워크에 연결할 수 없습니다"
        assertEquals(
            "항목이 많으면 대화상자 예외가 안 걸린다. 격식체로 갈라야 남는다",
            notice, ScreenPrivacy.redact(notice, KAKAO, MANY),
        )
        val chat = "아까 말한 거 그거 어떻게 됐어 진짜 궁금한데"
        assertTrue(
            "같은 화면의 대화는 가려져야 한다. 위 케이스와 짝이다",
            ScreenPrivacy.redact(chat, KAKAO, MANY).startsWith("<내용"),
        )
    }

    @Test
    fun `사람 이름은 남는다`() {
        assertEquals(
            "짧아서 걸러지지 않고, 누를 항목을 가리키려면 필요하다",
            "김철수", ScreenPrivacy.redact("김철수", KAKAO, MANY),
        )
    }

    @Test
    fun `설정 화면의 긴 글은 가리지 않는다`() {
        val notice = "도로명 주소를 권장합니다. 지번 주소로도 배송이 가능합니다"
        assertEquals(
            "메신저가 아닌 앱의 긴 글은 안내 문구다. 가리면 화면을 못 읽는다",
            notice, ScreenPrivacy.redact(notice, "com.android.settings", MANY),
        )
    }

    @Test
    fun `은행 앱은 화면 자체를 막는다`() {
        assertNotNull(
            "은행 앱은 화면에 뜬 것 자체가 잔액·거래내역이다",
            ScreenPrivacy.blockedApp("com.kbstar.kbbank"),
        )
        assertNull(
            "로그인 화면은 막지 않는다. 무슨 값이 필요한지를 봐야 흐름이 이어진다",
            ScreenPrivacy.blockedApp("com.kakao.talk"),
        )
        assertNull("평범한 설정 화면", ScreenPrivacy.blockedApp("com.android.settings"))
    }

    @Test
    fun `가상자산 지갑도 막는다`() {
        // 실측: 폰의 456개 패키지를 훑어보니 삼성 블록체인 월렛이
        // com.samsung.android.coldwalletservice라, 이름에 "bank"도 "pay"도 없어
        // 그대로 통과하고 있었다.
        assertNotNull(
            "지갑 화면에 뜬 것은 잔액이다",
            ScreenPrivacy.blockedApp("com.samsung.android.coldwalletservice"),
        )
        assertNotNull("거래소도 같다", ScreenPrivacy.blockedApp("com.dunamu.exchange.upbit"))
    }

    @Test
    fun `대화앱은 마디로 가른다`() {
        // 조각으로 맞추면 엉뚱한 앱이 걸린다. 실측으로 "gm"이 잡던 것들이다.
        // 잘못 걸리면 긴 글이 통째로 가려져, 정작 읽어야 할 시스템 대화상자를 못 읽는다.
        val notice = "이 기기에서 계정을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
        assertEquals(
            "com.google.android.gms는 지메일이 아니다",
            notice,
            ScreenPrivacy.redact(notice, "com.google.android.gms", MANY),
        )
        assertEquals(
            "com.google.mainline.telemetry는 라인이 아니다",
            notice,
            ScreenPrivacy.redact(notice, "com.google.mainline.telemetry", MANY),
        )
        assertEquals(
            "진짜 지메일은 그대로 걸려야 한다",
            true,
            ScreenPrivacy.redact("어제 보낸 메일 확인해봤어? 답장이 없어서", "com.google.android.gm", MANY)
                .startsWith("<내용"),
        )
    }
}
