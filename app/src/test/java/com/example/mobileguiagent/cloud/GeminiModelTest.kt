package com.example.mobileguiagent.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiModelTest {
    @Test
    fun defaultLiteModelUsesStableGemini31ApiId() {
        assertEquals(
            "gemini-3.1-flash-lite",
            GeminiModel.FLASH_LITE_3_1.apiId,
        )
        assertEquals(
            "Gemini 3.1 Flash-Lite",
            GeminiModel.FLASH_LITE_3_1.displayName,
        )
    }
}
