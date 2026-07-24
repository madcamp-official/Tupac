package com.example.mobileguiagent.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptEventListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class MoonshineKoreanState(
    val modelReady: Boolean = false,
    val downloading: Boolean = false,
    val loading: Boolean = false,
    val listening: Boolean = false,
    val status: String = "한국어 모델을 준비하세요.",
    val progress: Float? = null,
    val currentText: String = "",
    val completedText: String = "",
    val error: String? = null,
    val testLatencyMs: Long? = null,
)

object MoonshineKoreanRepository {
    private const val MODEL_DIRECTORY = "moonshine/tiny-ko"
    private val modelSpec = ModelSpec.stt(
        "ko",
        JNI.MOONSHINE_MODEL_ARCH_TINY,
        false,
    )
    private val executor = Executors.newSingleThreadExecutor()
    private val mutableState = MutableStateFlow(MoonshineKoreanState())
    private val mutableFinalTranscripts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val state = mutableState.asStateFlow()
    val finalTranscripts = mutableFinalTranscripts.asSharedFlow()

    @Volatile
    private var transcriber: Transcriber? = null
    private val recording = AtomicBoolean(false)
    @Volatile
    private var audioRecord: AudioRecord? = null

    fun refresh(context: Context) {
        if (transcriber != null) {
            mutableState.value = mutableState.value.copy(modelReady = true)
            return
        }
        executor.execute {
            val present = runCatching {
                AssetDownloader().isModelPresent(modelDirectory(context), modelSpec)
            }.getOrDefault(false)
            mutableState.value = mutableState.value.copy(
                modelReady = present,
                status = if (present) {
                    "한국어 Tiny 모델이 설치되어 있습니다."
                } else {
                    "한국어 Tiny 모델을 다운로드해야 합니다."
                },
            )
        }
    }

    fun ensureReady(
        context: Context,
        onReady: (() -> Unit)? = null,
    ) {
        if (transcriber != null) {
            onReady?.invoke()
            return
        }
        val current = mutableState.value
        if (current.downloading || current.loading) return

        mutableState.value = current.copy(
            downloading = true,
            loading = false,
            status = "Moonshine Korean 모델 확인 중…",
            progress = null,
            error = null,
        )
        executor.execute {
            runCatching {
                val downloader = AssetDownloader()
                val directory = modelDirectory(context)
                val root = downloader.ensureModelPresent(
                    directory,
                    modelSpec,
                ) { path, index, total, done, size ->
                    val fileProgress = if (size > 0) {
                        done.toFloat() / size.toFloat()
                    } else {
                        0f
                    }
                    val overallProgress = ((index - 1) + fileProgress) / total.coerceAtLeast(1)
                    mutableState.value = mutableState.value.copy(
                        downloading = true,
                        status = "모델 다운로드 $index/$total · $path",
                        progress = overallProgress.coerceIn(0f, 1f),
                    )
                }
                mutableState.value = mutableState.value.copy(
                    downloading = false,
                    loading = true,
                    status = "한국어 모델 로딩 중…",
                    progress = 1f,
                )
                val loaded = createTranscriber()
                loaded.loadFromFiles(
                    root.absolutePath,
                    JNI.MOONSHINE_MODEL_ARCH_TINY,
                )
                transcriber = loaded
            }.onSuccess {
                Log.i(TAG, "Korean Tiny model downloaded and loaded successfully")
                mutableState.value = mutableState.value.copy(
                    modelReady = true,
                    downloading = false,
                    loading = false,
                    status = "Moonshine Korean 준비 완료 · 완전 로컬",
                    progress = null,
                    error = null,
                )
                onReady?.invoke()
            }.onFailure { error ->
                Log.e(TAG, "Unable to prepare Korean Tiny model", error)
                mutableState.value = mutableState.value.copy(
                    downloading = false,
                    loading = false,
                    listening = false,
                    status = "Moonshine Korean 준비 실패",
                    progress = null,
                    error = error.message ?: error::class.java.simpleName,
                )
            }
        }
    }

    fun onMicPermissionGranted() = Unit

    @Suppress("MissingPermission")
    fun startListening(): Boolean {
        val active = transcriber ?: return false
        if (!recording.compareAndSet(false, true)) return true
        return runCatching {
            active.start()
            val minimumBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBuffer, AUDIO_CHUNK_SAMPLES * 2),
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord 초기화에 실패했습니다."
            }
            audioRecord = recorder
            recorder.startRecording()
            Thread(
                { captureMicrophone(active, recorder) },
                "MoonshineKoreanMic",
            ).start()
            Log.i(TAG, "Korean microphone transcription started")
            mutableState.value = mutableState.value.copy(
                listening = true,
                status = "듣는 중… 한국어로 말해보세요.",
                currentText = "",
                error = null,
            )
            true
        }.getOrElse { error ->
            recording.set(false)
            runCatching { audioRecord?.release() }
            audioRecord = null
            mutableState.value = mutableState.value.copy(
                listening = false,
                status = "음성인식 시작 실패",
                error = error.message ?: error::class.java.simpleName,
            )
            false
        }
    }

    fun stopListening() {
        recording.set(false)
        runCatching { audioRecord?.stop() }
        mutableState.value = mutableState.value.copy(
            listening = false,
            status = "음성인식을 중지했습니다.",
        )
    }

    fun markCommandForwarded(text: String) {
        mutableState.value = mutableState.value.copy(
            status = "MiniCPM 입력 전달 완료: $text",
        )
    }

    fun runSampleTest(context: Context) {
        ensureReady(context) {
            mutableState.value = mutableState.value.copy(
                status = "내장 한국어 음성 샘플 인식 중…",
                currentText = "",
                error = null,
                testLatencyMs = null,
            )
            executor.execute {
                runCatching {
                    val wav = readPcm16Wav(
                        context.assets.open(TEST_AUDIO_ASSET).use { it.readBytes() },
                    )
                    val startedAt = SystemClock.elapsedRealtime()
                    val transcript = requireNotNull(transcriber).transcribeWithoutStreaming(
                        wav.samples,
                        wav.sampleRate,
                    )
                    transcript.text().trim() to
                        (SystemClock.elapsedRealtime() - startedAt)
                }.onSuccess { (text, latencyMs) ->
                    Log.i(TAG, "Korean sample test completed in ${latencyMs}ms: $text")
                    mutableState.value = mutableState.value.copy(
                        status = "내장 샘플 인식 완료",
                        currentText = "",
                        completedText = text,
                        testLatencyMs = latencyMs,
                        error = null,
                    )
                }.onFailure { error ->
                    Log.e(TAG, "Korean sample test failed", error)
                    mutableState.value = mutableState.value.copy(
                        status = "내장 샘플 인식 실패",
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
            }
        }
    }

    private fun captureMicrophone(
        active: Transcriber,
        recorder: AudioRecord,
    ) {
        val pcm = ShortArray(AUDIO_CHUNK_SAMPLES)
        try {
            while (recording.get()) {
                val count = recorder.read(pcm, 0, pcm.size)
                if (count <= 0) continue
                val samples = FloatArray(count) { index -> pcm[index] / 32768f }
                active.addAudio(samples, AUDIO_SAMPLE_RATE)
            }
        } catch (error: Throwable) {
            if (recording.get()) {
                Log.e(TAG, "Microphone capture failed", error)
                mutableState.value = mutableState.value.copy(
                    error = error.message ?: error::class.java.simpleName,
                    status = "마이크 처리 오류",
                )
            }
        } finally {
            recording.set(false)
            runCatching { recorder.stop() }
            recorder.release()
            audioRecord = null
            runCatching { active.stop() }
            mutableState.value = mutableState.value.copy(listening = false)
        }
    }

    private fun createTranscriber(): Transcriber = Transcriber(
        listOf(
            TranscriberOption("max_tokens_per_second", "13.0"),
        ),
    ).apply {
        addListener { event ->
            event.accept(
                object : TranscriptEventListener() {
                    override fun onLineTextChanged(event: TranscriptEvent.LineTextChanged) {
                        mutableState.value = mutableState.value.copy(
                            currentText = event.line.text.orEmpty(),
                        )
                    }

                    override fun onLineCompleted(event: TranscriptEvent.LineCompleted) {
                        val text = event.line.text.orEmpty().trim()
                        if (text.isBlank()) return
                        Log.i(TAG, "Korean transcript completed: $text")
                        val previous = mutableState.value.completedText
                        mutableState.value = mutableState.value.copy(
                            currentText = "",
                            completedText = listOf(previous, text)
                                .filter(String::isNotBlank)
                                .joinToString("\n"),
                            status = "인식 완료 · 계속 듣는 중…",
                        )
                        mutableFinalTranscripts.tryEmit(text)
                    }

                    override fun onError(event: TranscriptEvent.Error) {
                        Log.e(TAG, "Korean transcription error", event.cause)
                        mutableState.value = mutableState.value.copy(
                            error = event.cause.message ?: event.cause::class.java.simpleName,
                            status = "음성인식 오류",
                        )
                    }
                },
            )
        }
    }

    private fun modelDirectory(context: Context): File =
        File(context.filesDir, MODEL_DIRECTORY)

    private fun readPcm16Wav(bytes: ByteArray): PcmAudio {
        require(bytes.size >= 44) { "WAV 파일이 너무 짧습니다." }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(offset + 4)
            val payloadOffset = offset + 8
            if (chunkSize < 0 || payloadOffset + chunkSize > bytes.size) break
            when (chunkId) {
                "fmt " -> {
                    require(buffer.getShort(payloadOffset).toInt() == 1) {
                        "PCM WAV만 지원합니다."
                    }
                    channels = buffer.getShort(payloadOffset + 2).toInt()
                    sampleRate = buffer.getInt(payloadOffset + 4)
                    bitsPerSample = buffer.getShort(payloadOffset + 14).toInt()
                }
                "data" -> {
                    dataOffset = payloadOffset
                    dataSize = chunkSize
                }
            }
            offset = payloadOffset + chunkSize + (chunkSize and 1)
        }
        require(sampleRate > 0 && channels == 1 && bitsPerSample == 16) {
            "16-bit mono PCM WAV가 필요합니다."
        }
        require(dataOffset >= 0 && dataSize > 0) { "WAV data 청크가 없습니다." }
        val samples = FloatArray(dataSize / 2)
        for (index in samples.indices) {
            samples[index] = buffer.getShort(dataOffset + index * 2) / 32768f
        }
        return PcmAudio(samples, sampleRate)
    }

    private data class PcmAudio(
        val samples: FloatArray,
        val sampleRate: Int,
    )

    private const val TEST_AUDIO_ASSET = "moonshine_test_ko.wav"
    private const val AUDIO_SAMPLE_RATE = 16_000
    private const val AUDIO_CHUNK_SAMPLES = 1_600
    private const val TAG = "MoonshineKorean"
}
