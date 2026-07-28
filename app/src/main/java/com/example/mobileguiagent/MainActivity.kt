package com.example.mobileguiagent

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.mobileguiagent.mcp.McpServerRepository
import com.example.mobileguiagent.model.ChatMessage
import com.example.mobileguiagent.model.ChatRole
import com.example.mobileguiagent.model.LocalChatRepository
import com.example.mobileguiagent.model.LocalChatState
import com.example.mobileguiagent.repository.AgentRepository
import com.example.mobileguiagent.ui.theme.MobileGUIAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 릴레이 주소를 화면 없이 넣을 수 있게 열어둔다. 아직 설정 화면이 없어서인데,
        // 여기 값이 있어야만 앱이 밖으로 접속한다. 기본은 아무 데도 안 붙는 것이다.
        //   adb shell am start -n com.example.mobileguiagent/.MainActivity \
        //     --es relay_url http://127.0.0.1:8790 --es relay_token test
        applyRelaySettings(intent)
        LocalChatRepository.refresh(applicationContext)
        McpServerRepository.start(applicationContext)

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
                        LocalChatRepository.send(applicationContext, message)
                    },
                    onClear = LocalChatRepository::clear,
                    onOpenAccessibilitySettings = {
                        AgentRepository.openAccessibilitySettings(this)
                    },
                )
                }
            }
        }
    }

    /**
     * 이미 떠 있는 앱에 인텐트가 오면 onCreate가 아니라 여기로 온다. 실측으로
     * "Activity not started, intent has been delivered to currently running
     * top-most instance"가 나면서 설정이 조용히 버려졌다.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyRelaySettings(intent)
    }

    /** 설정이 바뀌었으면 서비스에게 알린다. 안 알리면 옛 주소로 계속 붙는다. */
    private fun applyRelaySettings(intent: Intent?) {
        if (!com.example.mobileguiagent.mcp.RelaySettings.applyFrom(this, intent)) return
        startService(
            Intent(this, com.example.mobileguiagent.mcp.PocketMcpService::class.java)
                .setAction(com.example.mobileguiagent.mcp.PocketMcpService.ACTION_RECONNECT_RELAY),
        )
    }

    override fun onResume() {
        super.onResume()
        AgentRepository.syncServiceConnection()
        LocalChatRepository.refresh(applicationContext)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LocalModelChatScreen(
    onOpenVault: () -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val chatState by LocalChatRepository.state.collectAsState()
    val agentState by AgentRepository.state.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    fun submit() {
        val message = input.trim()
        if (message.isEmpty() || chatState.generating || !chatState.modelFilePresent) return
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
                        Text("Local GUI Agent")
                        Text(
                            chatState.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenVault) { Text("내 정보") }
                    TextButton(
                        onClick = onClear,
                        enabled = chatState.messages.isNotEmpty() && !chatState.generating,
                    ) {
                        Text("대화 지우기")
                    }
                },
            )
        },
        bottomBar = {
            ChatInput(
                input = input,
                onInputChange = { input = it },
                onSubmit = ::submit,
                enabled = chatState.modelFilePresent && !chatState.generating,
                generating = chatState.generating,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (!agentState.serviceConnected) {
                AccessibilityNotice(onOpenAccessibilitySettings)
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
                                Text("로컬 모델이 답변을 생성하고 있습니다.")
                            }
                        }
                    }
                }
            }
        }
    }
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
                "MiniCPM-V 4.6",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                if (state.modelFilePresent) {
                    "휴대폰에서 실행되는 로컬 모델과 대화를 시작하세요."
                } else {
                    "모델 파일을 앱 저장소에 넣어야 대화를 시작할 수 있습니다."
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
private fun ChatInput(
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
    generating: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        )
        Button(
            onClick = onSubmit,
            enabled = enabled && input.isNotBlank(),
        ) {
            Text("전송")
        }
    }
}
