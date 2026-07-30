package com.example.mobileguiagent.cloud

enum class GeminiModel(
    val apiId: String,
    val displayName: String,
) {
    FLASH_LITE_3_1(
        apiId = "gemini-3.1-flash-lite",
        displayName = "Gemini 3.1 Flash-Lite",
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
