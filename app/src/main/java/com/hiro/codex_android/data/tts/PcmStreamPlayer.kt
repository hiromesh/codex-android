package com.hiro.codex_android.data.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 单个 TTS session 的 PCM s16le 单声道流式播放器。
 * 音频块到达即写入 [AudioTrack]；[finish] 后会等已排队的音频播完再释放，
 * [interrupt] 则立即静音并释放。每个 session 新建一个实例，不复用。
 */
class PcmStreamPlayer(private val sampleRate: Int) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile private var interrupted = false
    @Volatile private var track: AudioTrack? = null

    init {
        scope.launch { playLoop() }
    }

    fun write(chunk: ByteArray) {
        if (!interrupted && !channel.isClosedForSend) channel.trySend(chunk)
    }

    /** 不再有新音频：播完队列里剩余的部分后自然结束。 */
    fun finish() {
        channel.close()
    }

    /** 立即停止播放（用户打断/开始新提问）。 */
    fun interrupt() {
        interrupted = true
        channel.close()
        // pause/flush/stop 让阻塞中的 write 立刻返回，播放循环随之退出。
        track?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
        }
    }

    private suspend fun playLoop() {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "设备不支持 ${sampleRate}Hz PCM 播放")
            scope.cancel()
            return
        }
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audioTrack
        var framesWritten = 0L
        try {
            audioTrack.play()
            for (chunk in channel) {
                if (interrupted) break
                var offset = 0
                while (offset < chunk.size && !interrupted) {
                    offset += audioTrack.write(chunk, offset, chunk.size - offset)
                }
                framesWritten += chunk.size / 2
            }
            if (!interrupted) {
                // playbackHeadPosition 追平 framesWritten 才算真正播完。
                while (audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL < framesWritten) {
                    delay(30)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "TTS 播放异常：${error.message}")
        } finally {
            runCatching { audioTrack.stop() }
            audioTrack.release()
            track = null
            scope.cancel()
        }
    }

    private companion object {
        const val TAG = "PcmStreamPlayer"
    }
}
