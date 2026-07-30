package com.example.mobileguiagent.remote

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RemoteDeviceState(
    val configured: Boolean = false,
    val running: Boolean = false,
    val connected: Boolean = false,
    val deviceId: String = "",
    val endpoint: String = "",
    val commandCount: Long = 0,
    val lastTool: String = "",
    val status: String = "설정되지 않음",
    val error: String? = null,
)

object RemoteDeviceRepository {
    private val mutableState = MutableStateFlow(RemoteDeviceState())
    val state: StateFlow<RemoteDeviceState> = mutableState.asStateFlow()

    fun refresh(context: Context) {
        val config = RemoteDeviceConfigStore.load(context)
        try {
            mutableState.update {
                it.copy(
                    configured = config != null,
                    deviceId = config?.deviceId.orEmpty(),
                    endpoint = config?.webSocketUrl.orEmpty(),
                    status = when {
                        it.connected -> "클라우드 MCP에 연결됨"
                        it.running -> "클라우드 MCP에 연결하는 중"
                        config != null -> "연결 준비됨"
                        else -> "설정되지 않음"
                    },
                )
            }
        } finally {
            config?.clearSecret()
        }
    }

    fun start(context: Context) {
        refresh(context)
        if (!mutableState.value.configured) {
            onError("클라우드 MCP 연결 정보를 먼저 저장하세요.")
            return
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, RemoteDeviceService::class.java),
        )
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, RemoteDeviceService::class.java))
    }

    internal fun onStarting(config: RemoteDeviceConfig) {
        mutableState.update {
            it.copy(
                configured = true,
                running = true,
                connected = false,
                deviceId = config.deviceId,
                endpoint = config.webSocketUrl,
                status = "클라우드 MCP에 연결하는 중",
                error = null,
            )
        }
    }

    internal fun onConnected() {
        mutableState.update {
            it.copy(
                running = true,
                connected = true,
                status = "클라우드 MCP에 연결됨",
                error = null,
            )
        }
    }

    internal fun onDisconnected(message: String) {
        mutableState.update {
            it.copy(
                connected = false,
                status = if (it.running) "재연결 대기 중" else "연결 중지됨",
                error = message,
            )
        }
    }

    internal fun onCommand(toolName: String) {
        mutableState.update {
            it.copy(
                commandCount = it.commandCount + 1,
                lastTool = toolName,
            )
        }
    }

    internal fun onStopped() {
        mutableState.update {
            it.copy(
                running = false,
                connected = false,
                status = if (it.configured) "연결 중지됨" else "설정되지 않음",
            )
        }
    }

    internal fun onError(message: String) {
        mutableState.update { it.copy(error = message) }
    }
}
