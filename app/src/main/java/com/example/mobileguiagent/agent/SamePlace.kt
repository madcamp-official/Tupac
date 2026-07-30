package com.example.mobileguiagent.agent

/**
 * 두 자리가 같은 항목으로 볼 만큼 겹치는지.
 *
 * 관찰한 뒤 조작하기까지 시간이 흐르고, 그 사이 노드의 bounds는 조금씩 흔들린다
 * — 애니메이션이 끝나가는 중이거나, 스크롤 관성이 남았거나, 리스트가 항목을
 * 재활용하면서 몇 픽셀 옮겨 놓는다. 그런 흔들림을 "화면이 넘어갔다"로 읽으면
 * 멀쩡한 조작이 거부된다. 반대로 한 줄 스크롤처럼 진짜 옮겨간 것은 걸러야 한다.
 *
 * 겹침 비율로 가른다. 두 사각형의 교집합을 합집합으로 나눈 값이라, 크기와 위치를
 * 한 숫자로 함께 본다. 작은 아이콘에는 좁은 여유가, 큰 버튼에는 넓은 여유가
 * 저절로 생긴다 — 절대 픽셀로 정하면 둘 중 하나가 반드시 어긋난다.
 *
 * ENOUGH = 0.6이 어디서 왔는지 (기준선에 딱 걸리는 밀림):
 *   40x40 아이콘   → 10px 까지 통과
 *   200x100 버튼   → 50px 까지 통과
 *   한 줄(186px) 스크롤 → 겹침 0.00, 거부
 * 실측 화면의 항목 간격이 186px이고 항목 높이가 그보다 작아, 한 줄만 밀려도
 * 겹침이 0이 된다. 흔들림과 이동 사이가 넉넉히 벌어져 있다.
 *
 * android.graphics.Rect를 받지 않고 Int를 받는다. 유닛 테스트의 android.jar는
 * 껍데기라 Rect의 생성자도 메서드도 동작하지 않는다(app/build.gradle.kts의
 * unitTests.isReturnDefaultValues). 계산을 Rect에서 떼어놓으면 이 판단을
 * 기기 없이 검사할 수 있다.
 */
object SamePlace {

    /** 이만큼 겹치면 같은 자리로 본다. */
    const val ENOUGH = 0.6f

    /** 겹침 비율. 1.0이면 같은 자리, 0.0이면 안 닿는다. */
    fun overlap(
        aLeft: Int,
        aTop: Int,
        aRight: Int,
        aBottom: Int,
        bLeft: Int,
        bTop: Int,
        bRight: Int,
        bBottom: Int,
    ): Float {
        // 같은 자리는 넓이를 따질 것 없이 같은 자리다. 넓이가 0인 노드(안 보이는
        // 자리표시자)도 있어서, 이 갈래가 없으면 자기 자신과도 안 맞는다고 나온다.
        if (aLeft == bLeft && aTop == bTop && aRight == bRight && aBottom == bBottom) {
            return 1f
        }

        val left = maxOf(aLeft, bLeft)
        val top = maxOf(aTop, bTop)
        val right = minOf(aRight, bRight)
        val bottom = minOf(aBottom, bBottom)
        if (right <= left || bottom <= top) return 0f

        // Long으로 센다. 1080x2400 화면에서도 Int로 넘치지는 않지만, 넓이 곱은
        // 실수하기 쉬운 자리라 여유를 둔다.
        val shared = (right - left).toLong() * (bottom - top)
        val a = (aRight - aLeft).toLong() * (aBottom - aTop)
        val b = (bRight - bLeft).toLong() * (bBottom - bTop)
        val union = a + b - shared
        if (union <= 0L) return 0f
        return shared.toFloat() / union
    }

    /** 같은 자리로 볼 만큼 겹치는지. */
    fun enough(
        aLeft: Int,
        aTop: Int,
        aRight: Int,
        aBottom: Int,
        bLeft: Int,
        bTop: Int,
        bRight: Int,
        bBottom: Int,
    ): Boolean = overlap(aLeft, aTop, aRight, aBottom, bLeft, bTop, bRight, bBottom) >= ENOUGH
}
