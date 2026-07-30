package com.example.mobileguiagent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mobileguiagent.mcp.McpServerRepository
import com.example.mobileguiagent.repository.AgentRepository
import com.example.mobileguiagent.ui.theme.MobileGUIAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 릴레이 주소를 화면 없이 넣을 수 있게 열어둔다. 아직 설정 화면이 없어서인데,
        // 여기 값이 있어야만 앱이 밖으로 접속한다. 기본은 아무 데도 안 붙는 것이다.
        //   adb shell am start --activity-single-top \
        //     -n com.example.mobileguiagent/.MainActivity \
        //     --es relay_url http://127.0.0.1:8790 --es relay_token test
        applyRelaySettings(intent)
        McpServerRepository.start(applicationContext)

        setContent {
            MobileGUIAgentTheme {
                // 개인정보 등록 화면은 별도로 띄운다. 값을 넣는 통로를 앱 안에
                // 하나로 두기 위해서다(MCP로는 쓰기를 열지 않는다).
                var showVault by remember { mutableStateOf(false) }
                if (showVault) {
                    com.example.mobileguiagent.secret.SecretVaultScreen(
                        onBack = { showVault = false },
                    )
                } else {
                    ServerStatusScreen(
                        onOpenVault = { showVault = true },
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
    }
}

/**
 * 앱의 첫 화면. 여기서 사람이 하는 일은 둘뿐이다 — 개인정보를 등록하고, 접근성
 * 서비스를 켜는 것. 나머지는 밖의 모델이 MCP로 부른다.
 *
 * 예전에는 이 자리가 온디바이스 모델과의 채팅 화면이었다. 그 모델을 걷어내면서
 * 화면이 비었는데, 사람이 확인해야 하는 것은 "이 폰이 지금 부를 수 있는 상태인가"라
 * 서버 상태를 보여준다. 붙지 않을 때 어디를 봐야 하는지가 화면에 있어야 한다.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ServerStatusScreen(
    onOpenVault: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val server by McpServerRepository.state.collectAsState()
    val agent by AgentRepository.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PocketMCP")
                        Text(
                            if (server.running) "요청을 받고 있습니다" else "서버가 꺼져 있습니다",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenVault) { Text("내 정보") }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!agent.serviceConnected) {
                AccessibilityNotice(onOpenAccessibilitySettings)
            }

            server.error?.let { error ->
                Text(
                    text = error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            StatusRow("접근성 서비스", if (agent.serviceConnected) "연결됨" else "꺼짐")
            StatusRow("화면에 떠 있는 앱", agent.foregroundPackage.ifBlank { "—" })
            StatusRow("받은 요청", "${server.requestCount}건")
            StatusRow("마지막 요청", server.lastMethod.ifBlank { "—" })
            // 케이블로 붙을 때 쓰는 주소다. 릴레이로 붙을 때는 쓰지 않는다.
            StatusRow("직접 연결 주소", server.endpoints.firstOrNull() ?: "—")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
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
