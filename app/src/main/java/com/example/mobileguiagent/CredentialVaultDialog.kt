package com.example.mobileguiagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.mobileguiagent.credentials.CredentialFieldRole
import com.example.mobileguiagent.credentials.LocalCredentialRepository

/**
 * Minimal local-only credential manager for the PoC.
 *
 * Values are never rendered after saving. "다음 1회 허용" creates a short-lived,
 * package-bound capability consumed by fill_secret.
 */
@Composable
fun CredentialVaultDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scopeAlias by remember { mutableStateOf("") }
    var allowedPackage by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(CredentialFieldRole.USERNAME) }
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val records = remember(revision) { LocalCredentialRepository.list(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("로컬 보안 저장소") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Gemini와 로컬 모델에는 아래 값이 아니라 불투명 ID와 역할만 " +
                            "전달됩니다. 패키지명과 실제 값은 기기에만 남습니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    OutlinedTextField(
                        value = scopeAlias,
                        onValueChange = { scopeAlias = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("안전한 별칭 (예: shopping_account)") },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = allowedPackage,
                        onValueChange = { allowedPackage = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("허용 Android 패키지") },
                        placeholder = { Text("com.example.app") },
                        singleLine = true,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { role = CredentialFieldRole.USERNAME },
                            enabled = role != CredentialFieldRole.USERNAME,
                        ) {
                            Text("아이디")
                        }
                        OutlinedButton(
                            onClick = { role = CredentialFieldRole.PASSWORD },
                            enabled = role != CredentialFieldRole.PASSWORD,
                        ) {
                            Text("비밀번호")
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                if (role == CredentialFieldRole.USERNAME) {
                                    "저장할 사용자 식별자"
                                } else {
                                    "저장할 비밀번호"
                                },
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
                item {
                    TextButton(
                        onClick = {
                            val characters = secret.toCharArray()
                            try {
                                LocalCredentialRepository.save(
                                    context = context,
                                    scopeAlias = scopeAlias,
                                    allowedPackage = allowedPackage,
                                    role = role,
                                    secret = characters,
                                )
                                secret = ""
                                scopeAlias = ""
                                allowedPackage = ""
                                message = "Android Keystore로 암호화해 저장했습니다."
                                revision += 1
                            } catch (error: Throwable) {
                                message = error.message ?: "저장하지 못했습니다."
                            } finally {
                                characters.fill('\u0000')
                            }
                        },
                        enabled =
                            scopeAlias.isNotBlank() &&
                                allowedPackage.isNotBlank() &&
                                secret.isNotEmpty(),
                    ) {
                        Text("암호화 저장")
                    }
                }
                message?.let { status ->
                    item {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                items(records, key = { it.descriptor.id }) { record ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(record.descriptor.scopeAlias)
                            Text(
                                "${record.descriptor.id} · ${record.descriptor.role.name}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                record.allowedPackage,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        val granted =
                                            LocalCredentialRepository.grantOneTimeUse(
                                                context,
                                                record.descriptor.id,
                                            )
                                        message = if (granted) {
                                            "5분 안의 다음 1회 로컬 입력을 허용했습니다."
                                        } else {
                                            "승인하지 못했습니다."
                                        }
                                    },
                                ) {
                                    Text("다음 1회 허용")
                                }
                                TextButton(
                                    onClick = {
                                        LocalCredentialRepository.delete(
                                            context,
                                            record.descriptor.id,
                                        )
                                        message = "로컬 저장값을 삭제했습니다."
                                        revision += 1
                                    },
                                ) {
                                    Text("삭제")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        },
    )
}
