package com.example.mobileguiagent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mobileguiagent.MainActivity
import com.example.mobileguiagent.R
import com.example.mobileguiagent.model.ChatRole
import com.example.mobileguiagent.model.LocalChatRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One-shot local voice-command session.
 *
 * Moonshine Base Korean produces the first completed local transcript, which is forwarded
 * to the same LocalChatRepository used by typed chat. The service stays in the
 * foreground until MiniCPM finishes so microphone use remains valid off-screen.
 */
class VoiceCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandAccepted = AtomicBoolean(false)
    private lateinit var textToSpeech: LocalTextToSpeech
    private var returnToWakeWord = false

    override fun onCreate() {
        super.onCreate()
        textToSpeech = LocalTextToSpeech(applicationContext)
        createNotificationChannel()
        scope.launch {
            MoonshineKoreanRepository.finalTranscripts.collect { transcript ->
                if (!commandAccepted.compareAndSet(false, true)) return@collect
                Log.i(TAG, "STT final transcript: ${transcript.take(LOG_TEXT_LIMIT)}")
                MoonshineKoreanRepository.stopListening()
                MoonshineKoreanRepository.markCommandForwarded(transcript)
                VoiceAssistantOverlay.update(
                    VoiceOverlayPhase.PROCESSING,
                    "요청을 처리하고 있어요",
                )
                notificationManager().notify(
                    NOTIFICATION_ID,
                    buildNotification("명령 실행 중: $transcript"),
                )

                val messageCountBeforeCommand =
                    LocalChatRepository.state.value.messages.size
                LocalChatRepository.send(applicationContext, transcript)
                val completedState =
                    LocalChatRepository.state.first { state -> !state.generating }
                val spokenResponse = completedState.messages
                    .drop(messageCountBeforeCommand)
                    .lastOrNull { message -> message.role == ChatRole.ASSISTANT }
                    ?.text
                    ?: completedState.error?.let { "명령 처리에 실패했습니다. $it" }
                    ?: "명령 처리가 완료되었습니다."
                Log.i(TAG, "TTS response text: ${spokenResponse.take(LOG_TEXT_LIMIT)}")

                notificationManager().notify(
                    NOTIFICATION_ID,
                    buildNotification("답변을 읽는 중…"),
                )
                VoiceAssistantOverlay.update(VoiceOverlayPhase.SPEAKING)
                val spoken = textToSpeech.speak(spokenResponse)
                if (!spoken) {
                    VoiceAssistantOverlay.update(
                        VoiceOverlayPhase.ERROR,
                        "답변은 앱에서 확인해 주세요",
                    )
                    notificationManager().notify(
                        NOTIFICATION_ID,
                        buildNotification("음성 출력에 실패했습니다. 앱에서 답변을 확인하세요."),
                    )
                } else {
                    VoiceAssistantOverlay.update(VoiceOverlayPhase.COMPLETE)
                    delay(COMPLETE_FEEDBACK_DURATION_MS)
                }
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        returnToWakeWord = intent?.getBooleanExtra(EXTRA_RETURN_TO_WAKE_WORD, false) == true
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                returnToWakeWord = false
                WakeWordRepository.stopped()
                VoiceAssistantOverlay.hide()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> startVoiceSession()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MoonshineKoreanRepository.stopListening()
        textToSpeech.close()
        scope.cancel()
        super.onDestroy()
        if (returnToWakeWord && WakeWordRepository.state.value.enabled) {
            VoiceAssistantOverlay.hide()
            WakeWordService.resume(applicationContext)
        } else {
            VoiceAssistantOverlay.hide()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVoiceSession() {
        commandAccepted.set(false)
        startForeground(
            NOTIFICATION_ID,
            buildNotification("한국어 음성 모델을 준비하는 중…"),
        )
        VoiceAssistantOverlay.show(this, VoiceOverlayPhase.PREPARING)
        MoonshineKoreanRepository.ensureReady(applicationContext) {
            val started = MoonshineKoreanRepository.startListening()
            if (started) {
                VoiceAssistantOverlay.update(VoiceOverlayPhase.LISTENING)
                notificationManager().notify(
                    NOTIFICATION_ID,
                    buildNotification("듣는 중… 한국어로 명령하세요. · 완전 로컬"),
                )
            } else {
                VoiceAssistantOverlay.update(
                    VoiceOverlayPhase.ERROR,
                    "마이크를 시작하지 못했어요",
                )
                notificationManager().notify(
                    NOTIFICATION_ID,
                    buildNotification("로컬 음성인식을 시작하지 못했습니다."),
                )
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Tupac 음성 도우미",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Hey Tupac 대기와 한국어 음성 명령 상태를 표시합니다."
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
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceCommandService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Local GUI Agent · 음성 명령")
            .setContentText(status)
            .setContentIntent(openAppIntent)
            .addAction(0, "중지", stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        const val ACTION_START = "com.example.mobileguiagent.action.START_VOICE_COMMAND"
        const val ACTION_STOP = "com.example.mobileguiagent.action.STOP_VOICE_COMMAND"
        private const val EXTRA_RETURN_TO_WAKE_WORD = "return_to_wake_word"

        private const val CHANNEL_ID = WakeWordService.CHANNEL_ID
        private const val NOTIFICATION_ID = WakeWordService.NOTIFICATION_ID
        private const val TAG = "VoiceCommandService"
        private const val LOG_TEXT_LIMIT = 500
        private const val COMPLETE_FEEDBACK_DURATION_MS = 900L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceCommandService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VoiceCommandService::class.java).setAction(ACTION_STOP),
            )
        }

        fun startFromWakeWord(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceCommandService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RETURN_TO_WAKE_WORD, true),
            )
        }
    }
}
