package com.example.mobileguiagent.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 개인정보를 기기 안에만 두는 금고.
 *
 * 설계의 핵심은 "값이 폰을 벗어나지 않는다"이다. 에이전트는 "node_14를 password
 * 필드로 채워라"라고만 말하고, 실제 값은 여기서 꺼내 접근성 서비스가 바로
 * 입력한다. 값이 adb를 건너지도, 맥의 파이썬 프로세스에 들어오지도, 로그에
 * 남지도 않는다. 라우팅에 버그가 나도 폰 밖에는 유출될 값 자체가 없다.
 *
 * 암호화는 Android Keystore를 직접 쓴다. androidx.security의
 * EncryptedSharedPreferences가 편하지만 의존성을 새로 받아야 하고, 이 저장소를
 * 빌드하는 환경이 Gradle 네트워크에 제약이 있다. Keystore는 플랫폼 API라
 * 추가로 받을 게 없고, 키가 하드웨어에 남아 앱을 뜯어도 꺼낼 수 없다는 점은
 * 오히려 더 낫다.
 *
 * 쓰기는 MCP로 열지 않는다. 앱 화면에서만 값을 넣을 수 있다. 포트 8765에
 * 닿을 수 있는 무언가가 값을 덮어쓰거나 넣어보며 떠보는 걸 막기 위해서다.
 */
object SecretVault {
    /**
     * 다룰 수 있는 필드. 고정 목록으로 두어 모델이 임의의 키를 지어내지 못하게 한다.
     * 설명은 화면의 라벨과 필드를 잇는 다리다("아이디"가 username인 걸 알아야 한다).
     */
    val FIELDS: Map<String, String> = linkedMapOf(
        "username" to "아이디, 로그인 ID, 사용자 이름",
        "password" to "비밀번호, 패스워드",
        "email" to "이메일 주소",
        "phone" to "휴대폰 번호",
        "name" to "이름, 성명",
        "birthday" to "생년월일",
        "address" to "주소",
        "postcode" to "우편번호",
    )

    /** 값이 실제로 들어있는 필드 이름만. 값은 절대 돌려주지 않는다. */
    fun storedFields(context: Context): List<String> =
        FIELDS.keys.filter { field -> prefs(context).contains(field) }

    fun has(context: Context, field: String): Boolean =
        FIELDS.containsKey(field) && prefs(context).contains(field)

    /** 앱 화면에서만 부른다. MCP로는 열지 않는다. */
    fun put(context: Context, field: String, value: String): Boolean {
        if (!FIELDS.containsKey(field)) return false
        return runCatching {
            prefs(context).edit().putString(field, encrypt(value)).apply()
            true
        }.getOrElse { error ->
            Log.e(TAG, "Unable to store $field", error)
            false
        }
    }

    fun remove(context: Context, field: String) {
        prefs(context).edit().remove(field).apply()
    }

    /**
     * 값을 꺼낸다. 부르는 쪽은 이걸 그대로 화면에 넣기만 해야 하고, MCP 응답이나
     * 로그에 실으면 안 된다. 그러라고 만든 금고가 아니다.
     */
    fun reveal(context: Context, field: String): String? {
        val stored = prefs(context).getString(field, null) ?: return null
        return runCatching { decrypt(stored) }.getOrElse { error ->
            Log.e(TAG, "Unable to read $field", error)
            null
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray())
        // IV는 매번 달라지므로 암호문 앞에 붙여 함께 저장한다.
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES),
        )
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    /** 키는 Keystore 안에서 만들어지고 밖으로 나오지 않는다. 없으면 처음 한 번 만든다. */
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { entry ->
            return entry.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "tupac_secret_vault"
    private const val PREFS = "tupac_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG = "SecretVault"
}
