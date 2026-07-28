package com.example.mobileguiagent.mcp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.example.mobileguiagent.MainActivity
import com.example.mobileguiagent.R

class PocketMcpService : Service() {
    private var server: PocketMcpHttpServer? = null
    private var relay: RelayClient? = null
    private lateinit var authToken: String
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshEndpoints()
        }

        override fun onLost(network: Network) {
            refreshEndpoints()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("서버를 시작하는 중…"))
        authToken = McpAuthTokenStore.getOrCreate(this)

        runCatching {
            PocketMcpHttpServer(
                port = DEFAULT_PORT,
                authToken = authToken,
                onRequest = McpServerRepository::onRequest,
            ).also { httpServer ->
                httpServer.start()
                server = httpServer
                registerNetworkCallback()
                refreshEndpoints()
                startRelay(httpServer)
            }
        }.onFailure { error ->
            McpServerRepository.onError(
                error.message ?: error::class.java.simpleName,
            )
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    /**
     * 릴레이는 주소가 설정돼 있을 때만 띄운다. 기본은 안 붙는 것이다 — 앱이 밖으로
     * 접속하는 일은 사용자가 정한 다음에 일어나야 한다.
     */
    private fun startRelay(httpServer: PocketMcpHttpServer) {
        val config = RelaySettings.read(this) ?: return
        relay = RelayClient(
            baseUrl = config.baseUrl,
            token = config.token,
            handle = httpServer::handle,
        ).also { it.start() }
    }

    override fun onDestroy() {
        relay?.stop()
        relay = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
        server?.stop()
        server = null
        McpServerRepository.onStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder().build()
        getSystemService(ConnectivityManager::class.java)
            .registerNetworkCallback(request, networkCallback)
    }

    private fun refreshEndpoints() {
        val httpServer = server ?: return
        val endpoints = httpServer.networkEndpoints()
        McpServerRepository.onStarted(DEFAULT_PORT, endpoints, authToken)
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager().notify(
                NOTIFICATION_ID,
                buildNotification(
                    endpoints.firstOrNull() ?: "포트 ${DEFAULT_PORT}에서 실행 중",
                ),
            )
        }
    }

    private fun createNotificationChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "PocketMCP 서버",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Android MCP 서버가 실행 중임을 표시합니다."
            },
        )
    }

    private fun buildNotification(status: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PocketMCP 실행 중")
            .setContentText(status)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        const val DEFAULT_PORT = 8765
        private const val CHANNEL_ID = "pocket_mcp_server"
        private const val NOTIFICATION_ID = 8765
    }
}
