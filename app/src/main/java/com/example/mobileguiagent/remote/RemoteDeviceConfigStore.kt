package com.example.mobileguiagent.remote

import android.content.Context
import androidx.core.content.edit
import com.example.mobileguiagent.BuildConfig
import com.example.mobileguiagent.credentials.AndroidKeystoreSecretStore

data class RemoteDeviceConfig(
    val webSocketUrl: String,
    val deviceId: String,
    val deviceToken: CharArray,
) {
    fun clearSecret() {
        deviceToken.fill('\u0000')
    }
}

object RemoteDeviceConfigStore {
    private const val PREFERENCES = "remote_device_config_v1"
    private const val URL_KEY = "websocket_url"
    private const val DEVICE_ID_KEY = "device_id"
    private const val SECRET_ID = "REMOTE_DEVICE_API_TOKEN"

    fun load(context: Context): RemoteDeviceConfig? {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val url = preferences.getString(URL_KEY, null)?.trim().orEmpty()
        val deviceId = preferences.getString(DEVICE_ID_KEY, null)?.trim().orEmpty()
        if (url.isBlank() || deviceId.isBlank()) return null
        val token = AndroidKeystoreSecretStore(appContext).get(SECRET_ID)
            ?: return null
        return RemoteDeviceConfig(url, deviceId, token)
    }

    fun save(
        context: Context,
        webSocketUrl: String,
        deviceId: String,
        deviceToken: CharArray,
    ) {
        val normalizedUrl = webSocketUrl.trim()
        require(normalizedUrl.startsWith("wss://") || normalizedUrl.startsWith("ws://")) {
            "WebSocket 주소는 ws:// 또는 wss://로 시작해야 합니다."
        }
        require(BuildConfig.DEBUG || normalizedUrl.startsWith("wss://")) {
            "배포 빌드에서는 암호화된 wss:// 주소만 사용할 수 있습니다."
        }
        require(deviceId.isNotBlank()) { "기기 ID가 필요합니다." }
        require(deviceToken.size >= 32) { "기기 토큰은 32자 이상이어야 합니다." }
        val appContext = context.applicationContext
        AndroidKeystoreSecretStore(appContext).put(SECRET_ID, deviceToken)
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(URL_KEY, normalizedUrl)
            putString(DEVICE_ID_KEY, deviceId.trim())
        }
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit { clear() }
        AndroidKeystoreSecretStore(appContext).remove(SECRET_ID)
    }
}
