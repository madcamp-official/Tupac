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
 * 설계의 핵심은 "값이 폰을 벗어나지 않는다"이다. 에이전트는 "이 칸을 password로
 * 채워라"라고만 말하고, 실제 값은 여기서 꺼내 접근성 서비스가 바로 입력한다.
 * 값이 adb를 건너지도, 맥의 파이썬 프로세스에 들어오지도, 로그에 남지도 않는다.
 *
 * 암호화는 Android Keystore를 직접 쓴다. androidx.security의
 * EncryptedSharedPreferences가 편하지만 의존성을 새로 받아야 하고, 이 저장소를
 * 빌드하는 환경이 Gradle 네트워크에 제약이 있다. Keystore는 플랫폼 API라
 * 추가로 받을 게 없고, 키가 하드웨어에 남아 앱을 뜯어도 꺼낼 수 없다.
 *
 * 값은 두 종류다:
 *   공통 정보 — 이름·전화번호처럼 서비스와 무관하게 하나면 되는 것
 *   계정 정보 — 아이디·비밀번호처럼 앱마다 다른 것
 *
 * 계정을 앱별로 나눠 두는 이유는 단순한 편의가 아니다. 하나만 두면 카카오톡
 * 비밀번호가 다른 앱 로그인 화면에 들어갈 수 있다. 서비스는 화면의 패키지
 * 이름으로 정하고, 부르는 쪽이 고르지 못하게 한다.
 */
object SecretVault {
    /** 서비스와 무관한 값. 키 -> 화면 라벨과 이어줄 설명. */
    val PROFILE_FIELDS: Map<String, String> = linkedMapOf(
        // 설명은 화면 라벨과 필드를 잇는 다리다. 사람이 실제로 쓰는 말을 쉼표로
        // 나열한다. 스킬은 쉼표 단위로만 끊어 보므로 "휴대폰 번호"처럼 띄어쓴
        // 말도 한 덩어리로 다뤄진다.
        "name" to "이름, 성명, 받는분, 받는사람, 수령인",
        "phone" to "휴대폰 번호, 연락처, 전화번호",
        "email" to "이메일 주소, 메일 주소",
        "birthday" to "생년월일, 생일",
        "address" to "주소, 기본주소, 도로명주소, 자택주소",
        // 상세주소를 따로 두지 않으면 "주소"에 걸려 전체 주소가 들어간다.
        "address_detail" to "상세주소, 나머지 주소, 동호수",
        "postcode" to "우편번호",
    )

    /** 앱마다 따로 두는 값. */
    val ACCOUNT_FIELDS: Map<String, String> = linkedMapOf(
        "username" to "아이디, 로그인 ID, 사용자 이름",
        "password" to "비밀번호, 패스워드",
    )

    val FIELDS: Map<String, String> = PROFILE_FIELDS + ACCOUNT_FIELDS

    fun isAccountField(field: String): Boolean = ACCOUNT_FIELDS.containsKey(field)

    // ─────────────────────────────── 공통 정보 ───────────────────────────────

    fun storedProfileFields(context: Context): List<String> =
        PROFILE_FIELDS.keys.filter { field -> prefs(context).contains(profileKey(field)) }

    fun putProfile(context: Context, field: String, value: String): Boolean {
        if (!PROFILE_FIELDS.containsKey(field)) return false
        return write(context, profileKey(field), value)
    }

    fun removeProfile(context: Context, field: String) {
        prefs(context).edit().remove(profileKey(field)).apply()
    }

    // ─────────────────────────────── 계정 정보 ───────────────────────────────

    /** 계정이 등록된 앱 패키지 목록. */
    fun storedServices(context: Context): List<String> =
        prefs(context).all.keys
            .filter { key -> key.startsWith(ACCOUNT_PREFIX) }
            .mapNotNull { key -> key.removePrefix(ACCOUNT_PREFIX).substringBeforeLast(':', "") }
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()

    fun storedAccountFields(context: Context, service: String): List<String> =
        ACCOUNT_FIELDS.keys.filter { field -> prefs(context).contains(accountKey(service, field)) }

    fun putAccount(context: Context, service: String, field: String, value: String): Boolean {
        if (!ACCOUNT_FIELDS.containsKey(field) || service.isBlank()) return false
        return write(context, accountKey(service, field), value)
    }

    /**
     * 주소창의 호스트가 어느 앱의 계정인지. 확실하지 않으면 null.
     *
     * login.coupang.com → "coupang" → com.coupang.mobile 처럼, 도메인의 알맹이가
     * 등록해둔 패키지 이름 안에 들어 있는지 본다.
     *
     * 후보가 여럿이면 고르지 않는다. 한 사이트의 자격증명을 다른 사이트에 넣는
     * 일은 되돌릴 수 없다. 못 고르면 등록이 안 된 것으로 다루는 편이 낫다.
     */
    fun serviceForHost(context: Context, host: String): String? {
        val labels = host.lowercase().split('.')
            .filter { label -> label.length >= 3 && label !in DOMAIN_NOISE }
        if (labels.isEmpty()) return null
        return storedServices(context)
            .filter { service -> labels.any { label -> service.lowercase().contains(label) } }
            .singleOrNull()
    }

    fun removeService(context: Context, service: String) {
        val editor = prefs(context).edit()
        ACCOUNT_FIELDS.keys.forEach { field -> editor.remove(accountKey(service, field)) }
        editor.apply()
    }

    // ─────────────────────────────── 읽기 ───────────────────────────────

    /**
     * 값을 꺼낸다. 부르는 쪽은 이걸 그대로 화면에 넣기만 해야 하고, MCP 응답이나
     * 로그에 실으면 안 된다. 그러라고 만든 금고가 아니다.
     *
     * @param service 계정 필드일 때 어느 앱 것인지. 공통 정보면 무시한다.
     */
    fun reveal(context: Context, field: String, service: String? = null): String? {
        val key = when {
            isAccountField(field) -> accountKey(service ?: return null, field)
            PROFILE_FIELDS.containsKey(field) -> profileKey(field)
            else -> return null
        }
        val stored = prefs(context).getString(key, null) ?: return null
        return runCatching { decrypt(stored) }.getOrElse { error ->
            Log.e(TAG, "Unable to read $field", error)
            null
        }
    }

    // ─────────────────────────────── 내부 ───────────────────────────────

    private fun profileKey(field: String) = "$PROFILE_PREFIX$field"

    private fun accountKey(service: String, field: String) = "$ACCOUNT_PREFIX$service:$field"

    private fun write(context: Context, key: String, value: String): Boolean =
        runCatching {
            prefs(context).edit().putString(key, encrypt(value)).apply()
            true
        }.getOrElse { error ->
            Log.e(TAG, "Unable to store $key", error)
            false
        }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { store ->
            // 접두사가 없던 시절의 항목은 어느 서비스 것인지 알 수 없어 쓸 수가 없다.
            // 남겨두면 복호화되지 않는 값이 계속 쌓이므로 한 번 지운다.
            val legacy = store.all.keys.filter { key ->
                !key.startsWith(PROFILE_PREFIX) && !key.startsWith(ACCOUNT_PREFIX)
            }
            if (legacy.isNotEmpty()) {
                store.edit().apply { legacy.forEach(::remove) }.apply()
            }
        }

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
    private const val PROFILE_PREFIX = "profile:"
    private const val ACCOUNT_PREFIX = "account:"

    /**
     * 도메인에서 앱을 가리키지 않는 조각. 이걸 걸러야 알맹이만 남는다.
     * login.coupang.com에서 "login"이 남으면 로그인 화면을 가진 아무 앱에나 걸린다.
     */
    private val DOMAIN_NOISE = setOf(
        "www", "www2", "web", "mobile", "app", "api", "new",
        "com", "net", "org", "co", "kr", "io", "gov",
        "login", "signin", "auth", "account", "accounts", "member", "secure", "sso",
    )
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG = "SecretVault"
}
