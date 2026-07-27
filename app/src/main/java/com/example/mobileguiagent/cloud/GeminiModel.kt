package com.example.mobileguiagent.cloud

enum class GeminiModel(
    val apiId: String,
    val displayName: String,
) {
    LATEST_LITE(
        apiId = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash-Lite",
    ),
    SMART(
        apiId = "gemini-3.6-flash",
        displayName = "Gemini 3.6 Flash",
    ),
    ;

    fun next(): GeminiModel {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}
