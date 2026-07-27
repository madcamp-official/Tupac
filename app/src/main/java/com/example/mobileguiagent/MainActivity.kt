package com.example.mobileguiagent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.agent.AgentRuntimeMode
import com.example.mobileguiagent.mcp.McpServerRepository
import com.example.mobileguiagent.model.ChatMessage
import com.example.mobileguiagent.model.ChatArchive
import com.example.mobileguiagent.model.ChatRole
import com.example.mobileguiagent.model.LocalChatRepository
import com.example.mobileguiagent.model.LocalChatState
import com.example.mobileguiagent.ui.theme.MobileGUIAgentTheme
import com.example.mobileguiagent.voice.MoonshineKoreanState
import com.example.mobileguiagent.voice.MoonshineKoreanRepository
import com.example.mobileguiagent.voice.VoiceCommandService
import com.example.mobileguiagent.voice.WakeWordRepository
import com.example.mobileguiagent.voice.WakeWordService
import com.example.mobileguiagent.voice.WakeWordState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalChatRepository.refresh(applicationContext)
        MoonshineKoreanRepository.refresh(applicationContext)

        setContent {
            MobileGUIAgentTheme {
                // 개인정보 등록 화면은 별도로 띄운다. 값을 넣는 통로를 앱 안에
                // 하나로 두기 위해서다(MCP로는 쓰기를 열지 않는다).
                var showVault by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                if (showVault) {
                    com.example.mobileguiagent.secret.SecretVaultScreen(
                        onBack = { showVault = false },
                    )
                } else {
                LocalModelChatScreen(
                    onOpenVault = { showVault = true },
                    onSend = { message ->
                        LocalChatRepository.send(
                            context = applicationContext,
                            input = message,
                            controllerVisible = true,
                        )
                    },
                    onClear = LocalChatRepository::clear,
                    onStop = LocalChatRepository::stopGeneration,
                    onToggleMcp = {
                        if (McpServerRepository.state.value.running) {
                            McpServerRepository.stop(applicationContext)
                        } else {
                            McpServerRepository.start(applicationContext)
                        }
                    },
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LocalChatRepository.refresh(applicationContext)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LocalModelChatScreen(
    onOpenVault: () -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onStop: () -> Unit,
    onToggleMcp: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val chatState by LocalChatRepository.state.collectAsState()
    val accessibilityConnected by
        AgentAccessibilityService.connectionState.collectAsState()
    val mcpState by McpServerRepository.state.collectAsState()
    val voiceState by MoonshineKoreanRepository.state.collectAsState()
    val wakeWordState by WakeWordRepository.state.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var startWakeWordAfterPermission by remember { mutableStateOf(false) }
    var showArchives by remember { mutableStateOf(false) }
    var showCredentialVault by remember { mutableStateOf(false) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (startWakeWordAfterPermission) {
                WakeWordService.start(context)
            } else {
                VoiceCommandService.start(context)
            }
        }
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (!Settings.canDrawOverlays(context)) return@rememberLauncherForActivityResult
        val missingPermissions = buildList {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missingPermissions.isEmpty()) {
            if (startWakeWordAfterPermission) {
                WakeWordService.start(context)
            } else {
                VoiceCommandService.start(context)
            }
        } else {
            microphonePermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    fun requestVoicePermission(startWakeWord: Boolean) {
        startWakeWordAfterPermission = startWakeWord
        if (!Settings.canDrawOverlays(context)) {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
            return
        }
        val missingPermissions = buildList {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missingPermissions.isEmpty()) {
            if (startWakeWord) {
                WakeWordService.start(context)
            } else {
                VoiceCommandService.start(context)
            }
        } else {
            microphonePermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    fun toggleWakeWord() {
        if (wakeWordState.enabled) {
            WakeWordService.stop(context)
            return
        }
        requestVoicePermission(startWakeWord = true)
    }

    fun toggleVoiceCommand() {
        if (voiceState.listening || voiceState.loading || voiceState.downloading) {
            VoiceCommandService.stop(context)
            return
        }
        if (wakeWordState.enabled) {
            WakeWordService.commandNow(context)
        } else {
            requestVoicePermission(startWakeWord = false)
        }
    }

    fun submit() {
        val message = input.trim()
        if (message.isEmpty() || chatState.generating || !chatState.readyForInput) return
        input = ""
        onSend(message)
    }

    LaunchedEffect(chatState.messages.size, chatState.generating) {
        if (chatState.messages.isNotEmpty()) {
            listState.animateScrollToItem(chatState.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (chatState.runtimeMode == AgentRuntimeMode.GEMINI) {
                                "Tupac"
                            } else {
                                "Tupac"
                            },
                        )
                        Text(
                            chatState.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = LocalChatRepository::toggleRuntimeMode) {
                        Text(
                            if (chatState.runtimeMode == AgentRuntimeMode.GEMINI) {
                                "로컬"
                            } else {
                                "Gemini"
                            },
                        )
                    }
                    TextButton(onClick = onToggleMcp) {
                        Text(if (mcpState.running) "MCP 끄기" else "MCP 켜기")
                    }
                    TextButton(onClick = { showArchives = true }) {
                        Text("기록")
                    }
                    TextButton(onClick = { showCredentialVault = true }) {
                        Text("보안")
                    }
                    TextButton(onClick = onOpenVault) {
                        Text("내 정보")
                    }
                    TextButton(
                        onClick = { LocalChatRepository.archiveCurrent(context) },
                        enabled = chatState.messages.isNotEmpty() && !chatState.generating,
                    ) {
                        Text("보관")
                    }
                },
            )
        },
        bottomBar = {
            ChatInput(
                input = input,
                onInputChange = { input = it },
                onSubmit = ::submit,
                onStop = onStop,
                voiceState = voiceState,
                wakeWordState = wakeWordState,
                onVoiceCommand = ::toggleVoiceCommand,
                onToggleWakeWord = ::toggleWakeWord,
                enabled = chatState.readyForInput && !chatState.generating,
                generating = chatState.generating,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (!accessibilityConnected) {
                AccessibilityNotice(onOpenAccessibilitySettings)
            }

            if (mcpState.running) {
                McpConnectionNotice(
                    endpoint = mcpState.endpoints.firstOrNull(),
                    pairingToken = mcpState.pairingToken,
                )
            }

            chatState.error?.let { error ->
                Text(
                    text = error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (chatState.messages.isEmpty()) {
                EmptyChat(chatState)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = chatState.messages,
                        key = ChatMessage::id,
                    ) { message ->
                        ChatBubble(message)
                    }
                    if (chatState.generating) {
                        item {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                                Text(
                                    if (chatState.runtimeMode == AgentRuntimeMode.GEMINI) {
                                        "Gemini가 화면을 판단하고 있습니다."
                                    } else {
                                        "로컬 모델이 답변을 생성하고 있습니다."
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showArchives) {
        ArchiveDialog(
            archives = chatState.archives,
            onDismiss = { showArchives = false },
            onOpen = { archive ->
                LocalChatRepository.openArchive(archive.id)
                showArchives = false
            },
            onDelete = { archive ->
                LocalChatRepository.deleteArchive(context, archive.id)
            },
            onClearCurrent = {
                onClear()
                showArchives = false
            },
        )
    }
    if (showCredentialVault) {
        CredentialVaultDialog(
            onDismiss = { showCredentialVault = false },
        )
    }
}

/**
 * Shows the actual authenticated MCP URL.
 *
 * The server rejects the base endpoint without a pairing token. Previously the
 * UI exposed only the base URL, leaving external clients unable to connect
 * after the token changed.
 */
@Composable
private fun McpConnectionNotice(
    endpoint: String?,
    pairingToken: String,
) {
    if (endpoint.isNullOrBlank() || pairingToken.isBlank()) return
    val context = LocalContext.current
    val pairingUrl = "$endpoint?pairing_token=$pairingToken"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("MCP 연결 주소", style = MaterialTheme.typography.titleSmall)
            Text(
                pairingUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("PocketMCP pairing URL", pairingUrl),
                    )
                },
            ) {
                Text("주소 복사")
            }
        }
    }
}

@Composable
private fun ArchiveDialog(
    archives: List<ChatArchive>,
    onDismiss: () -> Unit,
    onOpen: (ChatArchive) -> Unit,
    onDelete: (ChatArchive) -> Unit,
    onClearCurrent: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("대화 기록") },
        text = {
            if (archives.isEmpty()) {
                Text("보관된 대화가 없습니다.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(archives, key = ChatArchive::id) { archive ->
                        Card(onClick = { onOpen(archive) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(archive.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${archive.messages.size}개 항목",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onDelete(archive) }) {
                                    Text("삭제")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        },
        dismissButton = {
            TextButton(onClick = onClearCurrent) { Text("현재 대화 지우기") }
        },
    )
}

@Composable
private fun EmptyChat(state: LocalChatState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (state.runtimeMode == AgentRuntimeMode.GEMINI) {
                    state.cloudModel.displayName
                } else {
                    state.activeModelProfile.displayName
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                when (state.runtimeMode) {
                    AgentRuntimeMode.GEMINI ->
                        if (state.geminiConfigured) {
                            "현재 화면은 Gemini API로 전송되며 동작은 폰에서 실행됩니다."
                        } else {
                            "local.properties에 GEMINI_API_KEY를 설정해야 사용할 수 있습니다."
                        }
                    AgentRuntimeMode.LOCAL ->
                        if (state.modelFilePresent) {
                            "휴대폰에서 실행되는 로컬 모델과 대화를 시작하세요."
                        } else {
                            "모델 파일을 앱 저장소에 넣어야 대화를 시작할 수 있습니다."
                        }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AccessibilityNotice(onOpenAccessibilitySettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "GUI 조작을 사용하려면 접근성 서비스를 켜세요.",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onOpenAccessibilitySettings) {
                Text("설정")
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val isTrace = message.role == ChatRole.TOOL_CALL || message.role == ChatRole.TOOL_RESULT
    if (isTrace) {
        ToolTraceCard(message)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ToolTraceCard(message: ChatMessage) {
    val isCall = message.role == ChatRole.TOOL_CALL
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCall) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                if (isCall) "도구 호출" else "도구 결과",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                message.text,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ChatInput(
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    voiceState: MoonshineKoreanState,
    wakeWordState: WakeWordState,
    onVoiceCommand: () -> Unit,
    onToggleWakeWord: () -> Unit,
    enabled: Boolean,
    generating: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (wakeWordState.enabled) {
                    "Hey Tupac 백그라운드 대기 중"
                } else {
                    "Hey Tupac 백그라운드 호출"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onToggleWakeWord) {
                Text(if (wakeWordState.enabled) "끄기" else "켜기")
            }
        }
        if (
            wakeWordState.enabled ||
            wakeWordState.preparing ||
            wakeWordState.error != null ||
            voiceState.listening ||
            voiceState.loading ||
            voiceState.downloading ||
            voiceState.currentText.isNotBlank()
        ) {
            Text(
                text = voiceState.currentText.ifBlank {
                    wakeWordState.error ?: if (
                        wakeWordState.enabled || wakeWordState.preparing
                    ) {
                        wakeWordState.status
                    } else {
                        voiceState.status
                    }
                },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val voiceActive =
                voiceState.listening ||
                    voiceState.loading ||
                    voiceState.downloading
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = {
                    Text(if (generating) "답변 생성 중…" else "메시지를 입력하세요")
                },
                minLines = 1,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                trailingIcon = {
                    IconButton(
                        onClick = onVoiceCommand,
                        enabled = !generating,
                        modifier = Modifier.semantics {
                            contentDescription = if (voiceActive) {
                                "음성 명령 중지"
                            } else {
                                "음성 명령 시작"
                            }
                        },
                    ) {
                        if (voiceActive) {
                            StopGlyph(
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            MicGlyph(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
            FilledIconButton(
                onClick = if (generating) onStop else onSubmit,
                enabled = generating || (enabled && input.isNotBlank()),
                modifier = Modifier
                    .size(52.dp)
                    .semantics {
                        contentDescription = if (generating) {
                            "답변 생성 중단"
                        } else {
                            "메시지 전송"
                        }
                    },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                if (generating) {
                    StopGlyph(color = MaterialTheme.colorScheme.surface)
                } else {
                    ArrowUpGlyph(color = MaterialTheme.colorScheme.surface)
                }
            }
        }
    }
}

@Composable
private fun MicGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val strokeWidth = 2.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.34f, size.height * 0.08f),
            size = androidx.compose.ui.geometry.Size(
                size.width * 0.32f,
                size.height * 0.52f,
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                size.width * 0.16f,
                size.width * 0.16f,
            ),
            style = Stroke(width = strokeWidth),
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.2f, size.height * 0.28f),
            size = androidx.compose.ui.geometry.Size(
                size.width * 0.6f,
                size.height * 0.48f,
            ),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.5f, size.height * 0.76f),
            end = Offset(size.width * 0.5f, size.height * 0.9f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.35f, size.height * 0.9f),
            end = Offset(size.width * 0.65f, size.height * 0.9f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun StopGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(22.dp)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.27f, size.height * 0.27f),
            size = androidx.compose.ui.geometry.Size(
                size.width * 0.46f,
                size.height * 0.46f,
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        )
    }
}

@Composable
private fun ArrowUpGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val strokeWidth = 2.dp.toPx()
        drawLine(
            color = color,
            start = Offset(size.width * 0.5f, size.height * 0.8f),
            end = Offset(size.width * 0.5f, size.height * 0.2f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.5f, size.height * 0.2f),
            end = Offset(size.width * 0.25f, size.height * 0.45f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.5f, size.height * 0.2f),
            end = Offset(size.width * 0.75f, size.height * 0.45f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}
