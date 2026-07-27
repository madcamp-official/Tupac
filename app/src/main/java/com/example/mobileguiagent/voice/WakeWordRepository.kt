package com.example.mobileguiagent.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WakeWordState(
    val enabled: Boolean = false,
    val listening: Boolean = false,
    val preparing: Boolean = false,
    val status: String = "웨이크워드가 꺼져 있습니다.",
    val error: String? = null,
)

/**
 * UI와 음성 서비스가 공유하는 웨이크워드 상태입니다.
 *
 * 실제 마이크와 Porcupine 수명은 WakeWordService가 소유하고, 이 저장소는
 * 화면 표시 및 VoiceCommandService의 자동 복귀 여부만 전달합니다.
 */
object WakeWordRepository {
    private val mutableState = MutableStateFlow(WakeWordState())
    val state = mutableState.asStateFlow()

    fun preparing() {
        mutableState.value = WakeWordState(
            enabled = true,
            preparing = true,
            status = "\"Hey Tupac\" 모델을 준비하는 중…",
        )
    }

    fun listening() {
        mutableState.value = WakeWordState(
            enabled = true,
            listening = true,
            status = "\"Hey Tupac\"을 기다리는 중…",
        )
    }

    fun commandRunning() {
        mutableState.value = WakeWordState(
            enabled = true,
            status = "웨이크워드 감지 · 명령을 듣는 중…",
        )
    }

    fun failed(message: String) {
        mutableState.value = WakeWordState(
            status = "웨이크워드 시작 실패",
            error = message,
        )
    }

    fun stopped() {
        mutableState.value = WakeWordState()
    }
}
