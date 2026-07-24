package com.example.mobileguiagent.model

import android.content.Context
import com.example.minicpm_v_demo.LlamaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChatRole {
    USER,
    ASSISTANT,
}

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
)

data class LocalChatState(
    val messages: List<ChatMessage> = emptyList(),
    val modelFilePresent: Boolean = false,
    val modelReady: Boolean = false,
    val generating: Boolean = false,
    val status: String = "로컬 모델을 확인하는 중…",
    val error: String? = null,
)

object LocalChatRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(LocalChatState())
    val state: StateFlow<LocalChatState> = mutableState.asStateFlow()

    private var generationJob: Job? = null
    private var nextMessageId = 1L

    fun refresh(context: Context) {
        val modelFile = UiTreeModelRepository.modelFile(context.applicationContext)
        mutableState.update { state ->
            state.copy(
                modelFilePresent = modelFile.isFile,
                status = when {
                    state.modelReady -> "MiniCPM-V 4.6 · 온디바이스"
                    modelFile.isFile -> "MiniCPM-V 4.6 준비됨"
                    else -> "모델 파일이 필요합니다: ${UiTreeModelRepository.MODEL_FILE_NAME}"
                },
            )
        }
    }

    fun send(context: Context, input: String) {
        val text = input.trim()
        if (text.isEmpty() || generationJob?.isActive == true) return

        val appContext = context.applicationContext
        val userMessage = ChatMessage(
            id = nextMessageId++,
            role = ChatRole.USER,
            text = text,
        )
        mutableState.update {
            it.copy(
                messages = it.messages + userMessage,
                generating = true,
                status = if (it.modelReady) {
                    "답변 생성 중…"
                } else {
                    "모델 로딩 중…"
                },
                error = null,
            )
        }

        generationJob = scope.launch {
            try {
                val modelFile = UiTreeModelRepository.modelFile(appContext)
                require(modelFile.isFile) {
                    "모델 파일이 없습니다: ${modelFile.absolutePath}"
                }

                val engine = LlamaEngine.getInstance(appContext)
                engine.loadModel(modelFile)
                mutableState.update {
                    it.copy(
                        modelReady = true,
                        status = "답변 생성 중…",
                    )
                }

                val conversation = buildConversationPrompt(mutableState.value.messages)
                val response = engine.generate(
                    systemPrompt = SYSTEM_PROMPT,
                    userPrompt = conversation,
                    predictLength = RESPONSE_TOKEN_LIMIT,
                ).ifBlank {
                    "응답을 생성하지 못했습니다."
                }

                mutableState.update {
                    it.copy(
                        messages = it.messages + ChatMessage(
                            id = nextMessageId++,
                            role = ChatRole.ASSISTANT,
                            text = response,
                        ),
                        generating = false,
                        status = "MiniCPM-V 4.6 · 온디바이스",
                    )
                }
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        generating = false,
                        status = "응답 생성 실패",
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
            }
        }
    }

    fun clear() {
        if (generationJob?.isActive == true) return
        mutableState.update {
            it.copy(
                messages = emptyList(),
                error = null,
                status = if (it.modelReady) {
                    "MiniCPM-V 4.6 · 온디바이스"
                } else {
                    it.status
                },
            )
        }
    }

    private fun buildConversationPrompt(messages: List<ChatMessage>): String = buildString {
        messages.takeLast(MAX_CONTEXT_MESSAGES).forEach { message ->
            val role = if (message.role == ChatRole.USER) "User" else "Assistant"
            appendLine("$role: ${message.text}")
        }
        append("Assistant:")
    }

    private const val MAX_CONTEXT_MESSAGES = 8
    private const val RESPONSE_TOKEN_LIMIT = 384
    private const val SYSTEM_PROMPT =
        """You are a helpful assistant running locally on an Android phone.
Reply naturally and concisely in the same language as the user.
The conversation history follows. Continue only as the assistant."""
}
