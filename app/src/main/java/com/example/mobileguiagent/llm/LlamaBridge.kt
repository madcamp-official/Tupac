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
     * system/user 한 쌍으로 한 번 생성한다. 모델의 챗 템플릿이 적용되고 추론
     * 모드는 꺼진다. 실패는 "ERROR:"로 시작하는 문자열로 돌아온다.
     *
     * 프롬프트와 답은 로그에 남기지 않는다 — 여기 금고 값이 실린다.
     */
    external fun nativeChat(
        handle: Long,
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
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
