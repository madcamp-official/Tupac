package com.example.mobileguiagent.llm

import java.io.File

/**
 * llama.cpp를 JNI로 부를 수 있는지 확인하기 위한 최소 통로다.
 *
 * 아직 앱 기능에 연결하지 않는다. 여기서 확인하려는 것은 세 가지뿐이다.
 * arm64-v8a로 빌드가 되는가, Kotlin에서 네이티브 함수가 불리는가,
 * 실기기에서 gguf를 올려 짧은 답이 나오는가.
 */
object LlamaBridge {

    init {
        System.loadLibrary("llamajni")
    }

    /** 모델 없이 네이티브가 살아 있는지만 본다. */
    external fun nativeBuildInfo(): String

    /** 실패하면 0. 이유는 logcat의 `llama.cpp` 태그에 남는다. */
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int): Long

    external fun nativeModelInfo(handle: Long): String

    /**
     * [applyTemplate]가 false면 [prompt]를 그대로 넣는다. llama.cpp가 들고 있는
     * EXAONE 4.0 템플릿 사본이 gguf의 실제 템플릿과 달라서, 지금은 호출 측이
     * 포맷한 프롬프트를 넘기는 쪽이 정확하다.
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        applyTemplate: Boolean,
    ): String

    external fun nativeFree(handle: Long)

    /**
     * 이 프로세스의 실제 사용 메모리(KB). 모델은 mmap으로 올라오므로 자바 힙에는
     * 잡히지 않는다. S10e에서 775MB가 들어가는지 보려면 RSS를 직접 봐야 한다.
     */
    fun processRssKb(): Long = statusKb("VmRSS:")

    /** 이 프로세스가 지금까지 찍은 최대 RSS(KB). 기기에 들어가는지 보려면 이 값을 본다. */
    fun peakRssKb(): Long = statusKb("VmHWM:")

    private fun statusKb(field: String): Long =
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith(field) }
                ?.filter { it.isDigit() }
                ?.toLongOrNull()
                ?: -1L
        }
}
