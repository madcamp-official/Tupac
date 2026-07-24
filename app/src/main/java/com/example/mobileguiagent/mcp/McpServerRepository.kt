package com.example.mobileguiagent.mcp

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class McpServerState(
    val running: Boolean = false,
    val port: Int = PocketMcpService.DEFAULT_PORT,
    val endpoints: List<String> = emptyList(),
    val pairingToken: String = "",
    val requestCount: Long = 0,
    val lastMethod: String = "",
    val error: String? = null,
)

object McpServerRepository {
    private val mutableState = MutableStateFlow(McpServerState())
    val state: StateFlow<McpServerState> = mutableState.asStateFlow()

    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, PocketMcpService::class.java),
        )
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, PocketMcpService::class.java))
    }

    internal fun onStarted(
        port: Int,
        endpoints: List<String>,
        pairingToken: String,
    ) {
        mutableState.update {
            it.copy(
                running = true,
                port = port,
                endpoints = endpoints,
                pairingToken = pairingToken,
                error = null,
            )
        }
    }

    internal fun onStopped() {
        mutableState.update { it.copy(running = false) }
    }

    internal fun onRequest(method: String) {
        mutableState.update {
            it.copy(
                requestCount = it.requestCount + 1,
                lastMethod = method,
            )
        }
    }

    internal fun onError(message: String) {
        mutableState.update { it.copy(error = message) }
    }
}
