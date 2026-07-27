package com.example.mobileguiagent.voice

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.setPadding
import com.example.mobileguiagent.accessibility.AgentAccessibilityService

enum class VoiceOverlayPhase(
    val title: String,
    val defaultStatus: String,
    val symbol: String,
    val startColor: Int,
    val endColor: Int,
    val showsSpinner: Boolean,
) {
    PREPARING(
        title = "준비 중",
        defaultStatus = "음성 모델을 불러오고 있어요",
        symbol = "···",
        startColor = Color.rgb(68, 116, 255),
        endColor = Color.rgb(77, 176, 255),
        showsSpinner = true,
    ),
    LISTENING(
        title = "말씀하세요",
        defaultStatus = "듣고 있어요",
        symbol = "●",
        startColor = Color.rgb(26, 190, 123),
        endColor = Color.rgb(53, 224, 158),
        showsSpinner = false,
    ),
    PROCESSING(
        title = "들었어요",
        defaultStatus = "요청을 처리하고 있어요",
        symbol = "✓",
        startColor = Color.rgb(124, 84, 255),
        endColor = Color.rgb(178, 99, 255),
        showsSpinner = true,
    ),
    SPEAKING(
        title = "답변 중",
        defaultStatus = "음성으로 알려드리고 있어요",
        symbol = "◖",
        startColor = Color.rgb(27, 163, 207),
        endColor = Color.rgb(65, 214, 214),
        showsSpinner = false,
    ),
    COMPLETE(
        title = "완료했어요",
        defaultStatus = "다시 부르면 도와드릴게요",
        symbol = "✓",
        startColor = Color.rgb(31, 174, 105),
        endColor = Color.rgb(72, 206, 123),
        showsSpinner = false,
    ),
    ERROR(
        title = "문제가 생겼어요",
        defaultStatus = "앱에서 상태를 확인해 주세요",
        symbol = "!",
        startColor = Color.rgb(211, 63, 72),
        endColor = Color.rgb(244, 102, 93),
        showsSpinner = false,
    ),
}

/**
 * 웨이크워드가 감지된 뒤에만 표시되는 작고 비차단적인 음성 상태 패널입니다.
 *
 * 서비스가 바뀌어도 동일한 application WindowManager와 View를 사용하므로
 * "준비 → 듣기 → 처리 → 답변" 전환 중 패널이 깜빡이지 않습니다.
 */
object VoiceAssistantOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var indicatorView: View? = null
    private var symbolView: TextView? = null
    private var titleView: TextView? = null
    private var statusView: TextView? = null
    private var progressView: ProgressBar? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var currentPhase: VoiceOverlayPhase? = null
    private var vibrator: Vibrator? = null

    fun show(
        context: Context,
        phase: VoiceOverlayPhase,
        status: String? = null,
    ) {
        mainHandler.post {
            val appContext = context.applicationContext
            val accessibilityService = AgentAccessibilityService.activeService
            val hostContext = accessibilityService ?: appContext
            val windowType = if (accessibilityService != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            if (
                windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                !Settings.canDrawOverlays(appContext)
            ) {
                Log.w(TAG, "Overlay not shown: draw-over-other-apps permission is missing")
                return@post
            }
            if (rootView == null) {
                val view = createView(hostContext)
                val manager = hostContext.getSystemService(WindowManager::class.java)
                runCatching {
                    manager.addView(view, layoutParams(hostContext, windowType))
                }
                    .onSuccess {
                        windowManager = manager
                        rootView = view
                        vibrator = hostContext.getSystemService(Vibrator::class.java)
                        Log.i(TAG, "Voice overlay shown: type=$windowType, phase=$phase")
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Unable to show voice overlay", error)
                        return@post
                    }
            }
            updateNow(phase, status)
        }
    }

    fun update(
        phase: VoiceOverlayPhase,
        status: String? = null,
    ) {
        mainHandler.post {
            if (rootView == null) return@post
            updateNow(phase, status)
        }
    }

    fun hide() {
        mainHandler.post {
            rootView?.let { view ->
                runCatching { windowManager?.removeView(view) }
            }
            rootView = null
            pulseAnimator?.cancel()
            pulseAnimator = null
            indicatorView = null
            symbolView = null
            titleView = null
            statusView = null
            progressView = null
            currentPhase = null
            vibrator = null
            windowManager = null
            Log.i(TAG, "Voice overlay hidden")
        }
    }

    private fun updateNow(
        phase: VoiceOverlayPhase,
        status: String?,
    ) {
        val phaseChanged = currentPhase != phase
        currentPhase = phase
        titleView?.text = phase.title
        statusView?.text = status ?: phase.defaultStatus
        symbolView?.text = phase.symbol
        progressView?.visibility = if (phase.showsSpinner) View.VISIBLE else View.INVISIBLE
        indicatorView?.background = indicatorBackground(phase)
        updatePulseAnimation(phase)
        if (phaseChanged) performPhaseHaptic(phase)
        rootView?.contentDescription = "${phase.title}. ${status ?: phase.defaultStatus}"
        Log.i(TAG, "Voice overlay phase: $phase")
    }

    private fun createView(context: Context): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16))
            elevation = dp(12).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.rgb(27, 29, 34))
                setStroke(dp(1), Color.rgb(66, 70, 78))
            }
        }

        val indicator = FrameLayout(context)
        indicatorView = indicator
        val indicatorParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginEnd = dp(12)
        }
        val microphone = TextView(context).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Tupac 음성 상태"
        }
        symbolView = microphone
        indicator.addView(
            microphone,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        panel.addView(indicator, indicatorParams)

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleView = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        labels.addView(titleView)
        statusView = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.rgb(197, 201, 210))
            maxLines = 1
        }
        labels.addView(statusView)
        panel.addView(
            labels,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        progressView = ProgressBar(context).apply {
            isIndeterminate = true
            contentDescription = "처리 중"
        }
        panel.addView(
            progressView,
            LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginStart = dp(12)
            },
        )

        val close = TextView(context).apply {
            text = "×"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(197, 201, 210))
            contentDescription = "음성 명령 닫기"
            setOnClickListener {
                WakeWordService.stop(context)
                VoiceCommandService.stop(context)
                hide()
            }
        }
        panel.addView(
            close,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginStart = dp(4)
            },
        )
        return panel
    }

    private fun indicatorBackground(phase: VoiceOverlayPhase) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        colors = intArrayOf(phase.startColor, phase.endColor)
        gradientType = GradientDrawable.LINEAR_GRADIENT
    }

    private fun updatePulseAnimation(phase: VoiceOverlayPhase) {
        pulseAnimator?.cancel()
        pulseAnimator = null
        indicatorView?.apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }
        if (phase != VoiceOverlayPhase.LISTENING) return
        val target = indicatorView ?: return
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            target,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.14f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.14f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.72f, 1f),
        ).apply {
            duration = 1_100L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun performPhaseHaptic(phase: VoiceOverlayPhase) {
        val activeVibrator = vibrator ?: return
        val pattern = when (phase) {
            VoiceOverlayPhase.LISTENING -> longArrayOf(0L, 55L)
            VoiceOverlayPhase.PROCESSING -> longArrayOf(0L, 35L, 55L, 35L)
            VoiceOverlayPhase.COMPLETE -> longArrayOf(0L, 30L)
            else -> return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            activeVibrator.vibrate(pattern, -1)
        }
    }

    private fun layoutParams(
        context: Context,
        windowType: Int,
    ): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        return WindowManager.LayoutParams(
            (340 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt()
            title = "TupacVoiceOverlay"
        }
    }

    private const val TAG = "VoiceAssistantOverlay"
}
