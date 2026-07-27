package com.example.mobileguiagent.secret

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.mobileguiagent.device.LaunchAppDeviceTool

/**
 * 개인정보를 등록하는 화면.
 *
 * 값을 넣는 통로를 여기 하나로 둔다. MCP로 쓰기를 열면 포트 8765에 닿는 무언가가
 * 값을 덮어쓰거나 넣어보며 떠볼 수 있어서, 에이전트에게는 읽어서 화면에 넣는
 * 길만 준다(fill_field).
 *
 * 등록된 값은 다시 보여주지 않는다. "등록됨"까지만 표시한다. 화면에 띄우는 순간
 * 접근성 트리에 올라가고, 그건 이 에이전트가 읽는 바로 그 통로다.
 */
@Composable
fun SecretVaultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val drafts = remember { mutableStateMapOf<String, String>() }
    var storedProfile by remember { mutableStateOf(SecretVault.storedProfileFields(context)) }
    var services by remember { mutableStateOf(SecretVault.storedServices(context)) }

    // 사용자는 "카카오톡"이라고 쓰지 com.kakao.talk을 외우지 않는다. 앱 목록에서
    // 이름으로 찾아 패키지를 대신 채운다(launch_app이 쓰는 것과 같은 목록).
    var appQuery by remember { mutableStateOf("") }
    val resolved = remember(appQuery) {
        if (appQuery.isBlank()) {
            null
        } else {
            LaunchAppDeviceTool.launchableApps(context.packageManager)
                .firstOrNull { (label, _) ->
                    label.replace(" ", "").contains(appQuery.replace(" ", ""), ignoreCase = true)
                }
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("내 정보", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("닫기") }
        }
        Text(
            "여기 등록한 값은 이 폰을 벗어나지 않습니다. 에이전트는 값을 볼 수 없고, " +
                "\"이 칸을 비밀번호로 채워라\"라고만 지시할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("공통 정보", style = MaterialTheme.typography.titleMedium)
                Text(
                    "앱과 무관하게 하나만 있으면 되는 값입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(SecretVault.PROFILE_FIELDS.entries.toList().size) { index ->
                val (field, hint) = SecretVault.PROFILE_FIELDS.entries.toList()[index]
                FieldCard(
                    title = "$field — $hint",
                    stored = field in storedProfile,
                    draft = drafts["profile:$field"].orEmpty(),
                    masked = false,
                    onDraft = { drafts["profile:$field"] = it },
                    onSave = {
                        SecretVault.putProfile(context, field, drafts["profile:$field"].orEmpty())
                        drafts["profile:$field"] = ""
                        storedProfile = SecretVault.storedProfileFields(context)
                    },
                    onDelete = {
                        SecretVault.removeProfile(context, field)
                        storedProfile = SecretVault.storedProfileFields(context)
                    },
                )
            }

            item {
                Text(
                    "앱 계정",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    "아이디·비밀번호는 앱마다 다릅니다. 등록한 계정은 그 앱 화면에서만 쓰입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    label = { Text("앱 이름 (예: 카카오톡)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    resolved?.let { (label, packageName) -> "→ $label ($packageName)" }
                        ?: if (appQuery.isBlank()) "" else "그런 이름의 앱이 없습니다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (resolved != null) {
                items(SecretVault.ACCOUNT_FIELDS.entries.toList().size) { index ->
                    val (field, hint) = SecretVault.ACCOUNT_FIELDS.entries.toList()[index]
                    val packageName = resolved.second
                    val key = "account:$packageName:$field"
                    FieldCard(
                        title = "$field — $hint",
                        stored = field in SecretVault.storedAccountFields(context, packageName),
                        draft = drafts[key].orEmpty(),
                        masked = field == "password",
                        onDraft = { drafts[key] = it },
                        onSave = {
                            SecretVault.putAccount(context, packageName, field, drafts[key].orEmpty())
                            drafts[key] = ""
                            services = SecretVault.storedServices(context)
                        },
                        onDelete = null,
                    )
                }
            }

            if (services.isNotEmpty()) {
                item {
                    Text(
                        "등록된 계정",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                items(services.size) { index ->
                    val packageName = services[index]
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(packageName, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    SecretVault.storedAccountFields(context, packageName)
                                        .joinToString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                SecretVault.removeService(context, packageName)
                                services = SecretVault.storedServices(context)
                            }) { Text("삭제") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldCard(
    title: String,
    stored: Boolean,
    draft: String,
    masked: Boolean,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                if (stored && onDelete != null) {
                    TextButton(onClick = onDelete) { Text("삭제") }
                }
            }
            Text(
                if (stored) "등록됨" else "미등록",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    label = { Text(if (stored) "새 값으로 바꾸기" else "값 입력") },
                    singleLine = true,
                    // 비밀번호는 화면에도 가린다. 어깨너머로 보이는 것도 접근성
                    // 트리에 올라가는 것도 막을 이유가 같다.
                    visualTransformation = if (masked) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = draft.isNotBlank(), onClick = onSave) { Text("저장") }
            }
        }
    }
}
