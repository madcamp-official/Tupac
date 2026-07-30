package com.example.mobileguiagent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mobileguiagent.MainActivity
import com.example.mobileguiagent.R
import com.example.mobileguiagent.model.LocalChatRepository

/**
 * Keeps an active cross-app agent run out of Android's cached/background
 * process freezer while the controlled application owns the foreground.
 *
 * The actual agent state remains in [LocalChatRepository]; this service owns
 * only process lifetime and the required user-visible foreground notification.
 */
class AgentRunForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            notification("에이전트 실행을 준비하는 중…"),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LocalChatRepository.stopGeneration()
            stopSelf()
            return START_NOT_STICKY
        }
        val goal = intent?.getStringExtra(EXTRA_GOAL)?.trim().orEmpty()
        if (goal.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        notificationManager().notify(
            NOTIFICATION_ID,
            notification("다른 앱의 화면 작업을 실행 중…"),
        )
        val started = LocalChatRepository.runFromForegroundService(
            context = applicationContext,
            input = goal,
            onFinished = ::stopSelf,
        )
        if (!started && !LocalChatRepository.isGenerating()) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (LocalChatRepository.isGenerating()) {
            LocalChatRepository.stopGeneration()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "에이전트 화면 작업",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "다른 앱을 조작하는 에이전트가 실행 중임을 표시합니다."
            },
        )
    }

    private fun notification(status: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentRunForegroundService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MobileGUIAgent 실행 중")
            .setContentText(status)
            .setContentIntent(openAppIntent)
            .addAction(0, "중단", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        private const val ACTION_RUN =
            "com.example.mobileguiagent.action.RUN_AGENT_FOREGROUND"
        private const val ACTION_STOP =
            "com.example.mobileguiagent.action.STOP_AGENT_FOREGROUND"
        private const val EXTRA_GOAL = "goal"
        private const val CHANNEL_ID = "agent_run"
        private const val NOTIFICATION_ID = 8891

        fun start(context: Context, goal: String) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, AgentRunForegroundService::class.java)
                    .setAction(ACTION_RUN)
                    .putExtra(EXTRA_GOAL, goal),
            )
        }
    }
}
