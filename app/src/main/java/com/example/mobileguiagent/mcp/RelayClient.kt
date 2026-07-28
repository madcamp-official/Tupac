package com.example.mobileguiagent.mcp

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 폰이 릴레이에 먼저 접속해 할 일을 기다린다.
 *
 * HTTP 서버(PocketMcpHttpServer)는 남이 폰에 붙어오기를 기다린다. 그건 adb로
 * 통로를 뚫었을 때만 되는데, 폰에는 남이 접속할 주소가 없기 때문이다. 통신사
 * NAT 뒤에 있고, 캠퍼스 Wi-Fi는 기기끼리 통신을 막는다(실측: 같은 공유기의
 * 맥북조차 폰에 못 닿았다).
 *
 * 그래서 방향을 뒤집는다. 나가는 접속은 언제나 되므로, 폰이 릴레이로 나가서
 * "할 일 있어?"를 반복한다. 릴레이는 일이 생길 때까지 답을 붙들고 있다가
 * 넘겨준다 — 사실상 릴레이가 밀어주는 것과 같아진다.
 *
 * WebSocket이 아닌 이유는 이 앱에 네트워크 라이브러리가 없어서다. 롱폴링은
 * HttpURLConnection만으로 되고, 한 왕복 늦는 것은 지금 흐름에서 티가 안 난다
 * (한 스텝이 이미 4초다).
 *
 * HTTP 서버와 같은 처리기(handle)를 쓴다. 둘이 갈라지면 어느 길로 들어왔느냐에
 * 따라 도구 목록이나 동작이 달라진다.
 *
 * 오가는 것은 화면 텍스트와 도구 호출이다. 그래서 본문은 로그에 적지 않는다 —
 * logcat은 다른 앱도 읽을 수 있다.
 */
class RelayClient(
    private val baseUrl: String,
    private val token: String,
    private val handle: (JSONObject) -> JSONObject?,
) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ loop() }, "relay-client").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "릴레이에 접속합니다: $baseUrl")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.interrupt()
        worker = null
        Log.i(TAG, "릴레이 접속을 끊었습니다")
    }

    private fun loop() {
        var backoffMs = MIN_BACKOFF_MS
        while (running.get()) {
            try {
                val job = poll()
                // 닿았으면 물러섬을 되돌린다. 잠깐 끊겼다 돌아온 경우다.
                backoffMs = MIN_BACKOFF_MS
                if (job != null) {
                    reply(job.ticket, answerFor(job.body))
                }
            } catch (error: InterruptedException) {
                return                                  // stop()이 깨운 것이다
            } catch (error: IOException) {
                if (!running.get()) return
                // 릴레이가 죽었거나 네트워크가 끊겼다. 곧바로 다시 붙으면 배터리만
                // 태우므로 점점 뜸하게 시도한다.
                Log.w(TAG, "릴레이에 닿지 못했습니다: ${error.message}. ${backoffMs}ms 뒤 재시도")
                if (!sleepQuietly(backoffMs)) return
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /**
     * 요청 하나를 처리한다. 무슨 일이 있어도 답을 만들어 돌려준다 —
     * 여기서 예외가 새면 릴레이가 180초를 기다리다 끊고, 부르는 쪽은
     * 이유도 모른 채 실패한다.
     */
    private fun answerFor(body: JSONObject): JSONObject {
        // 알림(id 없는 요청)에는 답이 없다. 그래도 릴레이가 기다리고 있으므로
        // 빈 것이라도 보내야 그쪽 자리가 풀린다.
        val id = if (body.has("id")) body.get("id") else null
        return try {
            handle(body) ?: JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
        } catch (error: Throwable) {
            Log.e(TAG, "요청을 처리하지 못했습니다: ${error::class.java.simpleName}")
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id ?: JSONObject.NULL)
                .put(
                    "error",
                    JSONObject()
                        .put("code", -32603)
                        .put("message", "폰에서 처리하지 못했습니다: ${error.message}"),
                )
        }
    }

    private class Job(val ticket: String, val body: JSONObject)

    /** 할 일을 기다린다. 릴레이가 붙들고 있다가 없으면 204로 답한다. */
    private fun poll(): Job? {
        val connection = open("/poll", "GET", readTimeout = POLL_READ_TIMEOUT_MS)
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NO_CONTENT) return null
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("poll이 HTTP $code 를 돌려줬습니다")
            }
            val payload = JSONObject(connection.inputStream.bufferedReader().readText())
            return Job(payload.getString("ticket"), payload.getJSONObject("body"))
        } finally {
            connection.disconnect()
        }
    }

    private fun reply(ticket: String, body: JSONObject) {
        val connection = open("/reply", "POST", readTimeout = REPLY_TIMEOUT_MS)
        try {
            connection.doOutput = true
            val payload = JSONObject().put("ticket", ticket).put("body", body).toString()
            connection.outputStream.use { it.write(payload.toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("reply가 HTTP $code 를 돌려줬습니다")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String, readTimeout: Int): HttpURLConnection =
        (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            this.readTimeout = readTimeout
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }

    /** 자는 도중 stop()이 깨우면 false. */
    private fun sleepQuietly(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (_: InterruptedException) {
        false
    }

    private companion object {
        const val TAG = "RelayClient"
        const val CONNECT_TIMEOUT_MS = 10_000

        // 릴레이가 25초까지 붙들고 있으므로 그보다 넉넉해야 한다. 짧으면 일이
        // 없을 때마다 타임아웃 예외가 나고, 그때마다 물러섬이 걸린다.
        const val POLL_READ_TIMEOUT_MS = 40_000
        const val REPLY_TIMEOUT_MS = 20_000

        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
