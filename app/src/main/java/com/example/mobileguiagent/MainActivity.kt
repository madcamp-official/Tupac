package com.example.mobileguiagent

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.mobileguiagent.model.UiTreeModelRepository
import com.example.mobileguiagent.model.UiTreeModelState
import com.example.mobileguiagent.mcp.McpServerRepository
import com.example.mobileguiagent.mcp.McpServerState
import com.example.mobileguiagent.repository.AgentAction
import com.example.mobileguiagent.repository.AgentRepository
import com.example.mobileguiagent.repository.AgentUiState
import com.example.mobileguiagent.ui.theme.MobileGUIAgentTheme
import com.example.mobileguiagent.voice.MoonshineKoreanRepository
import com.example.mobileguiagent.voice.MoonshineKoreanState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startKoreanVoiceWhenReady()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MoonshineKoreanRepository.finalTranscripts.collect { transcript ->
                    Log.i(VOICE_AGENT_TAG, "Forwarding STT result to MiniCPM: $transcript")
                    MoonshineKoreanRepository.stopListening()
                    MoonshineKoreanRepository.markCommandForwarded(transcript)
                    UiTreeModelRepository.updateGoal(transcript)
                    UiTreeModelRepository.runSettingsTreeTest(applicationContext)
                }
            }
        }
        setContent {
            MobileGUIAgentTheme {
                AgentApp(
                    onOpenAccessibilitySettings = {
                        AgentRepository.openAccessibilitySettings(this)
                    },
                    onCommand = { command ->
                        AgentRepository.runCommand(this, command)
                    },
                    onAction = { action ->
                        AgentRepository.runAction(applicationContext, action)
                    },
                    onModelGoalChange = UiTreeModelRepository::updateGoal,
                    onRunUiTreeModelTest = {
                        UiTreeModelRepository.runSettingsTreeTest(applicationContext)
                    },
                    onStartMcpServer = {
                        McpServerRepository.start(applicationContext)
                    },
                    onStopMcpServer = {
                        McpServerRepository.stop(applicationContext)
                    },
                    onPrepareKoreanVoice = {
                        MoonshineKoreanRepository.ensureReady(applicationContext)
                    },
                    onStartKoreanVoice = {
                        if (
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            startKoreanVoiceWhenReady()
                        } else {
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStopKoreanVoice = MoonshineKoreanRepository::stopListening,
                    onTestKoreanVoice = {
                        MoonshineKoreanRepository.runSampleTest(applicationContext)
                    },
                )
            }
        }
        UiTreeModelRepository.refresh(applicationContext)
        MoonshineKoreanRepository.refresh(applicationContext)
        McpServerRepository.start(applicationContext)
        handleDebugIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AgentRepository.syncServiceConnection()
        UiTreeModelRepository.refresh(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent?) {
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (isDebuggable && intent?.action == ACTION_RUN_WIFI_POC) {
            window.decorView.post {
                AgentRepository.runAction(applicationContext, AgentAction.OpenWifi)
            }
        }
        if (isDebuggable && intent?.action == ACTION_PREPARE_MOONSHINE_KOREAN) {
            MoonshineKoreanRepository.ensureReady(applicationContext)
        }
        if (isDebuggable && intent?.action == ACTION_START_MOONSHINE_KOREAN) {
            MoonshineKoreanRepository.ensureReady(applicationContext) {
                MoonshineKoreanRepository.onMicPermissionGranted()
                MoonshineKoreanRepository.startListening()
            }
        }
        if (isDebuggable && intent?.action == ACTION_TEST_MOONSHINE_KOREAN) {
            MoonshineKoreanRepository.runSampleTest(applicationContext)
        }
    }

    private fun startKoreanVoiceWhenReady() {
        MoonshineKoreanRepository.ensureReady(applicationContext) {
            MoonshineKoreanRepository.onMicPermissionGranted()
            MoonshineKoreanRepository.startListening()
        }
    }

    companion object {
        private const val VOICE_AGENT_TAG = "VoiceAgentBridge"
        const val ACTION_RUN_WIFI_POC = "com.example.mobileguiagent.action.RUN_WIFI_POC"
        const val ACTION_PREPARE_MOONSHINE_KOREAN =
            "com.example.mobileguiagent.action.PREPARE_MOONSHINE_KOREAN"
        const val ACTION_START_MOONSHINE_KOREAN =
            "com.example.mobileguiagent.action.START_MOONSHINE_KOREAN"
        const val ACTION_TEST_MOONSHINE_KOREAN =
            "com.example.mobileguiagent.action.TEST_MOONSHINE_KOREAN"
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AgentApp(
    onOpenAccessibilitySettings: () -> Unit,
    onCommand: (String) -> Unit,
    onAction: (AgentAction) -> Unit,
    onModelGoalChange: (String) -> Unit,
    onRunUiTreeModelTest: () -> Unit,
    onStartMcpServer: () -> Unit,
    onStopMcpServer: () -> Unit,
    onPrepareKoreanVoice: () -> Unit,
    onStartKoreanVoice: () -> Unit,
    onStopKoreanVoice: () -> Unit,
    onTestKoreanVoice: () -> Unit,
) {
    val state by AgentRepository.state.collectAsState()
    val modelState by UiTreeModelRepository.state.collectAsState()
    val mcpState by McpServerRepository.state.collectAsState()
    val voiceState by MoonshineKoreanRepository.state.collectAsState()
    var command by remember { mutableStateOf("와이파이 메뉴 열어") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mobile GUI Agent · PoC") })
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            McpServerCard(
                state = mcpState,
                onStart = onStartMcpServer,
                onStop = onStopMcpServer,
            )

            ServiceStatusCard(state)

            MoonshineKoreanCard(
                state = voiceState,
                onPrepare = onPrepareKoreanVoice,
                onStart = onStartKoreanVoice,
                onStop = onStopKoreanVoice,
                onTest = onTestKoreanVoice,
            )

            Button(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("접근성 설정 열기")
            }

            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("규칙 기반 명령") },
                supportingText = {
                    Text("예: 설정 열어, 와이파이 메뉴 열어, 눌러 Bluetooth")
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { onCommand(command) },
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.running) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(18.dp)
                            .padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(if (state.running) "실행 중…" else "명령 실행")
            }

            Text("빠른 테스트", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onAction(AgentAction.OpenWifi) },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("설정 → Wi-Fi")
                }
                OutlinedButton(
                    onClick = { onAction(AgentAction.DumpTree) },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("UI 트리 출력")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onAction(AgentAction.ScrollDown) },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("스크롤")
                }
                OutlinedButton(
                    onClick = { onAction(AgentAction.Back) },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("뒤로")
                }
                OutlinedButton(
                    onClick = { onAction(AgentAction.Home) },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("홈")
                }
            }

            HorizontalDivider()
            UiTreeModelCard(
                state = modelState,
                onGoalChange = onModelGoalChange,
                onRunTest = onRunUiTreeModelTest,
            )

            HorizontalDivider()
            Text("실행 로그", style = MaterialTheme.typography.titleMedium)
            LogCard(state)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MoonshineKoreanCard(
    state: MoonshineKoreanState,
    onPrepare: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Moonshine Korean · 온디바이스 STT", style = MaterialTheme.typography.titleMedium)
            Text(state.status, style = MaterialTheme.typography.bodySmall)
            state.progress?.let { progress ->
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.downloading || state.loading) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("한국어 모델 로딩 중…")
                }
            } else if (!state.modelReady) {
                Button(
                    onClick = onPrepare,
                    enabled = !state.downloading && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.downloading || state.loading) "준비 중…" else "한국어 모델 다운로드")
                }
            } else if (state.listening) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("음성인식 중지")
                }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text("한국어 음성인식 시작")
                }
                OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
                    Text("내장 한국어 샘플 테스트")
                }
            }
            if (state.currentText.isNotBlank()) {
                Text("인식 중: ${state.currentText}")
            }
            if (state.completedText.isNotBlank()) {
                SelectionContainer {
                    Text(
                        state.completedText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            state.testLatencyMs?.let { latency ->
                Text("로컬 추론 시간: ${latency}ms", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { error ->
                Text(
                    "오류: $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun McpServerCard(
    state: McpServerState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val containerColor = if (state.running) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (state.running) "PocketMCP 서버 실행 중" else "PocketMCP 서버 중지됨",
                style = MaterialTheme.typography.titleMedium,
            )
            state.endpoints.forEach { endpoint ->
                SelectionContainer {
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (state.pairingToken.isNotBlank()) {
                SelectionContainer {
                    Text(
                        "페어링 토큰: ${state.pairingToken}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (state.requestCount > 0) {
                Text(
                    "요청 ${state.requestCount}회 · 마지막: ${state.lastMethod}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.error?.let { message ->
                Text(
                    "오류: $message",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.running) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("MCP 서버 중지")
                }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text("MCP 서버 시작")
                }
            }
        }
    }
}

@Composable
private fun UiTreeModelCard(
    state: UiTreeModelState,
    onGoalChange: (String) -> Unit,
    onRunTest: () -> Unit,
) {
    Text("MiniCPM-V 4.6 · UI-tree 이해", style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(state.status)
            Text(
                if (state.modelFilePresent) {
                    "모델: Q4_K_M 파일 확인됨"
                } else {
                    "모델: ${UiTreeModelRepository.MODEL_FILE_NAME} 필요"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = state.goal,
                onValueChange = onGoalChange,
                label = { Text("모델에게 줄 목표") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Button(
                onClick = onRunTest,
                enabled = state.modelFilePresent && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(18.dp)
                            .padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    if (state.busy) {
                        "모델 분석·자동 클릭 중…"
                    } else {
                        "설정 UI-tree 분석 후 자동 클릭"
                    },
                )
            }
            if (state.capturedNodeCount > 0) {
                Text(
                    "입력: ${state.capturedPackage} · ${state.capturedNodeCount} nodes",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.selectedNodeId?.let { nodeId ->
                val verdict = when (state.expectedSelection) {
                    true -> "예상 경로와 일치"
                    false -> "예상 경로와 불일치"
                    null -> "판정 불가"
                }
                Text(
                    "선택: $nodeId · ${state.selectedNodeLabel.orEmpty()} · $verdict",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            state.latencyMs?.let { latency ->
                Text("전체 지연시간: ${latency}ms", style = MaterialTheme.typography.bodySmall)
            }
            if (state.clickAttempted) {
                val clickText = when (state.clickSucceeded) {
                    true -> "성공"
                    false -> "실패"
                    null -> "진행 중"
                }
                val changedText = when (state.screenChanged) {
                    true -> "감지"
                    false -> "미감지"
                    null -> "확인 중"
                }
                val verifiedText = when (state.resultVerified) {
                    true -> "성공"
                    false -> "실패"
                    null -> "확인 중"
                }
                Text(
                    "자동 클릭: $clickText" +
                        if (state.usedClickableAncestor) " · 클릭 가능한 부모 사용" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "화면 변화: $changedText · 결과 검증: $verifiedText" +
                        state.actionLatencyMs?.let { " · ${it}ms" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.rawResponse.isNotBlank()) {
                SelectionContainer {
                    Text(
                        state.rawResponse,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceStatusCard(state: AgentUiState) {
    val containerColor = if (state.serviceConnected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (state.serviceConnected) "서비스 연결됨" else "서비스 연결 필요",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(state.status)
            if (state.foregroundPackage.isNotBlank()) {
                Text(
                    "현재 패키지: ${state.foregroundPackage}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.lastNodeCount > 0) {
                Text(
                    "마지막 트리: ${state.lastNodeCount} nodes · ${state.lastFingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LogCard(state: AgentUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text = if (state.logs.isEmpty()) {
                    "아직 실행 기록이 없습니다."
                } else {
                    state.logs.joinToString("\n")
                },
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (state.metricsFilePath.isNotBlank()) {
            Text(
                text = "측정값: ${state.metricsFilePath}",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
