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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.mcp.McpServerRepository
import com.example.mobileguiagent.model.ChatMessage
import com.example.mobileguiagent.model.ChatArchive
import com.example.mobileguiagent.model.ChatRole
import com.example.mobileguiagent.model.LocalChatRepository
import com.example.mobileguiagent.model.LocalChatState
import com.example.mobileguiagent.remote.RemoteConnectionDialog
import com.example.mobileguiagent.remote.RemoteConnectionNotice
import com.example.mobileguiagent.remote.RemoteDeviceRepository
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
        RemoteDeviceRepository.refresh(applicationContext)
        if (
            RemoteDeviceRepository.state.value.configured &&
            !RemoteDeviceRepository.state.value.running
        ) {
            RemoteDeviceRepository.start(applicationContext)
        }

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
                CloudAgentScreen(
                    onOpenVault = { showVault = true },
                    onSend = { message ->
                        LocalChatRepository.send(
                            context = applicationContext,
                            input = message,
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
private fun CloudAgentScreen(
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
    val remoteDeviceState by RemoteDeviceRepository.state.collectAsState()
    val voiceState by MoonshineKoreanRepository.state.collectAsState()
    val wakeWordState by WakeWordRepository.state.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var startWakeWordAfterPermission by remember { mutableStateOf(false) }
    var showArchives by remember { mutableStateOf(false) }
    var showCredentialVault by remember { mutableStateOf(false) }
    var showRemoteConnection by remember { mutableStateOf(false) }
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
            AppHeader(
                state = chatState,
                mcpRunning = mcpState.running,
                remoteConnected = remoteDeviceState.running,
                onCycleModel = LocalChatRepository::cycleCloudModel,
                onToggleMcp = onToggleMcp,
                onOpenRemote = { showRemoteConnection = true },
                onOpenArchives = { showArchives = true },
                onOpenCredentials = { showCredentialVault = true },
                onOpenVault = onOpenVault,
                onArchive = { LocalChatRepository.archiveCurrent(context) },
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
                .background(MaterialTheme.colorScheme.background)
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

            if (remoteDeviceState.configured || remoteDeviceState.error != null) {
                RemoteConnectionNotice(
                    state = remoteDeviceState,
                    onToggle = {
                        if (remoteDeviceState.running) {
                            RemoteDeviceRepository.stop(context)
                        } else {
                            RemoteDeviceRepository.start(context)
                        }
                    },
                    onConfigure = { showRemoteConnection = true },
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
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(
                        items = chatState.messages,
                        key = ChatMessage::id,
                    ) { message ->
                        ChatBubble(message)
                    }
                    if (chatState.generating) {
                        item {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    "Gemini가 화면을 판단하고 있습니다.",
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
    if (showRemoteConnection) {
        RemoteConnectionDialog(
            currentEndpoint = remoteDeviceState.endpoint,
            currentDeviceId = remoteDeviceState.deviceId,
            onDismiss = { showRemoteConnection = false },
            onSaved = {
                RemoteDeviceRepository.start(context)
                showRemoteConnection = false
            },
        )
    }
}

@Composable
private fun AppHeader(
    state: LocalChatState,
    mcpRunning: Boolean,
    remoteConnected: Boolean,
    onCycleModel: () -> Unit,
    onToggleMcp: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenArchives: () -> Unit,
    onOpenCredentials: () -> Unit,
    onOpenVault: () -> Unit,
    onArchive: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Tupac",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(
                                    if (state.error == null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    CircleShape,
                                ),
                        )
                        Text(
                            state.status,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ControlChip(
                    label = state.cloudModel.displayName,
                    active = true,
                    onClick = onCycleModel,
                )
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ControlChip(
                    label = if (mcpRunning) "MCP 연결됨" else "MCP",
                    active = mcpRunning,
                    onClick = onToggleMcp,
                )
                ControlChip(
                    label = if (remoteConnected) "클라우드 연결됨" else "클라우드",
                    active = remoteConnected,
                    onClick = onOpenRemote,
                )
                ControlChip("기록", onClick = onOpenArchives)
                ControlChip("계정", onClick = onOpenCredentials)
                ControlChip("내 정보", onClick = onOpenVault)
                ControlChip(
                    "보관",
                    enabled = state.messages.isNotEmpty() && !state.generating,
                    onClick = onArchive,
                )
            }
        }
    }
}

@Composable
private fun ControlChip(
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
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
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("MCP 연결", style = MaterialTheme.typography.titleSmall)
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "휴대폰에서\n바로 실행하세요.",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                if (state.geminiConfigured) {
                    "민감값을 마스킹한 화면 정보는 Gemini API로 전송되고 동작은 폰에서 실행됩니다."
                } else {
                    "local.properties에 GEMINI_API_KEY를 설정해야 사용할 수 있습니다."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    state.cloudModel.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "앱 실행, 화면 탐색, 입력과 선택을 한 문장으로 요청할 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
            }
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
            modifier = Modifier.widthIn(max = 360.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 5.dp,
                bottomEnd = if (isUser) 5.dp else 16.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (isUser) 0.dp else 1.dp,
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
            .padding(horizontal = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCall) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = RoundedCornerShape(12.dp),
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
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (wakeWordState.enabled) {
                    "Hey Tupac 대기 중"
                } else {
                    "백그라운드 음성 호출"
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
                shape = RoundedCornerShape(16.dp),
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
