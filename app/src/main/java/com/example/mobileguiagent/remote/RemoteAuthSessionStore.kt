package com.example.mobileguiagent.remote

import android.content.Context
import androidx.core.content.edit
import com.example.mobileguiagent.credentials.AndroidKeystoreSecretStore

object RemoteAuthSessionStore {
    private const val PREFERENCES = "remote_auth_session_v1"
    private const val EMAIL = "email"
    private const val ACCESS_TOKEN = "REMOTE_SUPABASE_ACCESS_TOKEN"
    private const val REFRESH_TOKEN = "REMOTE_SUPABASE_REFRESH_TOKEN"

    fun save(
        context: Context,
        email: String,
        accessToken: CharArray,
        refreshToken: CharArray,
    ) {
        val appContext = context.applicationContext
        val secretStore = AndroidKeystoreSecretStore(appContext)
        secretStore.put(ACCESS_TOKEN, accessToken)
        secretStore.put(REFRESH_TOKEN, refreshToken)
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(EMAIL, email)
        }
    }

    fun signedInEmail(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(EMAIL, null)

    fun clear(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            clear()
        }
        AndroidKeystoreSecretStore(appContext).apply {
            remove(ACCESS_TOKEN)
            remove(REFRESH_TOKEN)
        }
    }
}
