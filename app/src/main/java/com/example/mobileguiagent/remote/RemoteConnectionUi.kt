package com.example.mobileguiagent.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun RemoteConnectionDialog(
    currentEndpoint: String,
    currentDeviceId: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { RemoteProvisioningSettings.fromBuildConfig() }
    val provisioningClient = remember {
        RemoteProvisioningClient(context, settings)
    }
    val coroutineScope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(currentEndpoint) }
    var deviceId by remember { mutableStateOf(currentDeviceId.ifBlank { "development-phone" }) }
    var token by remember { mutableStateOf("") }
    var email by remember {
        mutableStateOf(RemoteAuthSessionStore.signedInEmail(context).orEmpty())
    }
    var verificationCode by remember { mutableStateOf("") }
    var codeRequested by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("클라우드 MCP 연결") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (settings.configured) {
                    Text(
                        if (codeRequested) {
                            "이메일로 받은 인증 코드를 입력하면 이 휴대폰을 계정에 연결합니다."
                        } else {
                            "Supabase 계정으로 로그인해 이 휴대폰 전용 연결 토큰을 발급합니다."
                        },
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("이메일") },
                        singleLine = true,
                        enabled = !submitting,
                    )
                    if (codeRequested) {
                        OutlinedTextField(
                            value = verificationCode,
                            onValueChange = { verificationCode = it },
                            label = { Text("인증 코드") },
                            singleLine = true,
                            enabled = !submitting,
                        )
                    }
                } else {
                    Text(
                        "개발 설정 모드입니다. 배포 빌드는 Supabase와 Gateway 주소를 " +
                            "local.properties에 설정하세요.",
                    )
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text("WebSocket 주소") },
                        placeholder = { Text("wss://mcp.example.com/device/ws") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = deviceId,
                        onValueChange = { deviceId = it },
                        label = { Text("기기 ID") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("기기 토큰") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
                error?.let {
                    Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    error = null
                    if (settings.configured) {
                        submitting = true
                        coroutineScope.launch {
                            runCatching {
                                if (codeRequested) {
                                    provisioningClient.verifyAndRegister(
                                        email,
                                        verificationCode,
                                    )
                                } else {
                                    provisioningClient.requestEmailCode(email)
                                }
                            }.onSuccess {
                                if (codeRequested) {
                                    onSaved()
                                } else {
                                    codeRequested = true
                                }
                            }.onFailure { failure ->
                                error = failure.message ?: "계정 연결에 실패했습니다."
                            }
                            submitting = false
                        }
                    } else {
                        val characters = token.toCharArray()
                        try {
                            RemoteDeviceConfigStore.save(
                                context = context,
                                webSocketUrl = endpoint,
                                deviceId = deviceId,
                                deviceToken = characters,
                            )
                            onSaved()
                        } catch (failure: IllegalArgumentException) {
                            error = failure.message ?: "연결 정보를 저장하지 못했습니다."
                        } finally {
                            characters.fill('\u0000')
                            token = ""
                        }
                    }
                },
            ) {
                Text(
                    when {
                        submitting -> "처리 중"
                        settings.configured && !codeRequested -> "인증 코드 받기"
                        settings.configured -> "휴대폰 연결"
                        else -> "저장"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
    )
}

@Composable
fun RemoteConnectionNotice(
    state: RemoteDeviceState,
    onToggle: () -> Unit,
    onConfigure: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("클라우드 MCP · ${state.status}")
            if (state.deviceId.isNotBlank()) Text("기기: ${state.deviceId}")
            if (state.lastTool.isNotBlank()) {
                Text("최근 도구: ${state.lastTool} · ${state.commandCount}회")
            }
            state.error?.let {
                Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onToggle,
                    enabled = state.configured,
                ) {
                    Text(if (state.running) "연결 중지" else "연결 시작")
                }
                TextButton(onClick = onConfigure) {
                    Text("설정")
                }
            }
        }
    }
}
