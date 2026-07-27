package com.example.mobileguiagent.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts credential values with a non-exportable Android Keystore AES key.
 *
 * Only ciphertext and IV are stored in SharedPreferences. Plaintext exists
 * briefly in this process only while the approved accessibility action runs.
 */
class AndroidKeystoreSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun put(id: String, secret: CharArray) {
        require(id.isNotBlank())
        require(secret.isNotEmpty())
        val plainBytes = String(secret).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            val ciphertext = cipher.doFinal(plainBytes)
            val payload = ByteBuffer
                .allocate(Int.SIZE_BYTES + cipher.iv.size + ciphertext.size)
                .putInt(cipher.iv.size)
                .put(cipher.iv)
                .put(ciphertext)
                .array()
            preferences.edit {
                putString(id, Base64.encodeToString(payload, Base64.NO_WRAP))
            }
        } finally {
            plainBytes.fill(0)
        }
    }

    fun get(id: String): CharArray? {
        val encoded = preferences.getString(id, null) ?: return null
        val payload = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        return runCatching {
            val buffer = ByteBuffer.wrap(payload)
            val ivLength = buffer.int
            require(ivLength in MIN_IV_BYTES..MAX_IV_BYTES)
            require(buffer.remaining() > ivLength)
            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            val plainBytes = cipher.doFinal(ciphertext)
            try {
                plainBytes.toString(Charsets.UTF_8).toCharArray()
            } finally {
                plainBytes.fill(0)
                ciphertext.fill(0)
                iv.fill(0)
            }
        }.getOrNull().also {
            payload.fill(0)
        }
    }

    fun remove(id: String) {
        preferences.edit { remove(id) }
    }

    internal fun encryptedPayloadForTest(id: String): String? =
        preferences.getString(id, null)

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "credential_ciphertexts_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mobile_gui_agent_credential_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 32
    }
}
