package com.example.mobileguiagent.device

/**
 * 자주 쓰는 앱의 흔한 한글 이름과 화면에 실제로 뜨는 이름을 잇는다.
 *
 * 왜 필요한가(실측):
 *   NAVER 앱의 화면 이름은 "NAVER"다. "네이버"로 찾으면 글자가 하나도 안
 *   겹쳐서 launch_app이 못 찾는다. list_apps로 "NAVER"를 직접 물어야만
 *   나온다 — 그런데 그걸 알려면 이미 앱 이름을 알아야 한다는 모순이 있다.
 *
 * 두 방향을 다 고려했지만 버렸다:
 *   - 한글 발음을 로마자로 바꾸기("네이버" -> "neibeo"): 자동이지만 틀린다.
 *     네이버는 naver지 neibeo가 아니다.
 *   - 패키지 이름으로도 대보기: com.nhn.android.search에는 "naver"가 아예
 *     없어서 이 경우엔 안 통한다.
 *
 * 그래서 자주 마주치는 것만 손으로 채운다. 다 못 채우는 게 당연하고, 못
 * 찾으면 지금처럼 비슷한 이름을 후보로 보여주는 것으로 충분하다.
 */
object AppAliases {

    /** 한글 이름 -> 화면에 실제로 뜨는 영문 이름. 소문자로 정규화해 비교한다. */
    private val ALIASES: Map<String, String> = mapOf(
        "네이버" to "NAVER",
        "카카오톡" to "KakaoTalk",
        "카톡" to "KakaoTalk",
        "유튜브" to "YouTube",
        "인스타그램" to "Instagram",
        "인스타" to "Instagram",
        "페이스북" to "Facebook",
        "지도" to "Maps",
        "크롬" to "Chrome",
        "쿠팡" to "Coupang",
        "배달의민족" to "Baemin",
        "배민" to "Baemin",
        "당근마켓" to "Karrot",
        "당근" to "Karrot",
        "토스" to "Toss",
        "카카오맵" to "KakaoMap",
        "카카오페이" to "KakaoPay",
        "설정" to "Settings",
        "메시지" to "Messages",
        "문자" to "Messages",
        "전화" to "Phone",
        "카메라" to "Camera",
        "갤러리" to "Gallery",
        "지메일" to "Gmail",
        "구글맵" to "Google Maps",
    )

    /** 검색어와 같은 뜻으로 볼 다른 이름들. 원래 검색어도 포함해 그대로도 시도된다. */
    fun expand(query: String): List<String> {
        val normalized = query.trim().lowercase()
        val alias = ALIASES.entries.firstOrNull { (korean, _) -> korean.lowercase() == normalized }
        return listOfNotNull(query, alias?.value)
    }
}
