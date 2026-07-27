package com.example.mobileguiagent.llm

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mobileguiagent.agent.LocalStep
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * llama.cpp를 실기기에서 실제로 돌려보는 확인용 테스트다. 앱 기능이 아니라
 * "되는가"를 재는 자리이므로, 결과는 assert로 막기보다 로그로 남겨서 읽는다.
 *
 * 모델은 APK에 넣지 않는다(775MB). 미리 기기에 올려두고 돌린다:
 *
 *   adb push EXAONE-4.0-1.2B-Q4_K_M.gguf \
 *     /sdcard/Android/data/com.example.mobileguiagent/files/model.gguf
 *
 * `gradlew connectedAndroidTest`로 돌리지 말 것. 그 태스크는 끝나고 앱을
 * 언인스톨하는데, 모델을 둔 곳이 앱 외부 저장소라 같이 지워진다. APK 두 개를
 * `adb install -r`로 넣고 계측을 직접 부른다:
 *
 *   adb shell am instrument -w \
 *     -e class com.example.mobileguiagent.llm.LlamaBridgeTest \
 *     com.example.mobileguiagent.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class LlamaBridgeTest {

    private val tag = "LlamaSpike"

    private fun modelFile(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.getExternalFilesDir(null), "model.gguf")
    }

    /** 1단계: 모델 없이 .so가 로드되고 Kotlin에서 네이티브가 불리는가. */
    @Test
    fun nativeLibraryLoadsAndAnswers() {
        val info = LlamaBridge.nativeBuildInfo()
        Log.i(tag, "buildInfo = $info")
        assertTrue("네이티브 응답이 비어 있다", info.startsWith("llama.cpp ok"))
    }

    /** 2단계: gguf를 올리고 짧은 생성이 나오는가. 시간과 RSS를 같이 잰다. */
    @Test
    fun loadsModelAndGenerates() {
        val model = modelFile()
        Log.i(tag, "모델 경로 = ${model.absolutePath}, 존재=${model.exists()}, 크기=${model.length()}")
        assertTrue("모델 파일이 기기에 없다: ${model.absolutePath}", model.exists())

        val rssBefore = LlamaBridge.processRssKb()
        Log.i(tag, "RSS(로딩 전) = ${rssBefore / 1024} MiB")

        var handle = 0L
        val loadMs = measureTimeMillis {
            handle = LlamaBridge.nativeLoadModel(model.absolutePath, 512, 4)
        }
        Log.i(tag, "로딩 시간 = ${loadMs} ms, handle = $handle")
        assertNotEquals("모델 로딩 실패 (logcat의 llama.cpp 태그 참고)", 0L, handle)

        try {
            val rssAfterLoad = LlamaBridge.processRssKb()
            Log.i(tag, "RSS(로딩 후) = ${rssAfterLoad / 1024} MiB (+${(rssAfterLoad - rssBefore) / 1024} MiB)")
            Log.i(tag, "modelInfo = ${LlamaBridge.nativeModelInfo(handle)}")

            // "fill" 형식이다. 화면 표기가 "node_12 [type] 라벨"이라 답 형식이
            // "type node_12 값"이면 앞부분이 겹쳐 모델이 값을 빠뜨린다.
            val question = "fill node_12 minsu 를 그대로 답하세요"

            // 한 번만 재면 페이지 캐시 상태에 따라 크게 흔들린다. 세 번 돌린다.
            var out = ""
            repeat(3) { round ->
                val genMs = measureTimeMillis {
                    out = LlamaBridge.nativeChat(handle, LocalStep.SYSTEM_PROMPT, question, 64, 0.0f)
                }
                Log.i(tag, "[$round] 생성 시간 = ${genMs} ms")
                Log.i(tag, "[$round] 출력 = >>>$out<<<")

                assertTrue("생성이 에러를 냈다: $out", !out.startsWith("ERROR:"))
                assertTrue("생성 결과가 비어 있다", out.isNotBlank())
            }

            Log.i(tag, "RSS(생성 후) = ${LlamaBridge.processRssKb() / 1024} MiB")
            Log.i(tag, "RSS 최대 = ${LlamaBridge.peakRssKb() / 1024} MiB")
        } finally {
            LlamaBridge.nativeFree(handle)
        }
    }
}
