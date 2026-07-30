package com.example.mobileguiagent.credentials

import android.content.Context
import androidx.core.content.edit
import com.example.mobileguiagent.secret.SecretVault
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Local, package-bound credential resolver.
 *
 * Gemini/local LLMs handle only opaque IDs. This class alone resolves an ID to
 * an encrypted value, and only after package and field-role checks succeed.
 * Persisting a credential for an app is the user's authorization to use it for
 * that app's structurally verified authentication form.
 */
object LocalCredentialRepository {
    private const val METADATA_PREFERENCES = "credential_metadata_v1"
    private const val RECORDS_KEY = "records"

    fun list(context: Context): List<LocalCredentialRecord> =
        readRecords(context.applicationContext)

    /**
     * Private planners may discover credentials for the exact foreground
     * package locally even when the cloud shortlist did not select them.
     * Only opaque descriptors are returned; values stay in Android Keystore.
     */
    fun publicCatalogForPackage(
        context: Context,
        packageName: String,
    ): List<PublicCredentialDescriptor> {
        val appContext = context.applicationContext
        val repositoryRecords = readRecords(appContext)
            .filter { record -> record.allowedPackage == packageName }
            .map(LocalCredentialRecord::descriptor)
        val vaultRecords = secretVaultDescriptors(appContext)
            .filter { descriptor ->
                resolveSecretVaultDescriptor(appContext, descriptor.id)
                    ?.packageName == packageName
            }
        return (repositoryRecords + vaultRecords)
            .distinctBy(PublicCredentialDescriptor::id)
    }

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
        AndroidKeystoreSecretStore(appContext).remove(id)
    }

    fun accessSecret(
        context: Context,
        id: String,
        foregroundPackage: String,
        targetIsPassword: Boolean,
    ): SecretAccessResult {
        val appContext = context.applicationContext
        if (id.startsWith(SECRET_VAULT_REFERENCE_PREFIX)) {
            return accessSecretVaultValue(
                context = appContext,
                id = id,
                foregroundPackage = foregroundPackage,
                targetIsPassword = targetIsPassword,
            )
        }
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
        val characters = AndroidKeystoreSecretStore(appContext).get(id)
            ?: return SecretAccessResult.Denied(
                "SECRET_DECRYPT_FAILED",
                "로컬 보안 저장소의 값을 복호화하지 못했습니다.",
            )
        return SecretAccessResult.Ready(characters)
    }

    private fun accessSecretVaultValue(
        context: Context,
        id: String,
        foregroundPackage: String,
        targetIsPassword: Boolean,
    ): SecretAccessResult {
        val resolved = resolveSecretVaultDescriptor(context, id)
            ?: return SecretAccessResult.Denied(
                "UNKNOWN_SECRET_REF",
                "등록되지 않은 credential reference입니다.",
            )
        if (resolved.packageName != foregroundPackage) {
            return SecretAccessResult.Denied(
                "SECRET_PACKAGE_DENIED",
                "이 credential reference는 현재 앱에서 사용할 수 없습니다.",
            )
        }
        val expectedPassword = resolved.field == "password"
        if (expectedPassword != targetIsPassword) {
            return SecretAccessResult.Denied(
                "SECRET_FIELD_MISMATCH",
                "저장된 자격증명의 역할과 현재 입력란이 일치하지 않습니다.",
            )
        }
        val value = SecretVault.reveal(
            context = context,
            field = resolved.field,
            service = resolved.packageName,
        ) ?: return SecretAccessResult.Denied(
            "SECRET_DECRYPT_FAILED",
                "로컬 보안 저장소의 값을 복호화하지 못했습니다.",
            )
        return SecretAccessResult.Ready(value.toCharArray())
    }

    private fun secretVaultDescriptors(
        context: Context,
    ): List<PublicCredentialDescriptor> =
        SecretVault.storedServices(context).flatMap { packageName ->
            val scopeAlias = applicationLabel(context, packageName)
            SecretVault.storedAccountFields(context, packageName).mapNotNull { field ->
                val role = when (field) {
                    "username" -> CredentialFieldRole.USERNAME
                    "password" -> CredentialFieldRole.PASSWORD
                    else -> null
                } ?: return@mapNotNull null
                PublicCredentialDescriptor(
                    id = secretVaultReference(packageName, field),
                    scopeAlias = scopeAlias,
                    role = role,
                )
            }
        }

    private fun resolveSecretVaultDescriptor(
        context: Context,
        id: String,
    ): SecretVaultDescriptor? {
        if (!id.startsWith(SECRET_VAULT_REFERENCE_PREFIX)) return null
        return SecretVault.storedServices(context).firstNotNullOfOrNull { packageName ->
            SecretVault.storedAccountFields(context, packageName)
                .firstOrNull { field -> secretVaultReference(packageName, field) == id }
                ?.let { field -> SecretVaultDescriptor(packageName, field) }
        }
    }

    private fun applicationLabel(context: Context, packageName: String): String =
        runCatching {
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(packageName)

    private fun secretVaultReference(packageName: String, field: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$packageName\u0000$field".toByteArray())
            .take(8)
            .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }
        return "$SECRET_VAULT_REFERENCE_PREFIX$digest"
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

    private data class SecretVaultDescriptor(
        val packageName: String,
        val field: String,
    )

    private val PACKAGE_PATTERN =
        Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private const val MAX_ALIAS_LENGTH = 48
    private const val SECRET_VAULT_REFERENCE_PREFIX = "SV_"
}
