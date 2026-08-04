package com.hiro.codex_android.data

import android.content.Context
import com.hiro.codex_android.data.tts.VolcengineTtsManager

/**
 * 极简服务定位。Repository 由 RepositoryRegistry 按 AgentProfile 动态创建，
 * 配置的增删改会自动释放/重建对应连接。
 */
object ServiceLocator {

    val registry: RepositoryRegistry by lazy { RepositoryRegistry(settingsStore) }
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
