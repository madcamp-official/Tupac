package com.example.mobileguiagent.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.mobileguiagent.MainActivity
import com.example.mobileguiagent.R
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RemoteDeviceService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val commandRunning = AtomicBoolean(false)
    private val commandExecutor = RemoteDeviceCommandExecutor()
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var webSocket: WebSocket? = null
    private var config: RemoteDeviceConfig? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("연결 준비 중"))
        val loaded = RemoteDeviceConfigStore.load(this)
        if (loaded == null) {
            RemoteDeviceRepository.onError("클라우드 MCP 연결 정보가 없습니다.")
            stopSelf()
            return
        }
        config = loaded
        RemoteDeviceRepository.onStarting(loaded)
        connect(loaded)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        stopping = true
        reconnectHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "user_stopped")
        webSocket = null
        config?.clearSecret()
        config = null
        executor.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        RemoteDeviceRepository.onStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connect(config: RemoteDeviceConfig) {
        val token = String(config.deviceToken)
        val request = try {
            Request.Builder()
                .url(config.webSocketUrl)
                .header("Authorization", "Bearer $token")
                .header("X-Device-ID", config.deviceId)
                .build()
        } finally {
            // OkHttp necessarily owns a String header for the live request;
            // erase the mutable source as soon as the request is built.
            config.deviceToken.fill('\u0000')
        }
        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                    RemoteDeviceRepository.onConnected()
                    notifyStatus("클라우드 MCP에 연결됨")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val envelope = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (envelope.optString("type") != "command") return
                    val command = envelope.optJSONObject("command") ?: return
                    if (command.optString("deviceId") != config.deviceId) {
                        webSocket.send(
                            errorResult(
                                command.optString("id"),
                                "DEVICE_ID_MISMATCH",
                                "다른 기기를 대상으로 한 명령입니다.",
                            ).toString(),
                        )
                        return
                    }
                    if (!commandRunning.compareAndSet(false, true)) {
                        webSocket.send(
                            errorResult(
                                command.optString("id"),
                                "DEVICE_BUSY",
                                "다른 명령을 실행 중입니다.",
                                retryable = true,
                            ).toString(),
                        )
                        return
                    }
                    executor.execute {
                        try {
                            RemoteDeviceRepository.onCommand(command.optString("toolName"))
                            webSocket.send(commandExecutor.execute(command).toString())
                        } finally {
                            commandRunning.set(false)
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    RemoteDeviceRepository.onDisconnected(
                        reason.ifBlank { "클라우드 연결이 종료되었습니다." },
                    )
                    notifyStatus("클라우드 연결 종료")
                    scheduleReconnect()
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    RemoteDeviceRepository.onDisconnected(
                        t.message ?: "클라우드 연결에 실패했습니다.",
                    )
                    notifyStatus("연결 실패")
                    scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (stopping) return
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed(
            {
                if (stopping) return@postDelayed
                val freshConfig = RemoteDeviceConfigStore.load(this)
                if (freshConfig == null) {
                    RemoteDeviceRepository.onError("저장된 클라우드 연결 정보를 읽을 수 없습니다.")
                    stopSelf()
                    return@postDelayed
                }
                config = freshConfig
                connect(freshConfig)
            },
            reconnectDelayMs,
        )
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun errorResult(
        commandId: String,
        code: String,
        message: String,
        retryable: Boolean = false,
    ): JSONObject = JSONObject()
        .put("type", "result")
        .put("commandId", commandId)
        .put("status", "failed")
        .put(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message)
                .put("retryable", retryable),
        )

    private fun createNotificationChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "클라우드 MCP 연결",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notifyStatus(status: String) {
        notificationManager().notify(NOTIFICATION_ID, notification(status))
    }

    private fun notification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MobileGUIAgent 원격 연결")
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private companion object {
        const val CHANNEL_ID = "remote_device_agent"
        const val NOTIFICATION_ID = 8876
        const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
