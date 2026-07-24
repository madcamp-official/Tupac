package com.example.mobileguiagent.mcp

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

object McpAuthTokenStore {
    private const val PREFERENCES_NAME = "pocket_mcp_auth"
    private const val TOKEN_KEY = "bearer_token"
    private const val TOKEN_BYTES = 32

    fun getOrCreate(context: Context): String {
        val preferences = context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        preferences.getString(TOKEN_KEY, null)
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        preferences.edit().putString(TOKEN_KEY, token).apply()
        return token
    }
}
