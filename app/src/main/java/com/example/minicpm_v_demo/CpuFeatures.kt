package com.example.minicpm_v_demo

import android.util.Log
import java.io.File

/**
 * Selects the same optimised ggml CPU backend as the official MiniCPM-V
 * Android demo. The bundled baseline backend remains the fallback.
 */
internal object CpuFeatures {
    private const val TAG = "MiniCpmCpuFeatures"

    private val features: Set<String> by lazy {
        runCatching {
            File("/proc/cpuinfo")
                .readLines()
                .firstOrNull { it.startsWith("Features") }
                ?.substringAfter(':')
                ?.trim()
                ?.split("\\s+".toRegex())
                ?.toSet()
                .orEmpty()
        }.getOrElse { error ->
            Log.w(TAG, "Unable to read CPU features", error)
            emptySet()
        }
    }

    fun bestGgmlCpuVariant(): String? =
        if ("i8mm" in features && "bf16" in features) "v86" else null
}
