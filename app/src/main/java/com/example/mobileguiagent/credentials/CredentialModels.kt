package com.example.mobileguiagent.credentials

enum class CredentialFieldRole {
    USERNAME,
    PASSWORD,
    GENERIC_SECRET,
}

/**
 * Metadata that may be shown to a planner. It deliberately omits the secret
 * value and the real Android package name.
 */
data class PublicCredentialDescriptor(
    val id: String,
    val scopeAlias: String,
    val role: CredentialFieldRole,
)

/**
 * Complete local policy record. This object never leaves the Android process.
 */
data class LocalCredentialRecord(
    val descriptor: PublicCredentialDescriptor,
    val allowedPackage: String,
)

sealed interface SecretAccessResult {
    data class Ready(val characters: CharArray) : SecretAccessResult

    data class Denied(
        val code: String,
        val message: String,
    ) : SecretAccessResult
}
