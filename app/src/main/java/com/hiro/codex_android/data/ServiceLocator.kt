package com.hiro.codex_android.data

import android.content.Context
import com.hiro.codex_android.data.tts.VolcengineTtsManager

/**
 * 极简服务定位。Repository 通过 SettingsStore 读取服务器地址和 token，
 * 因而设置保存后下一次 RPC 会自动使用新连接配置。
 */
object ServiceLocator {

    val repository: CodexRepository by lazy { WebSocketCodexRepository(settingsStore) }
    val streamingAsrClient: StreamingAsrClient by lazy { StreamingAsrClient() }
    val ttsManager: VolcengineTtsManager by lazy { VolcengineTtsManager(settingsStore) }

    lateinit var settingsStore: SettingsStore
        private set

    fun init(context: Context) {
        if (!::settingsStore.isInitialized) {
            settingsStore = SettingsStore(context.applicationContext)
        }
    }
}
