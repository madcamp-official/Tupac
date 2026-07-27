package com.example.mobileguiagent.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Small lifecycle-safe wrapper around the installed Android TTS engine.
 *
 * It waits for engine initialization and utterance completion so the owning
 * foreground service is not stopped while speech is still playing.
 */
class LocalTextToSpeech(context: Context) : TextToSpeech.OnInitListener {
    private val initialization = CompletableDeferred<Int>()
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val engine = TextToSpeech(context.applicationContext, this)

    init {
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i(TAG, "TTS utterance started: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { completions.remove(it)?.complete(true) }
                    Log.i(TAG, "TTS utterance completed: $utteranceId")
                }

                @Deprecated("Deprecated by Android")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { completions.remove(it)?.complete(false) }
                    Log.e(TAG, "TTS utterance failed: $utteranceId")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let { completions.remove(it)?.complete(false) }
                    Log.e(TAG, "TTS utterance failed: $utteranceId, error=$errorCode")
                }
            },
        )
    }

    override fun onInit(status: Int) {
        if (!initialization.isCompleted) {
            initialization.complete(status)
        }
        Log.i(TAG, "Android TTS initialized: status=$status")
    }

    suspend fun speak(text: String): Boolean {
        val status = withTimeoutOrNull(INIT_TIMEOUT_MS) { initialization.await() }
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Android TTS is unavailable: status=$status")
            return false
        }

        val languageResult = engine.setLanguage(Locale.KOREAN)
        if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.e(TAG, "Installed TTS engine does not support Korean")
            return false
        }

        val normalized = text.trim()
            .take(TextToSpeech.getMaxSpeechInputLength())
        if (normalized.isBlank()) return false

        val utteranceId = "agent-${UUID.randomUUID()}"
        val completion = CompletableDeferred<Boolean>()
        completions[utteranceId] = completion
        val enqueueResult = engine.speak(
            normalized,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId,
        )
        if (enqueueResult == TextToSpeech.ERROR) {
            completions.remove(utteranceId)
            Log.e(TAG, "Android TTS rejected the utterance")
            return false
        }

        return withTimeoutOrNull(SPEAK_TIMEOUT_MS) { completion.await() } ?: false
    }

    fun close() {
        completions.values.forEach { completion ->
            if (!completion.isCompleted) completion.complete(false)
        }
        completions.clear()
        engine.stop()
        engine.shutdown()
    }

    companion object {
        private const val TAG = "LocalTextToSpeech"
        private const val INIT_TIMEOUT_MS = 10_000L
        private const val SPEAK_TIMEOUT_MS = 60_000L
    }
}
