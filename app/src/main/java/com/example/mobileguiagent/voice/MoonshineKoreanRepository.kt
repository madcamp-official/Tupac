package com.example.mobileguiagent.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class MoonshineKoreanState(
    val modelReady: Boolean = false,
    val downloading: Boolean = false,
    val loading: Boolean = false,
    val listening: Boolean = false,
    val status: String = "고정밀 로컬 STT를 준비하세요.",
    val progress: Float? = null,
    val currentText: String = "",
    val completedText: String = "",
    val error: String? = null,
    val testLatencyMs: Long? = null,
)

/**
 * Fully local Korean STT using SenseVoice Small INT8 through sherpa-onnx.
 *
 * Network is used only once to download model assets. Audio capture and every
 * transcription run happen inside this process without a speech API/server.
 */
object MoonshineKoreanRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val mutableState = MutableStateFlow(MoonshineKoreanState())
    private val mutableFinalTranscripts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val recording = AtomicBoolean(false)

    val state = mutableState.asStateFlow()
    val finalTranscripts = mutableFinalTranscripts.asSharedFlow()

    @Volatile
    private var recognizer: OfflineRecognizer? = null
    @Volatile
    private var audioRecord: AudioRecord? = null

    fun refresh(context: Context) {
        val present = modelFile(context).isFile && tokensFile(context).isFile
        mutableState.value = mutableState.value.copy(
            modelReady = recognizer != null || present,
            status = if (recognizer != null) {
                "SenseVoice Korean · 완전 로컬"
            } else if (present) {
                "SenseVoice Korean 모델 설치됨"
            } else {
                "고정밀 SenseVoice 모델 다운로드 필요 · 약 229MB"
            },
            error = null,
        )
    }

    fun ensureReady(context: Context, onReady: (() -> Unit)? = null) {
        if (recognizer != null) {
            onReady?.invoke()
            return
        }
        if (mutableState.value.downloading || mutableState.value.loading) return
        mutableState.value = mutableState.value.copy(
            downloading = true,
            loading = false,
            status = "SenseVoice 모델 확인 중…",
            error = null,
        )
        executor.execute {
            runCatching {
                val directory = modelDirectory(context).apply { mkdirs() }
                downloadIfMissing(MODEL_URL, File(directory, MODEL_FILE_NAME), 0f, 0.995f)
                downloadIfMissing(TOKENS_URL, File(directory, TOKENS_FILE_NAME), 0.995f, 1f)
                mutableState.value = mutableState.value.copy(
                    downloading = false,
                    loading = true,
                    status = "SenseVoice 로컬 엔진 로딩 중…",
                    progress = 1f,
                )
                OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = modelFile(context).absolutePath,
                                language = "ko",
                                useInverseTextNormalization = true,
                            ),
                            tokens = tokensFile(context).absolutePath,
                            numThreads = Runtime.getRuntime().availableProcessors()
                                .coerceIn(4, 8),
                            provider = "cpu",
                        ),
                    ),
                )
            }.onSuccess { loaded ->
                recognizer = loaded
                mutableState.value = mutableState.value.copy(
                    modelReady = true,
                    downloading = false,
                    loading = false,
                    status = "SenseVoice Korean · 완전 로컬",
                    progress = null,
                    error = null,
                )
                onReady?.invoke()
            }.onFailure { error ->
                Log.e(TAG, "Unable to prepare SenseVoice Korean", error)
                mutableState.value = mutableState.value.copy(
                    downloading = false,
                    loading = false,
                    status = "로컬 STT 준비 실패",
                    progress = null,
                    error = error.message ?: error::class.java.simpleName,
                )
            }
        }
    }

    @Suppress("MissingPermission")
    fun startListening(): Boolean {
        val active = recognizer ?: return false
        if (!recording.compareAndSet(false, true)) return true
        return runCatching {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, CHUNK_SAMPLES * 2),
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            audioRecord = recorder
            recorder.startRecording()
            mutableState.value = mutableState.value.copy(
                listening = true,
                status = "듣는 중… 한국어로 말씀하세요.",
                currentText = "",
                completedText = "",
                error = null,
            )
            executor.execute { captureAndTranscribe(active, recorder) }
            true
        }.getOrElse { error ->
            recording.set(false)
            mutableState.value = mutableState.value.copy(
                listening = false,
                status = "마이크 시작 실패",
                error = error.message,
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
        mutableState.value = mutableState.value.copy(status = "MiniCPM 입력 전달 완료: $text")
    }

    private fun captureAndTranscribe(active: OfflineRecognizer, recorder: AudioRecord) {
        val pcm = ArrayList<Short>(SAMPLE_RATE * 8)
        val chunk = ShortArray(CHUNK_SAMPLES)
        var speechStarted = false
        var silentChunks = 0
        try {
            while (recording.get() && pcm.size < SAMPLE_RATE * MAX_SECONDS) {
                val count = recorder.read(chunk, 0, chunk.size)
                if (count <= 0) continue
                for (index in 0 until count) pcm += chunk[index]
                val rms = sqrt(
                    (0 until count).sumOf {
                        val value = chunk[it].toDouble()
                        value * value
                    } / count,
                )
                if (rms >= SPEECH_RMS_THRESHOLD) {
                    speechStarted = true
                    silentChunks = 0
                } else if (speechStarted) {
                    silentChunks++
                }
                if (speechStarted && silentChunks >= END_SILENCE_CHUNKS) break
            }
            recording.set(false)
            runCatching { recorder.stop() }
            mutableState.value = mutableState.value.copy(
                listening = false,
                status = "폰에서 음성을 분석하고 있어요…",
            )
            if (!speechStarted || pcm.isEmpty()) {
                mutableState.value = mutableState.value.copy(
                    status = "음성을 인식하지 못했습니다.",
                    error = "음성이 충분히 들리지 않았습니다.",
                )
                return
            }
            val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
            val stream = active.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                active.decode(stream)
                val text = active.getResult(stream).text
                    .replace(Regex("""<\|[^>]+>\|"""), "")
                    .trim()
                mutableState.value = mutableState.value.copy(
                    currentText = text,
                    completedText = text,
                    status = if (text.isBlank()) "인식 결과가 없습니다." else "인식 완료: $text",
                    error = null,
                )
                if (text.isNotBlank()) mutableFinalTranscripts.tryEmit(text)
            } finally {
                stream.release()
            }
        } catch (error: Throwable) {
            Log.e(TAG, "SenseVoice transcription failed", error)
            mutableState.value = mutableState.value.copy(
                listening = false,
                status = "로컬 음성인식 오류",
                error = error.message ?: error::class.java.simpleName,
            )
        } finally {
            recording.set(false)
            recorder.release()
            audioRecord = null
        }
    }

    private fun downloadIfMissing(
        source: String,
        target: File,
        progressStart: Float,
        progressEnd: Float,
    ) {
        if (target.isFile && target.length() > 0L) return
        val part = File(target.parentFile, "${target.name}.part")
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.connect()
        check(connection.responseCode in 200..299) {
            "모델 다운로드 실패: HTTP ${connection.responseCode}"
        }
        val total = connection.contentLengthLong.coerceAtLeast(1L)
        connection.inputStream.use { input ->
            part.outputStream().buffered().use { output ->
                val buffer = ByteArray(256 * 1024)
                var downloaded = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    val fraction = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                    mutableState.value = mutableState.value.copy(
                        downloading = true,
                        status = "고정밀 로컬 STT 다운로드 ${(fraction * 100).toInt()}%",
                        progress = progressStart + (progressEnd - progressStart) * fraction,
                    )
                }
            }
        }
        check(part.renameTo(target)) { "다운로드 파일을 확정하지 못했습니다." }
        connection.disconnect()
    }

    private fun modelDirectory(context: Context) = File(context.filesDir, MODEL_DIRECTORY)
    private fun modelFile(context: Context) = File(modelDirectory(context), MODEL_FILE_NAME)
    private fun tokensFile(context: Context) = File(modelDirectory(context), TOKENS_FILE_NAME)

    private const val MODEL_DIRECTORY = "sensevoice/small-int8-ko"
    private const val MODEL_FILE_NAME = "model.int8.onnx"
    private const val TOKENS_FILE_NAME = "tokens.txt"
    private const val MODEL_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"
    private const val TOKENS_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt"
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_SAMPLES = 1_600
    private const val MAX_SECONDS = 20
    private const val SPEECH_RMS_THRESHOLD = 450.0
    private const val END_SILENCE_CHUNKS = 10
    private const val TAG = "SenseVoiceKorean"
}
