package com.hiro.codex_android.data

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * 火山引擎大模型流式 ASR 的最小客户端。
 *
 * 文档规定的二进制帧由 4 字节头、4 字节大端 payload 长度以及 gzip payload 组成。
 * Android 的 [AudioRecord] 直接提供 PCM s16le，所以每 200ms 将一块原始 PCM 发给服务端。
 */
class StreamingAsrClient {

    fun start(
        settings: AppSettings,
        onTranscript: (String) -> Unit,
        onFailure: (String) -> Unit,
    ): Session {
        require(settings.asrAppKey.isNotBlank()) { "请先在设置中填写 ASR App Key（App ID）" }
        require(settings.asrAccessKey.isNotBlank()) { "请先在设置中填写 ASR Access Key（Access Token）" }
        require(settings.asrUrl.startsWith("ws://") || settings.asrUrl.startsWith("wss://")) {
            "ASR 地址必须以 ws:// 或 wss:// 开头"
        }
        require(settings.asrResourceId.isNotBlank()) { "请先在设置中填写 ASR 资源 ID" }

        return Session(settings, onTranscript, onFailure).also(Session::start)
    }

    class Session internal constructor(
        private val settings: AppSettings,
        private val onTranscript: (String) -> Unit,
        private val onFailure: (String) -> Unit,
    ) {
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val stopping = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        private val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        @Volatile private var socket: WebSocket? = null
        @Volatile private var recorder: AudioRecord? = null

        internal fun start() {
            val connectionId = UUID.randomUUID().toString()
            val request = Request.Builder()
                .url(settings.asrUrl)
                .header("X-Api-App-Key", settings.asrAppKey)
                .header("X-Api-Access-Key", settings.asrAccessKey)
                .header("X-Api-Resource-Id", settings.asrResourceId)
                .header("X-Api-Sequence", "-1")
                // 文档的表格与示例分别使用 Request-Id / Connect-Id；同时传入同一个 UUID
                // 兼容两个入口的网关实现。
                .header("X-Api-Request-Id", connectionId)
                .header("X-Api-Connect-Id", connectionId)
                .build()
            socket = client.newWebSocket(request, listener)
        }

        /** 停止采集后发送负包，给服务端机会返回最终纠正后的文本。 */
        fun stop() {
            if (!stopping.compareAndSet(false, true)) return
            stopRecorder()
            val currentSocket = socket
            if (currentSocket == null) {
                finishSilently()
                return
            }
            ioScope.launch {
                currentSocket.send(audioRequest(ByteArray(0), isLast = true))
                // 服务端通常会立刻回最终包；异常网络下不让连接无限保留。
                delay(FINAL_RESPONSE_TIMEOUT_MS)
                finishSilently()
            }
        }

        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopping.get()) {
                    webSocket.close(NORMAL_CLOSE_CODE, "cancelled before recording")
                    return
                }
                if (!webSocket.send(fullClientRequest())) {
                    fail("语音识别请求未能发送")
                    return
                }
                startRecording(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { parseServerResponse(bytes.toByteArray()) }
                    .onSuccess { response ->
                        response.text?.let(onTranscript)
                        if (response.isFinal) finishSilently()
                    }
                    .onFailure { error -> fail("语音识别响应解析失败：${error.message ?: "未知错误"}") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopping.get()) fail("语音识别连接失败：${t.message ?: "未知错误"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopping.get() && !finished.get()) {
                    fail("语音识别连接已关闭${reason.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()}")
                }
            }
        }

        private fun startRecording(webSocket: WebSocket) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferSize <= 0) {
                fail("设备不支持 16 kHz 单声道录音")
                return
            }

            val audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBufferSize, AUDIO_FRAME_BYTES * 2))
                .build()
            recorder = audioRecord

            try {
                audioRecord.startRecording()
            } catch (error: IllegalStateException) {
                fail("无法启动麦克风：${error.message ?: "未知错误"}")
                return
            }
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fail("无法启动麦克风，请检查录音权限")
                return
            }

            ioScope.launch {
                val buffer = ByteArray(AUDIO_FRAME_BYTES)
                while (!stopping.get() && !finished.get()) {
                    val count = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    when {
                        count > 0 && !stopping.get() -> {
                            if (!webSocket.send(audioRequest(buffer.copyOf(count), isLast = false))) {
                                fail("语音数据发送失败")
                                return@launch
                            }
                        }
                        count < 0 && !stopping.get() -> {
                            fail("读取麦克风失败（错误码 $count）")
                            return@launch
                        }
                    }
                }
            }
        }

        private fun fullClientRequest(): ByteString {
            val payload = JSONObject()
                .put("user", JSONObject().put("uid", "codex-android"))
                .put(
                    "audio",
                    JSONObject()
                        .put("format", "pcm")
                        .put("codec", "raw")
                        .put("rate", SAMPLE_RATE)
                        .put("bits", 16)
                        .put("channel", 1),
                )
                .put(
                    "request",
                    JSONObject()
                        .put("model_name", "bigmodel")
                        .put("enable_itn", true)
                        .put("enable_punc", true)
                        .put("result_type", "full"),
                )
                .toString()
                .toByteArray(Charsets.UTF_8)
            // version=1, header size=4; full request + JSON + gzip.
            return frame(byteArrayOf(0x11, 0x10, 0x11, 0x00), gzip(payload))
        }

        private fun audioRequest(audio: ByteArray, isLast: Boolean): ByteString {
            // audio request + (last packet flag if needed) + raw bytes + gzip.
            val messageFlags = if (isLast) 0x22 else 0x20
            return frame(byteArrayOf(0x11, messageFlags.toByte(), 0x01, 0x00), gzip(audio))
        }

        private fun frame(header: ByteArray, compressedPayload: ByteArray): ByteString {
            return ByteBuffer.allocate(header.size + 4 + compressedPayload.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(header)
                .putInt(compressedPayload.size)
                .put(compressedPayload)
                .array()
                .toByteString()
        }

        private fun parseServerResponse(frame: ByteArray): ServerResponse {
            require(frame.size >= 8) { "响应帧过短" }
            val headerSize = (frame[0].toInt() and 0x0F) * 4
            require(headerSize >= 4 && frame.size >= headerSize + 4) { "响应头长度无效" }
            val messageType = (frame[1].toInt() ushr 4) and 0x0F
            val flags = frame[1].toInt() and 0x0F
            val compression = frame[2].toInt() and 0x0F
            var offset = headerSize
            if (flags == FLAG_SEQUENCE || flags == FLAG_FINAL_SEQUENCE) {
                require(frame.size >= offset + 8) { "响应帧缺少 sequence 或 payload 长度" }
                offset += 4 // 服务端 sequence；应用层不需要它。
            }
            if (messageType == MESSAGE_TYPE_ERROR) {
                require(frame.size >= offset + 8) { "错误响应帧不完整" }
                val errorCode = ByteBuffer.wrap(frame, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                offset += 4
                val payload = readPayload(frame, offset, compression)
                val detail = payload.toString(Charsets.UTF_8).ifBlank { "错误码 $errorCode" }
                throw IOException("服务端拒绝语音识别：$detail")
            }
            require(messageType == MESSAGE_TYPE_SERVER_RESPONSE) { "不支持的服务端消息类型 $messageType" }
            val payload = readPayload(frame, offset, compression)
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            val text = json.optJSONObject("result")?.optString("text")?.takeIf(String::isNotBlank)
            return ServerResponse(text, flags == FLAG_FINAL_SEQUENCE)
        }

        private fun readPayload(frame: ByteArray, offset: Int, compression: Int): ByteArray {
            require(frame.size >= offset + 4) { "响应帧缺少 payload 长度" }
            val payloadSize = ByteBuffer.wrap(frame, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            require(payloadSize >= 0 && frame.size >= offset + 4 + payloadSize) { "响应 payload 长度无效" }
            val payload = frame.copyOfRange(offset + 4, offset + 4 + payloadSize)
            return when (compression) {
                COMPRESSION_NONE -> payload
                COMPRESSION_GZIP -> ungzip(payload)
                else -> error("不支持的响应压缩格式 $compression")
            }
        }

        private fun fail(message: String) {
            if (!finished.compareAndSet(false, true)) return
            stopRecorder()
            socket?.cancel()
            if (!stopping.get()) onFailure(message)
        }

        private fun finishSilently() {
            if (!finished.compareAndSet(false, true)) return
            stopRecorder()
            socket?.close(NORMAL_CLOSE_CODE, "finished")
        }

        private fun stopRecorder() {
            val activeRecorder = recorder ?: return
            recorder = null
            runCatching { activeRecorder.stop() }
            activeRecorder.release()
        }

        private data class ServerResponse(val text: String?, val isFinal: Boolean)

        private companion object {
            const val SAMPLE_RATE = 16_000
            const val AUDIO_FRAME_BYTES = SAMPLE_RATE / 5 * 2 // 200ms, PCM 16-bit mono
            const val NORMAL_CLOSE_CODE = 1000
            const val FINAL_RESPONSE_TIMEOUT_MS = 3_000L
            const val MESSAGE_TYPE_SERVER_RESPONSE = 0x09
            const val MESSAGE_TYPE_ERROR = 0x0F
            const val FLAG_SEQUENCE = 0x01
            const val FLAG_FINAL_SEQUENCE = 0x03
            const val COMPRESSION_NONE = 0x00
            const val COMPRESSION_GZIP = 0x01
        }
    }
}

private fun gzip(source: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
    GZIPOutputStream(output).use { it.write(source) }
    output.toByteArray()
}

private fun ungzip(source: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(source)).use { it.readBytes() }
