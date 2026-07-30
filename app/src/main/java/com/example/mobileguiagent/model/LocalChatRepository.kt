package com.example.mobileguiagent.model

import android.content.Context
import android.util.Log
import com.example.mobileguiagent.BuildConfig
import com.example.mobileguiagent.agent.AgentRunCoordinator
import com.example.mobileguiagent.agent.AgentBootstrapException
import com.example.mobileguiagent.agent.AgentRunForegroundService
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.agent.AgentDataSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object LocalChatRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(LocalChatState())
    private val deviceTools = DeviceToolRegistry()
    private val agentCoordinator = AgentRunCoordinator(deviceTools = deviceTools)

    val state: StateFlow<LocalChatState> = mutableState.asStateFlow()

    private var generationJob: Job? = null
    private var nextMessageId = 1L

    fun refresh(context: Context) {
        mutableState.update { state ->
            val configured = BuildConfig.GEMINI_API_KEY.isNotBlank()
            state.copy(
                geminiConfigured = configured,
                archives = ChatArchiveStore.load(context.applicationContext),
                status = if (configured) {
                    "${state.cloudModel.displayName} · 클라우드"
                } else {
                    "${state.cloudModel.displayName} · API 키 필요"
                },
            )
        }
    }

    fun send(
        context: Context,
        input: String,
    ) {
        val text = input.trim()
        if (text.isEmpty() || generationJob?.isActive == true) return
        runCatching {
            AgentRunForegroundService.start(context.applicationContext, text)
        }.onFailure { error ->
            Log.e(TAG, "Unable to start foreground agent service", error)
            mutableState.update {
                it.copy(
                    generating = false,
                    status = "에이전트 실행 서비스 시작 실패",
                    error = error.message ?: error::class.java.simpleName,
                )
            }
        }
    }

    internal fun runFromForegroundService(
        context: Context,
        input: String,
        onFinished: () -> Unit,
    ): Boolean {
        val text = input.trim()
        if (text.isEmpty() || generationJob?.isActive == true) return false
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isBlank()) {
            mutableState.update {
                it.copy(
                    status = "${it.cloudModel.displayName} · API 키 필요",
                    error = "프로젝트 local.properties에 GEMINI_API_KEY=... 형식으로 추가하세요.",
                )
            }
            return false
        }

        val appContext = context.applicationContext
        val selectedModel = mutableState.value.cloudModel
        mutableState.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    id = nextMessageId++,
                    role = ChatRole.USER,
                    text = text,
                ),
                generating = true,
                status = "${selectedModel.displayName} · 화면 준비 중…",
                error = null,
            )
        }
        generationJob = scope.launch {
            try {
                val run = agentCoordinator.execute(
                    context = appContext,
                    apiKey = apiKey,
                    model = selectedModel,
                    goal = text,
                    onProgress = { progress ->
                        mutableState.update { state -> state.copy(status = progress) }
                    },
                    onTrace = { event ->
                        appendTrace(event)
                    },
                )
                Log.i(
                    TAG,
                    "Agent completed: steps=${run.outcome.steps}, " +
                        "status=${run.outcome.status}, workspace=${run.workspaceId}, " +
                        "run_log=${run.logPath}",
                )
                finishWithAssistantMessage(run.outcome.message, run.outcome.status)
            } catch (error: CancellationException) {
                mutableState.update {
                    it.copy(generating = false, status = "Gemini 작업 중단됨")
                }
            } catch (error: AgentBootstrapException) {
                Log.e(TAG, "Agent bootstrap failed code=${error.code}", error)
                mutableState.update {
                    it.copy(
                        generating = false,
                        status = if (error.setupRequired) {
                            "에이전트 준비 필요"
                        } else {
                            "에이전트 준비 실패"
                        },
                        error = error.message,
                    )
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Agent failed", error)
                mutableState.update {
                    it.copy(
                        generating = false,
                        status = "Gemini 작업 실패",
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
            } finally {
                generationJob = null
                onFinished()
            }
        }
        return true
    }

    fun cycleCloudModel() {
        if (generationJob?.isActive == true) return
        mutableState.update { state ->
            val model = state.cloudModel.next()
            state.copy(
                cloudModel = model,
                status = if (state.geminiConfigured) {
                    "${model.displayName} · 클라우드"
                } else {
                    "${model.displayName} · API 키 필요"
                },
            )
        }
    }

    fun stopGeneration() {
        if (generationJob?.isActive != true) return
        generationJob?.cancel()
        mutableState.update {
            it.copy(generating = false, status = "생성 중단됨")
        }
    }

    internal fun isGenerating(): Boolean = generationJob?.isActive == true

    private fun appendTrace(event: AgentTraceEvent) {
        val (role, text) = when (event) {
            is AgentTraceEvent.PlannerDecision -> ChatRole.TOOL_RESULT to
                "Step ${event.step} · ${event.model} 판단\n" +
                "${event.action} · ${AgentDataSanitizer.text(event.reasonCode)} · " +
                "${AgentDataSanitizer.text(event.target)}\n" +
                AgentDataSanitizer.text(event.progressSummary)

            is AgentTraceEvent.ToolCall -> ChatRole.TOOL_CALL to buildString {
                append("Step ${event.step} · ${event.call.name}")
                if (event.call.arguments.length() > 0) {
                    append("\n")
                    append(AgentDataSanitizer.toolArguments(event.call).toString(2))
                }
            }

            is AgentTraceEvent.ActionVerification -> ChatRole.TOOL_RESULT to
                "Step ${event.step} · ${event.tool} 검증\n" +
                if (event.verified) {
                    "확인됨 · ${event.evidence.joinToString()}"
                } else {
                    "확인되지 않음 · ${event.message}"
                }

            is AgentTraceEvent.RuntimeRoute -> ChatRole.TOOL_RESULT to
                "Step ${event.step} · ${event.runtime}\n" +
                AgentDataSanitizer.text(event.reason)

            is AgentTraceEvent.RuntimeFact -> ChatRole.TOOL_RESULT to
                "Step ${event.step} · 런타임 상태\n" +
                "${AgentDataSanitizer.text(event.key)}=" +
                AgentDataSanitizer.text(event.value)

            is AgentTraceEvent.LatencySample -> return

            is AgentTraceEvent.ToolResult -> ChatRole.TOOL_RESULT to buildString {
                append(if (event.automatic) "자동 화면 재관찰" else "${event.call.name} 결과")
                append("\n")
                append(AgentTraceFormatter.format(event.call, event.result))
            }
        }
        mutableState.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    id = nextMessageId++,
                    role = role,
                    text = text,
                ),
            )
        }
    }

    private fun finishWithAssistantMessage(text: String, status: String? = null) {
        mutableState.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    id = nextMessageId++,
                    role = ChatRole.ASSISTANT,
                    text = text,
                ),
                generating = false,
                status = status ?: "${it.cloudModel.displayName} · 클라우드",
            )
        }
    }

    fun clear() {
        if (generationJob?.isActive == true) return
        mutableState.update {
            it.copy(
                messages = emptyList(),
                error = null,
                status = if (it.geminiConfigured) {
                    "${it.cloudModel.displayName} · 클라우드"
                } else {
                    "${it.cloudModel.displayName} · API 키 필요"
                },
            )
        }
    }

    fun archiveCurrent(context: Context) {
        val messages = mutableState.value.messages
        if (messages.isEmpty() || mutableState.value.generating) return
        val now = System.currentTimeMillis()
        val title = messages.firstOrNull { it.role == ChatRole.USER }
            ?.text
            ?.take(36)
            ?.ifBlank { null }
            ?: "새 대화"
        val archives = ChatArchiveStore.save(
            context.applicationContext,
            ChatArchive(now, title, now, messages),
        )
        mutableState.update {
            it.copy(
                messages = emptyList(),
                archives = archives,
                status = "대화를 보관했습니다.",
                error = null,
            )
        }
    }

    fun openArchive(archiveId: Long) {
        if (mutableState.value.generating) return
        val archive = mutableState.value.archives.firstOrNull { it.id == archiveId } ?: return
        nextMessageId = maxOf(
            nextMessageId,
            (archive.messages.maxOfOrNull(ChatMessage::id) ?: 0L) + 1L,
        )
        mutableState.update {
            it.copy(messages = archive.messages, status = "보관된 대화 · ${archive.title}")
        }
    }

    fun deleteArchive(context: Context, archiveId: Long) {
        val archives = ChatArchiveStore.delete(context.applicationContext, archiveId)
        mutableState.update { it.copy(archives = archives) }
    }

    private const val TAG = "LocalChatRepository"
}
