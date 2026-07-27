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
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.WakeWordModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * OpenWakeWord로 "Hey Tupac"을 온디바이스 감지하는 포그라운드 서비스입니다.
 *
 * 감지되면 OpenWakeWord가 마이크를 놓고 기존 Moonshine 한국어 명령 서비스가
 * 이어받습니다. 명령 처리와 TTS가 끝나면 이 서비스가 다시 시작됩니다.
 * 네트워크, 계정, API 키는 사용하지 않습니다.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val detectionInProgress = AtomicBoolean(false)
    private var engine: WakeWordEngine? = null
    private var handedOffToVoiceCommand = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                WakeWordRepository.stopped()
                VoiceCommandService.stop(this)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESUME -> {
                if (WakeWordRepository.state.value.enabled) startDetector()
            }

            ACTION_COMMAND_NOW -> {
                if (detectionInProgress.compareAndSet(false, true)) {
                    handOffToVoiceCommand()
                }
            }

            ACTION_START -> startDetector()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseDetector()
        scope.cancel()
        if (!handedOffToVoiceCommand && WakeWordRepository.state.value.enabled) {
            WakeWordRepository.stopped()
            VoiceAssistantOverlay.hide()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startDetector() {
        if (engine != null) return
        startForeground(
            NOTIFICATION_ID,
            buildNotification("\"Hey Tupac\" 모델을 준비하는 중…"),
        )
        WakeWordRepository.preparing()
        detectionInProgress.set(false)
        scope.launch {
            runCatching {
                requireAsset(HEY_TUPAC_MODEL_ASSET)
                requireAsset(MEL_SPECTROGRAM_ASSET)
                requireAsset(EMBEDDING_MODEL_ASSET)

                WakeWordEngine(
                    context = applicationContext,
                    models = listOf(
                        WakeWordModel(
                            name = WAKE_PHRASE,
                            modelPath = HEY_TUPAC_MODEL_ASSET,
                            threshold = DETECTION_THRESHOLD,
                        ),
                    ),
                    detectionCooldownMs = DETECTION_COOLDOWN_MS,
                    scope = scope,
                ).also { wakeWordEngine ->
                    engine = wakeWordEngine
                    scope.launch {
                        wakeWordEngine.detections.collect { detection ->
                            onWakeWordDetected(detection.score)
                        }
                    }
                    wakeWordEngine.start()
                }
            }.onSuccess {
                WakeWordRepository.listening()
                notificationManager().notify(
                    NOTIFICATION_ID,
                    buildNotification("\"Hey Tupac\"을 기다리는 중…"),
                )
                Log.i(TAG, "OpenWakeWord detector is listening")
            }.onFailure(::detectorFailed)
        }
    }

    private fun requireAsset(path: String) {
        assets.open(path).close()
    }

    private fun onWakeWordDetected(score: Float) {
        if (!detectionInProgress.compareAndSet(false, true)) return
        Log.i(TAG, "Wake word detected: $WAKE_PHRASE, score=$score")
        handOffToVoiceCommand()
    }

    private fun handOffToVoiceCommand() {
        releaseDetector()
        WakeWordRepository.commandRunning()
        VoiceAssistantOverlay.show(
            this,
            VoiceOverlayPhase.PREPARING,
            "호출을 확인했어요",
        )
        notificationManager().notify(
            NOTIFICATION_ID,
            buildNotification("감지됨 · 한국어로 명령을 말씀하세요."),
        )
        handedOffToVoiceCommand = true
        VoiceCommandService.startFromWakeWord(this)
        // VoiceCommandService가 같은 알림 ID를 이어받습니다. 기존 서비스가
        // 살아 있으면 Android가 마이크 알림 두 개를 동시에 유지할 수 있습니다.
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun detectorFailed(error: Throwable) {
        Log.e(TAG, "OpenWakeWord detector failed", error)
        releaseDetector()
        val message = error.message ?: error::class.java.simpleName
        WakeWordRepository.failed(message)
        notificationManager().notify(
            NOTIFICATION_ID,
            buildNotification("시작 실패: $message"),
        )
        stopSelf()
    }

    private fun releaseDetector() {
        engine?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        engine = null
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
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Local GUI Agent · Hey Tupac")
            .setContentText(status)
            .setContentIntent(openAppIntent)
            .addAction(0, "끄기", stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        const val ACTION_START = "com.example.mobileguiagent.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.example.mobileguiagent.action.STOP_WAKE_WORD"
        const val ACTION_RESUME = "com.example.mobileguiagent.action.RESUME_WAKE_WORD"
        const val ACTION_COMMAND_NOW = "com.example.mobileguiagent.action.COMMAND_NOW"

        private const val WAKE_PHRASE = "Hey Tupac"
        private const val HEY_TUPAC_MODEL_ASSET = "hey_tupac.onnx"
        private const val MEL_SPECTROGRAM_ASSET = "melspectrogram.onnx"
        private const val EMBEDDING_MODEL_ASSET = "embedding_model.onnx"
        // The first bundled PoC model is trained from synthetic English speech.
        // A 0.50 midpoint produced a real-device false accept, so use a stricter
        // threshold until it can be tuned with representative user recordings.
        private const val DETECTION_THRESHOLD = 0.65f
        private const val DETECTION_COOLDOWN_MS = 3_000L
        internal const val CHANNEL_ID = "tupac_voice_session"
        internal const val NOTIFICATION_ID = 8767
        private const val TAG = "WakeWordService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(ACTION_STOP),
            )
        }

        fun resume(context: Context) {
            if (!WakeWordRepository.state.value.enabled) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(ACTION_RESUME),
            )
        }

        fun commandNow(context: Context) {
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(ACTION_COMMAND_NOW),
            )
        }
    }
}
