package com.hiro.codex_android.data.tts

import android.util.Log
import com.hiro.codex_android.data.AppSettings
import com.hiro.codex_android.data.SettingsStore
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * 豆包语音合成大模型 V3 双向流式（wss://openspeech.bytedance.com/api/v3/tts/bidirection）
 * 的最小接入：agent 回答的文本增量直接喂进来，PCM 音频边收边播。
 *
 * 按文档最佳实践复用一条 WebSocket：StartConnection 一次，随后每条 agent 回答
 * 对应一个 session（StartSession → TaskRequest* → FinishSession）。
 * 工具调用、思考过程等其他事件不经过这里，只有 agent 正文会朗读。
 */
class VolcengineTtsManager(private val settingsStore: SettingsStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    private var socket: WebSocket? = null
    private var connectionReady = false
    private var connectionFingerprint: String? = null
    private var sessionId: String? = null
    private var sessionStarted = false
    private var finishRequested = false
    /** 上一个 session 收尾期间，下一条回答已经完整结束，新 session 一开就要立即 Finish。 */
    private var queuedFinish = false
    private val pendingText = StringBuilder()
    private var sessionSettings: AppSettings? = null
    private val filter = SpeakTextFilter()
    private var player: PcmStreamPlayer? = null

    init {
        // 关掉开关立刻停止播报；连接级配置变化后下次合成用新配置重建连接。
        scope.launch {
            settingsStore.settings.collect { settings ->
                if (!settings.ttsEnabled) {
                    stop()
                    synchronized(lock) { teardownLocked() }
                } else {
                    synchronized(lock) {
                        if (socket != null && connectionFingerprint != fingerprint(settings)) {
                            teardownLocked()
                        }
                    }
                }
            }
        }
    }

    /** agent 回答正文的一个流式增量（仅 agentMessage 会走到这里）。 */
    fun onAgentDelta(delta: String) {
        val settings = settingsStore.settings.value
        if (!settings.ttsEnabled || settings.ttsApiKey.isBlank()) return
        val speakable = filter.feed(delta)
        synchronized(lock) {
            pendingText.append(speakable)
            when {
                sessionId == null -> beginSessionLocked(settings)
                // 正在收尾的 session 不能再塞文本，缓冲到下个 session 一起发。
                sessionStarted && !finishRequested -> flushPendingLocked()
            }
        }
    }

    /** 一条 agent 回答文本结束（item/completed），文本发完后关闭本次合成 session。 */
    fun onAgentMessageFinished() {
        synchronized(lock) {
            filter.reset()
            val activeSession = sessionId ?: run {
                pendingText.setLength(0)
                return
            }
            // 上一个 session 还在收尾：这条回答的文本已缓冲，标记其结束后立即 Finish。
            if (finishRequested) {
                queuedFinish = true
                return
            }
            finishRequested = true
            if (sessionStarted) {
                flushPendingLocked()
                sendLocked(EVENT_FINISH_SESSION, activeSession, EMPTY_PAYLOAD)
            }
        }
    }

    /** 立即清空待合成文本并中断当前播报（用户打断、发新消息、离开会话等）。 */
    fun stop() {
        synchronized(lock) {
            filter.reset()
            pendingText.setLength(0)
            finishRequested = false
            queuedFinish = false
            val activeSession = sessionId
            sessionId = null
            sessionStarted = false
            if (activeSession != null && connectionReady) {
                socket?.send(uplinkFrame(EVENT_CANCEL_SESSION, activeSession, EMPTY_PAYLOAD))
            }
            player?.interrupt()
            player = null
        }
    }

    fun release() {
        stop()
        synchronized(lock) { teardownLocked() }
        scope.cancel()
    }

    private fun beginSessionLocked(settings: AppSettings) {
        sessionSettings = settings
        sessionId = UUID.randomUUID().toString()
        sessionStarted = false
        finishRequested = false
        val currentSocket = socket
        if (currentSocket != null && connectionFingerprint != fingerprint(settings)) {
            teardownLocked()
        }
        if (socket == null) connectLocked(settings) else maybeStartSessionLocked()
    }

    private fun connectLocked(settings: AppSettings) {
        connectionReady = false
        connectionFingerprint = fingerprint(settings)
        val request = Request.Builder()
            .url(settings.ttsUrl)
            .header("X-Api-Key", settings.ttsApiKey)
            .header("X-Api-Resource-Id", settings.ttsResourceId)
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()
        socket = client.newWebSocket(request, listener)
    }

    private fun maybeStartSessionLocked() {
        val activeSession = sessionId ?: return
        val settings = sessionSettings ?: return
        if (!connectionReady || sessionStarted) return
        val payload = JSONObject()
            .put("event", EVENT_START_SESSION)
            .put("namespace", "BidirectionalTTS")
            .put("user", JSONObject().put("uid", "codex-android"))
            .put(
                "req_params",
                JSONObject()
                    .put("speaker", settings.ttsSpeaker)
                    .put(
                        "audio_params",
                        JSONObject()
                            .put("format", AUDIO_FORMAT)
                            .put("sample_rate", SAMPLE_RATE)
                            .put("speech_rate", settings.ttsSpeechRate),
                    )
                    // 服务端再过滤一遍 markdown 语法（标题/加粗/表格等），避免读出符号。
                    .put("additions", JSONObject().put("disable_markdown_filter", true).toString()),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        sendLocked(EVENT_START_SESSION, activeSession, payload)
    }

    private fun flushPendingLocked() {
        val activeSession = sessionId ?: return
        if (!sessionStarted || pendingText.isEmpty()) return
        val payload = JSONObject()
            .put("event", EVENT_TASK_REQUEST)
            .put("req_params", JSONObject().put("text", pendingText.toString()))
            .toString()
            .toByteArray(Charsets.UTF_8)
        pendingText.setLength(0)
        sendLocked(EVENT_TASK_REQUEST, activeSession, payload)
    }

    private fun sendLocked(event: Int, sessionId: String?, payload: ByteArray) {
        val sent = socket?.send(uplinkFrame(event, sessionId, payload)) ?: false
        if (!sent) Log.w(TAG, "TTS 帧发送失败 event=$event")
    }

    private fun teardownLocked() {
        player?.interrupt()
        player = null
        socket?.cancel()
        socket = null
        connectionReady = false
        connectionFingerprint = null
        sessionId = null
        sessionStarted = false
        finishRequested = false
        queuedFinish = false
        pendingText.setLength(0)
        filter.reset()
    }

    /** SessionFinished 后复位 session 状态；连接保留，缓冲的文本直接开新 session。 */
    private fun finishSessionLocked() {
        val settings = sessionSettings
        val startNext = pendingText.isNotEmpty() && settings != null
        // 下一段马上开：打断上一段，避免两路 AudioTrack 叠播；否则播完队列再收尾。
        if (startNext) player?.interrupt() else player?.finish()
        player = null
        sessionId = null
        sessionStarted = false
        finishRequested = false
        val finishNext = queuedFinish
        queuedFinish = false
        if (startNext) {
            beginSessionLocked(settings!!)
            // 文本已经到齐，等 SessionStarted 后 flush 并立即 FinishSession。
            if (finishNext) finishRequested = true
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 建连后第一帧 StartConnection，收到 ConnectionStarted 才算可用。
            if (!webSocket.send(uplinkFrame(EVENT_START_CONNECTION, null, EMPTY_PAYLOAD))) {
                Log.w(TAG, "TTS StartConnection 发送失败")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            runCatching { handleFrame(bytes.toByteArray()) }
                .onFailure { Log.w(TAG, "TTS 响应解析失败：${it.message}") }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.w(TAG, "TTS 服务端文本帧：$text")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "TTS 连接失败：${t.message}（HTTP ${response?.code ?: "-"}）")
            synchronized(lock) { teardownLocked() }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) { teardownLocked() }
        }
    }

    private fun handleFrame(frame: ByteArray) {
        require(frame.size >= 4) { "响应帧过短" }
        val headerSize = (frame[0].toInt() and 0x0F) * 4
        val messageType = (frame[1].toInt() ushr 4) and 0x0F
        val flags = frame[1].toInt() and 0x0F
        val serialization = (frame[2].toInt() ushr 4) and 0x0F
        val compression = frame[2].toInt() and 0x0F
        var offset = headerSize

        if (messageType == MESSAGE_TYPE_ERROR) {
            val errorCode = frame.readInt(offset); offset += 4
            val payloadSize = frame.readInt(offset); offset += 4
            val message = frame.copyOfRange(offset, offset + payloadSize)
                .toString(Charsets.UTF_8)
            Log.w(TAG, "TTS 服务端错误 code=$errorCode message=$message")
            synchronized(lock) { teardownLocked() }
            return
        }

        var event = 0
        if (flags and FLAG_WITH_EVENT != 0) {
            event = frame.readInt(offset)
            offset += 4
        }
        // 连接类事件带 connect id，其余（会话类/数据类）带 session id，解析后直接跳过。
        if (event != 0 && offset + 4 <= frame.size) {
            val idSize = frame.readInt(offset)
            offset += 4 + idSize
        }
        val payloadSize = if (offset + 4 <= frame.size) frame.readInt(offset) else 0
        offset += 4
        val payloadEnd = (offset + payloadSize).coerceAtMost(frame.size)
        var payload = frame.copyOfRange(offset, payloadEnd)
        if (compression == COMPRESSION_GZIP && serialization == SERIALIZATION_JSON && payload.isNotEmpty()) {
            payload = GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
        }

        when (event) {
            EVENT_CONNECTION_STARTED -> synchronized(lock) {
                connectionReady = true
                maybeStartSessionLocked()
            }

            EVENT_SESSION_STARTED -> synchronized(lock) {
                sessionStarted = true
                // 只保留最新一路播放，防止上一段 finish 后仍在播时叠上新 AudioTrack。
                player?.interrupt()
                player = PcmStreamPlayer(SAMPLE_RATE)
                flushPendingLocked()
                val activeSession = sessionId
                if (finishRequested && activeSession != null) {
                    sendLocked(EVENT_FINISH_SESSION, activeSession, EMPTY_PAYLOAD)
                }
            }

            EVENT_TTS_RESPONSE -> player?.write(payload)

            EVENT_SESSION_FINISHED -> synchronized(lock) { finishSessionLocked() }

            EVENT_SESSION_CANCELED, EVENT_SESSION_FAILED -> synchronized(lock) {
                player?.interrupt()
                player = null
                sessionId = null
                sessionStarted = false
                finishRequested = false
                queuedFinish = false
                pendingText.setLength(0)
            }

            EVENT_CONNECTION_FAILED -> synchronized(lock) { teardownLocked() }
        }
    }

    private fun fingerprint(settings: AppSettings): String =
        listOf(settings.ttsUrl, settings.ttsApiKey, settings.ttsResourceId).joinToString("|")

    private companion object {
        const val TAG = "VolcengineTts"
        const val SAMPLE_RATE = 24_000
        const val AUDIO_FORMAT = "pcm"

        const val EVENT_START_CONNECTION = 1
        const val EVENT_FINISH_CONNECTION = 2
        const val EVENT_CONNECTION_STARTED = 50
        const val EVENT_CONNECTION_FAILED = 51
        const val EVENT_START_SESSION = 100
        const val EVENT_CANCEL_SESSION = 101
        const val EVENT_FINISH_SESSION = 102
        const val EVENT_SESSION_STARTED = 150
        const val EVENT_SESSION_CANCELED = 151
        const val EVENT_SESSION_FINISHED = 152
        const val EVENT_SESSION_FAILED = 153
        const val EVENT_TASK_REQUEST = 200
        const val EVENT_TTS_RESPONSE = 352

        const val MESSAGE_TYPE_ERROR = 0x0F
        const val FLAG_WITH_EVENT = 0x04
        const val SERIALIZATION_JSON = 0x01
        const val COMPRESSION_GZIP = 0x01

        val EMPTY_PAYLOAD = "{}".toByteArray(Charsets.UTF_8)

        /** 上行帧：v1 4 字节头 + full-client request + event + 可选 session id + JSON/raw payload。 */
        fun uplinkFrame(event: Int, sessionId: String?, payload: ByteArray): ByteString {
            val sid = sessionId?.toByteArray(Charsets.UTF_8)
            val size = 4 + 4 + (sid?.let { 4 + it.size } ?: 0) + 4 + payload.size
            val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            // version=1 headerSize=4；full-client request + 带 event；JSON 不压缩。
            buffer.put(0x11.toByte())
            buffer.put(0x14.toByte())
            buffer.put(0x10.toByte())
            buffer.put(0)
            buffer.putInt(event)
            if (sid != null) {
                buffer.putInt(sid.size)
                buffer.put(sid)
            }
            buffer.putInt(payload.size)
            buffer.put(payload)
            return buffer.array().toByteString()
        }
    }
}

private fun ByteArray.readInt(offset: Int): Int =
    ByteBuffer.wrap(this, offset, 4).order(ByteOrder.BIG_ENDIAN).int
