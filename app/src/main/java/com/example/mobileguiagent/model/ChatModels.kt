package com.example.mobileguiagent.model

import com.example.mobileguiagent.agent.AgentRuntimeMode
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
 * Gemini is the primary runtime. Local readiness is tracked independently so
 * the on-device agent can still be selected or invoked as a fallback tool.
 */
data class LocalChatState(
    val messages: List<ChatMessage> = emptyList(),
    val modelFilePresent: Boolean = false,
    val modelReady: Boolean = false,
    val activeModelProfile: OnDeviceModelProfile =
        OnDeviceModelProfileResolver.defaultInstallProfile,
    val generating: Boolean = false,
    val status: String = "실행 환경을 확인하는 중…",
    val error: String? = null,
    val archives: List<ChatArchive> = emptyList(),
    val runtimeMode: AgentRuntimeMode = AgentRuntimeMode.GEMINI,
    val cloudModel: GeminiModel = GeminiModel.LATEST_LITE,
    val geminiConfigured: Boolean = false,
) {
    val readyForInput: Boolean
        get() = when (runtimeMode) {
            AgentRuntimeMode.LOCAL -> modelFilePresent
            AgentRuntimeMode.GEMINI -> geminiConfigured
        }
}
