package com.example.mobileguiagent.mcp

import android.content.Context
import android.content.Intent

/**
 * 릴레이 주소와 토큰. 비어 있으면 릴레이를 아예 쓰지 않는다.
 *
 * 기본값을 두지 않는 것이 중요하다. 주소가 있으면 앱이 밖으로 접속하기 시작하는데,
 * 그건 사용자가 정한 다음에 일어나야 한다. 아무 데도 안 붙는 것이 기본이다.
 *
 * 개발 중에는 화면을 거치지 않고 adb로 넣을 수 있게 해뒀다:
 *
 *   adb shell am start -n com.example.mobileguiagent/.MainActivity \
 *     --es relay_url http://127.0.0.1:8790 --es relay_token test
 */
object RelaySettings {

    private const val PREFERENCES_NAME = "pocket_mcp_auth"
    private const val URL_KEY = "relay_url"
    private const val TOKEN_KEY = "relay_token"

    const val EXTRA_URL = "relay_url"
    const val EXTRA_TOKEN = "relay_token"

    data class Config(val baseUrl: String, val token: String)

    fun read(context: Context): Config? {
        val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(URL_KEY, "").orEmpty().trim().trimEnd('/')
        val token = prefs.getString(TOKEN_KEY, "").orEmpty().trim()
        if (url.isEmpty() || token.isEmpty()) return null
        return Config(url, token)
    }

    fun save(context: Context, baseUrl: String, token: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(URL_KEY, baseUrl.trim().trimEnd('/'))
            .putString(TOKEN_KEY, token.trim())
            .apply()
    }

    /** 실행 인텐트에 설정이 실려 왔으면 저장하고 true. */
    fun applyFrom(context: Context, intent: Intent?): Boolean {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return false
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return false
        save(context, url, token)
        return true
    }
}
