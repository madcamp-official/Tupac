package com.example.mobileguiagent.model

import android.content.Context
import android.util.Log
import com.example.minicpm_v_demo.LlamaEngine
import com.example.mobileguiagent.BuildConfig
import com.example.mobileguiagent.agent.AgentRuntimeMode
import com.example.mobileguiagent.cloud.GeminiAgentController
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.ObserveUiDeviceTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object LocalChatRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(LocalChatState())

    // 모든 런타임은 같은 Android 동작 구현을 사용합니다. 각 런타임의
    // 어댑터는 호출 형식만 변환하며 Device Tool 자체를 복제하지 않습니다.
    private val deviceTools = DeviceToolRegistry()
    private val toolAdapter = LocalDeviceToolAdapter(deviceTools)
    private val agentController = LocalAgentController(toolAdapter)
    private val localPrivacyPlanner = LocalPrivacyPlanner(toolAdapter, deviceTools)
    private val geminiAgentController = GeminiAgentController(
        deviceTools = deviceTools,
        localPrivacyPlanner = localPrivacyPlanner,
    )

    val state: StateFlow<LocalChatState> = mutableState.asStateFlow()

    private var generationJob: Job? = null
    private var activeEngine: LlamaEngine? = null
    private var nextMessageId = 1L

    fun refresh(context: Context) {
        val resolvedModel =
            OnDeviceModelProfileResolver.resolve(context.applicationContext)
        mutableState.update { state ->
            val sameReadyProfile =
                state.modelReady &&
                    state.activeModelProfile.id == resolvedModel.profile.id
            state.copy(
                modelFilePresent = resolvedModel.ready,
                modelReady = sameReadyProfile,
                activeModelProfile = resolvedModel.profile,
                geminiConfigured = BuildConfig.GEMINI_API_KEY.isNotBlank(),
                archives = ChatArchiveStore.load(context.applicationContext),
                status = when {
                    state.runtimeMode == AgentRuntimeMode.GEMINI &&
                        BuildConfig.GEMINI_API_KEY.isBlank() ->
                        "${state.cloudModel.displayName} · API 키 필요"
                    state.runtimeMode == AgentRuntimeMode.GEMINI ->
                        "${state.cloudModel.displayName} · 클라우드"
                    sameReadyProfile ->
                        "${resolvedModel.profile.displayName} · 온디바이스"
                    resolvedModel.ready -> "${resolvedModel.profile.displayName} 준비됨"
                    else ->
                        "모델 파일이 필요합니다: ${resolvedModel.profile.modelFileName}"
                },
            )
        }
    }

    fun send(
        context: Context,
        input: String,
        controllerVisible: Boolean = false,
    ) {
        val text = input.trim()
        if (text.isEmpty() || generationJob?.isActive == true) return
        if (mutableState.value.runtimeMode == AgentRuntimeMode.GEMINI) {
            sendWithGemini(
                context = context,
                input = text,
                controllerVisible = controllerVisible,
            )
            return
        }

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
                val resolvedModel = OnDeviceModelProfileResolver.resolve(appContext)
                require(resolvedModel.ready) {
                    "모델 파일이 없습니다: ${resolvedModel.modelFile.absolutePath}"
                }

                val engine = LlamaEngine.getInstance(appContext)
                activeEngine = engine
                engine.loadModel(
                    modelFile = resolvedModel.modelFile,
                    visionProjectorFile = resolvedModel.visionProjectorFile,
                    modelFamilyHint = resolvedModel.profile.nativeModelFamilyHint,
                    imageMaxSliceNums = resolvedModel.profile.imageMaxSliceNums,
                    modelDisplayName = resolvedModel.profile.displayName,
                    disableThinking = resolvedModel.profile.disableThinking,
                )
                mutableState.update {
                    it.copy(
                        modelReady = true,
                        activeModelProfile = resolvedModel.profile,
                        status = "답변 생성 중…",
                    )
                }

                // A GUI task starts a fresh bounded agent run. Feeding archived
                // assistant/tool traces into this first classifier caused the
                // small GUI model to copy a prior observe_ui call instead of
                // following the current request. The multi-step controller owns
                // its own action history after this handoff.
                val conversation =
                    if (
                        resolvedModel.profile.plannerPromptStyle ==
                        ModelPlannerPromptStyle.GUI_OWL
                    ) {
                        "Current request: $text\nReturn the required response now."
                    } else {
                        buildConversationPrompt(mutableState.value.messages)
                    }
                val systemPrompt = buildSystemPrompt(
                    context = appContext,
                    modelProfile = resolvedModel.profile,
                )
                val responseTokenLimit =
                    when {
                        resolvedModel.profile.plannerPromptStyle ==
                            ModelPlannerPromptStyle.GUI_OWL ->
                            GUI_OWL_RESPONSE_TOKEN_LIMIT
                        resolvedModel.profile.toolCallProtocol ==
                            ModelToolCallProtocol.EXAONE_JSON_DSL_FALLBACK ->
                            EXAONE_RESPONSE_TOKEN_LIMIT
                        else -> RESPONSE_TOKEN_LIMIT
                    }
                val rawResponse = engine.generate(
                    // 모델이 사용 가능한 툴과 JSON 호출 형식을 알 수 있도록
                    // 공통 툴 정의를 매 요청의 시스템 프롬프트에 포함합니다.
                    systemPrompt = systemPrompt,
                    userPrompt = conversation,
                    predictLength = responseTokenLimit,
                ).ifBlank {
                    "응답을 생성하지 못했습니다."
                }
                val requestedToolName = toolAdapter.requestedToolName(
                    rawResponse,
                    resolvedModel.profile.toolCallProtocol,
                )
                var initialToolCall = toolAdapter.parseToolCall(
                    rawResponse,
                    resolvedModel.profile.toolCallProtocol,
                )
                if (
                    initialToolCall != null &&
                    toolAdapter.validationError(initialToolCall) != null
                ) {
                    Log.w(
                        TAG,
                        "Initial tool arguments failed schema validation; " +
                            "bootstrapping visible GUI planning",
                    )
                    initialToolCall = guiBootstrapCall(
                        context = appContext,
                        modelProfile = resolvedModel.profile,
                        controllerVisible = controllerVisible,
                    )
                }
                if (initialToolCall == null && requestedToolName != null) {
                    // An invented semantic name proves device intent but is not
                    // executable. Ask the model to repair its protocol instead
                    // of mapping it to any app-specific gesture or package.
                    Log.w(
                        TAG,
                        "Unknown tool '$requestedToolName'; requesting protocol repair",
                    )
                }
                if (initialToolCall == null) {
                    // A small local model may describe an intended action
                    // instead of emitting JSON. Give it one protocol-only retry.
                    // Ordinary Q&A can explicitly stay in chat mode.
                    val correctedOutput = engine.generate(
                        systemPrompt = systemPrompt,
                        userPrompt = buildString {
                            appendLine("ORIGINAL_REQUEST:")
                            appendLine(text)
                            appendLine()
                            appendLine("INVALID_FIRST_OUTPUT:")
                            appendLine(rawResponse.take(LOG_TEXT_LIMIT))
                            appendLine()
                            if (
                                resolvedModel.profile.plannerPromptStyle ==
                                ModelPlannerPromptStyle.GUI_OWL
                            ) {
                                appendLine(
                                    "For an Android GUI request, repair the protocol by " +
                                        "observing the current screen first. Return exactly " +
                                        """{"tool":"observe_ui","arguments":{}}.""",
                                )
                            } else {
                                appendLine(
                                    "If the request requires changing or navigating the Android " +
                                        "screen, return exactly one registered tool-call JSON object.",
                                )
                            }
                            appendLine(
                                "If it is ordinary Q&A requiring no device action, return " +
                                    """{"mode":"chat"}.""",
                            )
                        },
                        predictLength = responseTokenLimit,
                    ).trim()
                    Log.i(
                        TAG,
                        "${resolvedModel.profile.displayName} protocol retry response: " +
                            correctedOutput.take(LOG_TEXT_LIMIT),
                    )
                    initialToolCall = toolAdapter.parseToolCall(
                        correctedOutput,
                        resolvedModel.profile.toolCallProtocol,
                    )
                    val correctedUnknownTool =
                        toolAdapter.requestedToolName(
                            correctedOutput,
                            resolvedModel.profile.toolCallProtocol,
                        )
                    if (initialToolCall == null && correctedUnknownTool != null) {
                        Log.w(
                            TAG,
                            "Protocol retry invented '$correctedUnknownTool'; " +
                                "bootstrapping visible GUI planning",
                        )
                        initialToolCall = guiBootstrapCall(
                            context = appContext,
                            modelProfile = resolvedModel.profile,
                            controllerVisible = controllerVisible,
                        )
                    }
                }
                val response = sanitizeModelResponse(
                    modelOutput = rawResponse,
                    currentUserText = text,
                )
                Log.i(TAG, "Local VLM raw response: ${rawResponse.take(LOG_TEXT_LIMIT)}")
                Log.i(TAG, "Local VLM final response: ${response.take(LOG_TEXT_LIMIT)}")

                // 일반 답변은 그대로 표시하고, 약속된 JSON이면 사용자에게 노출하지 않고
                // LocalDeviceToolAdapter를 통해 실제 Android Device Tool을 실행합니다.
                val toolCall = initialToolCall
                if (toolCall == null) {
                    if (response.contains("<tool_call", ignoreCase = true)) {
                        Log.w(TAG, "Rejected malformed tool-call markup")
                        finishWithAssistantMessage(
                            "요청에 맞는 기기 동작을 선택하지 못했습니다. 다시 말씀해 주세요.",
                            status = "툴 선택 실패",
                        )
                    } else {
                        Log.i(TAG, "No tool call; returning assistant text")
                        finishWithAssistantMessage(response)
                    }
                } else {
                    Log.i(
                        TAG,
                        "Tool call: ${toolCall.name} ${toolCall.arguments}",
                    )
                    val outcome = agentController.run(
                        context = appContext,
                        engine = engine,
                        goal = text,
                        initialCall = toolCall,
                        visionAvailable = resolvedModel.visionAvailable,
                        modelProfile = resolvedModel.profile,
                        onProgress = { progress ->
                            mutableState.update { state ->
                                state.copy(status = progress)
                            }
                        },
                        onTrace = ::appendTrace,
                    )
                    Log.i(
                        TAG,
                        "Agent completed: steps=${outcome.steps}, status=${outcome.status}",
                    )
                    finishWithAssistantMessage(
                        text = outcome.message,
                        status = outcome.status,
                    )
                }
            } catch (_: CancellationException) {
                mutableState.update {
                    it.copy(
                        generating = false,
                        status = "생성 중단됨",
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
            } finally {
                activeEngine = null
                generationJob = null
            }
        }
    }

    fun sendWithGemini(
        context: Context,
        input: String,
        controllerVisible: Boolean,
    ) {
        if (input.isBlank() || generationJob?.isActive == true) return
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isBlank()) {
            mutableState.update {
                it.copy(
                    status = "${it.cloudModel.displayName} · API 키 필요",
                    error = "프로젝트 local.properties에 GEMINI_API_KEY=... 형식으로 추가하세요.",
                )
            }
            return
        }
        val appContext = context.applicationContext
        val selectedModel = mutableState.value.cloudModel
        mutableState.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    id = nextMessageId++,
                    role = ChatRole.USER,
                    text = input,
                ),
                generating = true,
                status = "${selectedModel.displayName} · 화면 준비 중…",
                error = null,
            )
        }
        generationJob = scope.launch {
            try {
                val outcome = geminiAgentController.run(
                    context = appContext,
                    apiKey = apiKey,
                    model = selectedModel,
                    goal = input,
                    controllerVisible = controllerVisible,
                    onProgress = { progress ->
                        mutableState.update { state -> state.copy(status = progress) }
                    },
                    onTrace = ::appendTrace,
                )
                Log.i(
                    TAG,
                    "Gemini agent completed: steps=${outcome.steps}, " +
                        "status=${outcome.status}",
                )
                finishWithAssistantMessage(
                    text = outcome.message,
                    status = outcome.status,
                )
            } catch (_: CancellationException) {
                mutableState.update {
                    it.copy(
                        generating = false,
                        status = "Gemini 작업 중단됨",
                    )
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Gemini agent failed", error)
                mutableState.update {
                    it.copy(
                        generating = false,
                        status = "Gemini 작업 실패",
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
            } finally {
                generationJob = null
            }
        }
    }

    fun toggleRuntimeMode() {
        if (generationJob?.isActive == true) return
        mutableState.update { state ->
            val mode = when (state.runtimeMode) {
                AgentRuntimeMode.LOCAL -> AgentRuntimeMode.GEMINI
                AgentRuntimeMode.GEMINI -> AgentRuntimeMode.LOCAL
            }
            state.copy(
                runtimeMode = mode,
                error = null,
                status = when (mode) {
                    AgentRuntimeMode.LOCAL ->
                        if (state.modelReady) {
                            "${state.activeModelProfile.displayName} · 온디바이스"
                        } else {
                            "${state.activeModelProfile.displayName} 준비됨"
                        }
                    AgentRuntimeMode.GEMINI ->
                        if (state.geminiConfigured) {
                            "${state.cloudModel.displayName} · 클라우드"
                        } else {
                            "${state.cloudModel.displayName} · API 키 필요"
                        }
                },
            )
        }
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
        activeEngine?.cancelGeneration()
        localPrivacyPlanner.cancel()
        generationJob?.cancel()
        mutableState.update {
            it.copy(generating = false, status = "생성 중단됨")
        }
    }

    private fun appendTrace(event: AgentTraceEvent) {
        val (role, text) = when (event) {
            is AgentTraceEvent.ToolCall -> ChatRole.TOOL_CALL to buildString {
                append("Step ${event.step} · ${event.call.name}")
                if (event.call.arguments.length() > 0) {
                    append("\n")
                    append(event.call.arguments.toString(2))
                }
            }
            is AgentTraceEvent.RuntimeRoute -> ChatRole.TOOL_RESULT to
                "Step ${event.step} · ${event.runtime}\n${event.reason}"
            is AgentTraceEvent.ToolResult -> ChatRole.TOOL_RESULT to buildString {
                append(if (event.automatic) "자동 화면 재관찰" else "${event.call.name} 결과")
                append("\n")
                append(AgentTraceFormatter.format(event.result))
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

    private fun finishWithAssistantMessage(
        text: String,
        status: String? = null,
    ) {
        mutableState.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    id = nextMessageId++,
                    role = ChatRole.ASSISTANT,
                    text = text,
                ),
                generating = false,
                status = status ?: when (it.runtimeMode) {
                    AgentRuntimeMode.LOCAL ->
                        "${it.activeModelProfile.displayName} · 온디바이스"
                    AgentRuntimeMode.GEMINI ->
                        "${it.cloudModel.displayName} · 클라우드"
                },
            )
        }
    }

    fun clear() {
        if (generationJob?.isActive == true) return
        mutableState.update {
            it.copy(
                messages = emptyList(),
                error = null,
                status = when (it.runtimeMode) {
                    AgentRuntimeMode.LOCAL ->
                        if (it.modelReady) {
                            "${it.activeModelProfile.displayName} · 온디바이스"
                        } else {
                            it.status
                        }
                    AgentRuntimeMode.GEMINI ->
                        if (it.geminiConfigured) {
                            "${it.cloudModel.displayName} · 클라우드"
                        } else {
                            "${it.cloudModel.displayName} · API 키 필요"
                        }
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

    private fun buildConversationPrompt(messages: List<ChatMessage>): String = buildString {
        val currentRequest = messages.lastOrNull { it.role == ChatRole.USER }?.text.orEmpty()
        val history = messages.dropLast(1).takeLast(MAX_CONTEXT_MESSAGES - 1)
        if (history.isNotEmpty()) {
            appendLine("Previous conversation for context:")
        }
        history.forEach { message ->
            when (message.role) {
                ChatRole.USER -> appendLine("User: ${message.text}")
                ChatRole.ASSISTANT -> appendLine("Assistant: ${message.text}")
                ChatRole.TOOL_CALL, ChatRole.TOOL_RESULT -> Unit
            }
        }
        appendLine()
        appendLine("Current request:")
        append(currentRequest)
    }

    /**
     * The local VLM is already wrapped in its native chat template. If it imitates
     * the plain-text history and emits "User: <same request>", do not expose or
     * speak that prompt echo as though it were an assistant answer.
     */
    private fun sanitizeModelResponse(
        modelOutput: String,
        currentUserText: String,
    ): String {
        val cleaned = modelOutput
            .trim()
            .removePrefix("Assistant:")
            .removePrefix("Assistant：")
            .trim()
        val withoutUserLabel = cleaned.replaceFirst(
            Regex(
                """^(?:user|사용자|유저)\s*[,，:：]?\s*""",
                RegexOption.IGNORE_CASE,
            ),
            "",
        ).trim()
        val echoedRequest = normalizedForEchoComparison(withoutUserLabel) ==
            normalizedForEchoComparison(currentUserText)
        return if (echoedRequest) {
            Log.w(TAG, "Rejected prompt echo from local VLM")
            "요청을 반복하지 않고 실행할 작업을 판단하지 못했습니다. 다시 말씀해 주세요."
        } else {
            cleaned.ifBlank { "응답을 생성하지 못했습니다." }
        }
    }

    private fun normalizedForEchoComparison(text: String): String =
        text.lowercase().filter(Char::isLetterOrDigit)

    private fun guiBootstrapCall(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") modelProfile: OnDeviceModelProfile,
        @Suppress("UNUSED_PARAMETER") controllerVisible: Boolean,
    ): DeviceToolCall = DeviceToolCall(ObserveUiDeviceTool.NAME)

    private fun buildSystemPrompt(
        context: Context,
        modelProfile: OnDeviceModelProfile,
    ): String = buildString {
        if (modelProfile.plannerPromptStyle == ModelPlannerPromptStyle.GUI_OWL) {
            appendLine(
                """You are a local Android assistant and GUI agent.
Reply in the user's language for ordinary questions that need no phone action.
When a request requires opening, finding, navigating, tapping, swiping, typing,
or changing the phone screen, return exactly one allowed tool JSON and no prose.
Start by observing the current screen. Use go_home only when the observed UI
has no safe visible route toward the user's goal. Never invent open_app,
package launch, shell, intent, or MCP tools. For an external GUI task, the
first response is {"tool":"observe_ui","arguments":{}}. Do not add parentheses
to a tool name.""",
            )
            return@buildString
        }
        appendLine(SYSTEM_PROMPT)
        if (
            modelProfile.toolCallProtocol ==
            ModelToolCallProtocol.EXAONE_JSON_DSL_FALLBACK
        ) {
            appendLine("Do not output reasoning or <think> blocks.")
        }
        appendLine()
        appendLine("LOADED_SKILL:")
        appendLine(
            AgentSkillLoader.load(
                context,
                AgentSkillLoader.GUI_APP_NAVIGATION,
            ),
        )
        appendLine()
        append(toolAdapter.promptSectionFor(modelProfile.toolCallProtocol))
    }

    private const val MAX_CONTEXT_MESSAGES = 8
    private const val RESPONSE_TOKEN_LIMIT = 384
    private const val GUI_OWL_RESPONSE_TOKEN_LIMIT = 96
    private const val EXAONE_RESPONSE_TOKEN_LIMIT = 192
    private const val SYSTEM_PROMPT =
        """You are a helpful assistant running locally on an Android phone.
Reply naturally and concisely in the same language as the user.
The conversation history follows. Continue only as the assistant.
Do not prefix responses with "User", "유저", "Assistant", or another role label.
Never repeat or paraphrase the user's request as the entire answer.
When the user asks to open, find, navigate, tap, swipe, or control anything on
the phone, you MUST return exactly one registered tool JSON object and no prose.
For an external GUI task with no current UI observation, the first tool must be
{"tool":"observe_ui","arguments":{}}. Never invent a node id before observing.
Do not say that you will perform an action without calling a tool.
Only ordinary questions that require no phone action may receive a text answer.
Never emit {"status":"complete"}; completion must use the finish tool."""
    private const val TAG = "LocalChatRepository"
    private const val LOG_TEXT_LIMIT = 500
}
