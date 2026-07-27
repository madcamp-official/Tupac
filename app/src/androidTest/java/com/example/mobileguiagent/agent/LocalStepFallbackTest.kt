package com.example.mobileguiagent.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 모델 파일이 없을 때 조용히 비켜서는지 본다.
 *
 * 이것이 지켜지지 않으면 모델을 안 내려받은 기기에서는 로그인이 아예 안 된다.
 * 기기 안 모델은 있으면 한 번 더 확인해주는 것이지, 없으면 못 하게 하는 것이
 * 아니다 — 넣을 값은 어차피 금고에서 꺼낸 것이지 모델이 지어낸 것이 아니다.
 *
 * 모델을 잠시 치우고 돌린다:
 *
 *   adb shell mv .../files/model.gguf .../files/model.gguf.away
 *   adb shell am instrument -w \
 *     -e class com.example.mobileguiagent.agent.LocalStepFallbackTest \
 *     com.example.mobileguiagent.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class LocalStepFallbackTest {

    @Test
    fun opensToNullWhenModelIsMissing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LocalStep.modelFile(context)
        assumeFalse("모델이 아직 기기에 있어 폴백을 잴 수 없습니다: ${model.absolutePath}", model.isFile)

        // 예외가 아니라 null이어야 한다. 부르는 쪽(SecretFiller)은 null을 보고
        // 확인 단계를 건너뛴다.
        assertNull("모델이 없는데 세션이 열렸습니다", LocalStep.open(context))
    }
}
