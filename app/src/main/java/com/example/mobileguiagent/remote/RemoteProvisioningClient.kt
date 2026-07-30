package com.example.mobileguiagent.remote

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.example.mobileguiagent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RemoteProvisioningSettings(
    val supabaseUrl: String,
    val publishableKey: String,
    val gatewayUrl: String,
) {
    val configured: Boolean
        get() = supabaseUrl.isNotBlank() &&
            publishableKey.isNotBlank() &&
            gatewayUrl.isNotBlank()

    companion object {
        fun fromBuildConfig(): RemoteProvisioningSettings = RemoteProvisioningSettings(
            supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            gatewayUrl = BuildConfig.MCP_GATEWAY_URL.trimEnd('/'),
        )
    }
}

class RemoteProvisioningClient(
    context: Context,
    private val settings: RemoteProvisioningSettings =
        RemoteProvisioningSettings.fromBuildConfig(),
) {
    private val appContext = context.applicationContext
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun requestEmailCode(email: String) = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject()
            .put("email", email.trim())
            .put("create_user", true)
        executeJson(
            Request.Builder()
                .url("${settings.supabaseUrl}/auth/v1/otp")
                .header("apikey", settings.publishableKey)
                .post(body.toString().toRequestBody(jsonMediaType))
                .build(),
        )
    }

    suspend fun verifyAndRegister(email: String, code: String) =
        withContext(Dispatchers.IO) {
            requireConfigured()
            val verification = executeJson(
                Request.Builder()
                    .url("${settings.supabaseUrl}/auth/v1/verify")
                    .header("apikey", settings.publishableKey)
                    .post(
                        JSONObject()
                            .put("type", "email")
                            .put("email", email.trim())
                            .put("token", code.trim())
                            .toString()
                            .toRequestBody(jsonMediaType),
                    )
                    .build(),
            )
            val accessToken = verification.requiredString("access_token").toCharArray()
            val refreshToken = verification.requiredString("refresh_token").toCharArray()
            try {
                val registration = registerDevice(String(accessToken))
                val device = registration.getJSONObject("device")
                val credentials = registration.getJSONObject("credentials")
                val deviceToken = credentials.requiredString("deviceToken").toCharArray()
                try {
                    RemoteDeviceConfigStore.save(
                        context = appContext,
                        webSocketUrl = credentials.requiredString("webSocketUrl"),
                        deviceId = device.requiredString("id"),
                        deviceToken = deviceToken,
                    )
                    RemoteAuthSessionStore.save(
                        context = appContext,
                        email = email.trim(),
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                    )
                } finally {
                    deviceToken.fill('\u0000')
                }
            } finally {
                accessToken.fill('\u0000')
                refreshToken.fill('\u0000')
            }
        }

    private fun registerDevice(accessToken: String): JSONObject {
        val registrationBody = JSONObject()
            .put("installationId", installationId())
            .put("displayName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("appVersion", BuildConfig.VERSION_NAME)
        return executeJson(
            Request.Builder()
                .url("${settings.gatewayUrl}/api/v1/devices/register")
                .header("Authorization", "Bearer $accessToken")
                .post(registrationBody.toString().toRequestBody(jsonMediaType))
                .build(),
        )
    }

    private fun installationId(): String {
        val preferences = appContext.getSharedPreferences(
            INSTALLATION_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        preferences.getString(INSTALLATION_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { generated ->
            preferences.edit { putString(INSTALLATION_ID, generated) }
        }
    }

    private fun executeJson(request: Request): JSONObject {
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                val serverError = runCatching {
                    JSONObject(responseBody).optString("error")
                }.getOrNull()
                throw IOException(
                    serverError?.takeIf { it.isNotBlank() }
                        ?: "서버 요청에 실패했습니다. (${response.code})",
                )
            }
            return if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        }
    }

    private fun requireConfigured() {
        check(settings.configured) {
            "SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, MCP_GATEWAY_URL 설정이 필요합니다."
        }
    }

    private fun JSONObject.requiredString(name: String): String =
        optString(name).takeIf { it.isNotBlank() }
            ?: throw IOException("서버 응답에 $name 값이 없습니다.")

    private companion object {
        const val INSTALLATION_PREFERENCES = "remote_installation_v1"
        const val INSTALLATION_ID = "installation_id"
    }
}
