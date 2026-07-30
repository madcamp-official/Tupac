package com.example.mobileguiagent.model

import com.example.mobileguiagent.cloud.GeminiModel

/** Roles rendered in the chat timeline, including transparent agent traces. */
enum class ChatRole {
    USER,
    ASSISTANT,
    TOOL_CALL,
    TOOL_RESULT,
}

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
)

/**
 * Immutable UI state for the agent console.
 *
 * The application has a single cloud planner. Android actions, OCR, privacy
 * filtering, and secret resolution still execute on the phone.
 */
data class LocalChatState(
    val messages: List<ChatMessage> = emptyList(),
    val generating: Boolean = false,
    val status: String = "실행 환경을 확인하는 중…",
    val error: String? = null,
    val archives: List<ChatArchive> = emptyList(),
    val cloudModel: GeminiModel = GeminiModel.FLASH_LITE_3_1,
    val geminiConfigured: Boolean = false,
) {
    val readyForInput: Boolean
        get() = geminiConfigured
}
