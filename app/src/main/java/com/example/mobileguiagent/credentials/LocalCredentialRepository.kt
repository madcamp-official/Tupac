package com.example.mobileguiagent.credentials

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local resolver and one-time authorization broker.
 *
 * Gemini/local LLMs handle only opaque IDs. This class alone resolves an ID to
 * an encrypted value, and only after package, field-role and user-approval
 * checks have succeeded.
 */
object LocalCredentialRepository {
    private const val METADATA_PREFERENCES = "credential_metadata_v1"
    private const val RECORDS_KEY = "records"
    private const val APPROVAL_TTL_MS = 5 * 60 * 1_000L

    private val approvals = ConcurrentHashMap<String, Approval>()

    fun list(context: Context): List<LocalCredentialRecord> =
        readRecords(context.applicationContext)

    fun publicCatalog(context: Context): List<PublicCredentialDescriptor> =
        list(context).map(LocalCredentialRecord::descriptor)

    fun save(
        context: Context,
        scopeAlias: String,
        allowedPackage: String,
        role: CredentialFieldRole,
        secret: CharArray,
    ): LocalCredentialRecord {
        require(scopeAlias.isNotBlank()) { "안전한 서비스 별칭이 필요합니다." }
        require(PACKAGE_PATTERN.matches(allowedPackage.trim())) {
            "허용할 Android 패키지 이름이 올바르지 않습니다."
        }
        require(secret.isNotEmpty()) { "저장할 값이 비어 있습니다." }
        val appContext = context.applicationContext
        val record = LocalCredentialRecord(
            descriptor = PublicCredentialDescriptor(
                id = "R_${UUID.randomUUID().toString().replace("-", "").take(16).uppercase()}",
                scopeAlias = scopeAlias.trim().take(MAX_ALIAS_LENGTH),
                role = role,
            ),
            allowedPackage = allowedPackage.trim(),
        )
        AndroidKeystoreSecretStore(appContext).put(record.descriptor.id, secret)
        val records = readRecords(appContext) + record
        writeRecords(appContext, records)
        return record
    }

    fun delete(context: Context, id: String) {
        val appContext = context.applicationContext
        writeRecords(
            appContext,
            readRecords(appContext).filterNot { it.descriptor.id == id },
        )
        approvals.remove(id)
        AndroidKeystoreSecretStore(appContext).remove(id)
    }

    /**
     * Explicit UI approval. It is package-bound, expires quickly, and is
     * consumed by the first fill attempt whether that attempt succeeds or not.
     */
    fun grantOneTimeUse(context: Context, id: String): Boolean {
        val record = readRecords(context.applicationContext)
            .firstOrNull { it.descriptor.id == id }
            ?: return false
        approvals[id] = Approval(
            allowedPackage = record.allowedPackage,
            expiresAtElapsedMs = SystemClock.elapsedRealtime() + APPROVAL_TTL_MS,
        )
        return true
    }

    fun accessSecret(
        context: Context,
        id: String,
        foregroundPackage: String,
        targetIsPassword: Boolean,
    ): SecretAccessResult {
        val appContext = context.applicationContext
        val record = readRecords(appContext)
            .firstOrNull { it.descriptor.id == id }
            ?: return SecretAccessResult.Denied(
                "UNKNOWN_SECRET_REF",
                "등록되지 않은 credential reference입니다.",
            )
        if (record.allowedPackage != foregroundPackage) {
            return SecretAccessResult.Denied(
                "SECRET_PACKAGE_DENIED",
                "이 credential reference는 현재 앱에서 사용할 수 없습니다.",
            )
        }
        when (record.descriptor.role) {
            CredentialFieldRole.PASSWORD,
            CredentialFieldRole.GENERIC_SECRET,
            -> if (!targetIsPassword) {
                return SecretAccessResult.Denied(
                    "SECRET_FIELD_MISMATCH",
                    "비밀값은 비밀번호 입력란에만 넣을 수 있습니다.",
                )
            }

            CredentialFieldRole.USERNAME -> if (targetIsPassword) {
                return SecretAccessResult.Denied(
                    "SECRET_FIELD_MISMATCH",
                    "사용자 식별자는 비밀번호 입력란에 넣을 수 없습니다.",
                )
            }
        }
        val approval = approvals.remove(id)
            ?: return SecretAccessResult.Denied(
                "SECRET_APPROVAL_REQUIRED",
                "앱의 보안 저장소에서 이 값의 다음 1회 사용을 먼저 허용하세요.",
            )
        if (
            approval.allowedPackage != foregroundPackage ||
            approval.expiresAtElapsedMs < SystemClock.elapsedRealtime()
        ) {
            return SecretAccessResult.Denied(
                "SECRET_APPROVAL_EXPIRED",
                "1회 사용 승인이 만료되었거나 현재 앱과 일치하지 않습니다.",
            )
        }
        val characters = AndroidKeystoreSecretStore(appContext).get(id)
            ?: return SecretAccessResult.Denied(
                "SECRET_DECRYPT_FAILED",
                "로컬 보안 저장소의 값을 복호화하지 못했습니다.",
            )
        return SecretAccessResult.Ready(characters)
    }

    internal fun clearApprovalsForTest() {
        approvals.clear()
    }

    private fun readRecords(context: Context): List<LocalCredentialRecord> {
        val raw = context
            .getSharedPreferences(METADATA_PREFERENCES, Context.MODE_PRIVATE)
            .getString(RECORDS_KEY, "[]")
            .orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val alias = item.optString("scope_alias").trim()
                val allowedPackage = item.optString("allowed_package").trim()
                val role = runCatching {
                    CredentialFieldRole.valueOf(item.optString("role"))
                }.getOrNull()
                if (
                    id.isNotBlank() &&
                    alias.isNotBlank() &&
                    PACKAGE_PATTERN.matches(allowedPackage) &&
                    role != null
                ) {
                    add(
                        LocalCredentialRecord(
                            PublicCredentialDescriptor(id, alias, role),
                            allowedPackage,
                        ),
                    )
                }
            }
        }
    }

    private fun writeRecords(context: Context, records: List<LocalCredentialRecord>) {
        val array = JSONArray(
            records.map { record ->
                JSONObject()
                    .put("id", record.descriptor.id)
                    .put("scope_alias", record.descriptor.scopeAlias)
                    .put("role", record.descriptor.role.name)
                    .put("allowed_package", record.allowedPackage)
            },
        )
        context
            .getSharedPreferences(METADATA_PREFERENCES, Context.MODE_PRIVATE)
            .edit {
                putString(RECORDS_KEY, array.toString())
            }
    }

    private data class Approval(
        val allowedPackage: String,
        val expiresAtElapsedMs: Long,
    )

    private val PACKAGE_PATTERN =
        Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private const val MAX_ALIAS_LENGTH = 48
}
