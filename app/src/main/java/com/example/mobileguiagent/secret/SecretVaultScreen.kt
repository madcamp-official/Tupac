package com.example.mobileguiagent.secret

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp

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
    var stored by remember { mutableStateOf(SecretVault.storedFields(context)) }

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
            items(SecretVault.FIELDS.keys.toList()) { field ->
                val hint = SecretVault.FIELDS[field].orEmpty()
                val isStored = field in stored
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("$field — $hint", style = MaterialTheme.typography.labelLarge)
                            if (isStored) {
                                TextButton(onClick = {
                                    SecretVault.remove(context, field)
                                    stored = SecretVault.storedFields(context)
                                }) { Text("삭제") }
                            }
                        }
                        Text(
                            if (isStored) "등록됨" else "미등록",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = drafts[field].orEmpty(),
                                onValueChange = { drafts[field] = it },
                                label = { Text(if (isStored) "새 값으로 바꾸기" else "값 입력") },
                                singleLine = true,
                                // 비밀번호는 화면에도 가린다. 어깨너머로 보이는 것도
                                // 접근성 트리에 올라가는 것도 막을 이유가 같다.
                                visualTransformation = if (field == "password") {
                                    PasswordVisualTransformation()
                                } else {
                                    androidx.compose.ui.text.input.VisualTransformation.None
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = !drafts[field].isNullOrBlank(),
                                onClick = {
                                    SecretVault.put(context, field, drafts[field].orEmpty())
                                    drafts[field] = ""
                                    stored = SecretVault.storedFields(context)
                                },
                            ) { Text("저장") }
                        }
                    }
                }
            }
        }
    }
}
