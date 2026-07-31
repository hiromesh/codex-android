package com.hiro.codex_android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App 级配置，对应文档 §0 的连接信息。模型是会话级设置（§3.9），不放这里 */
data class AppSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val token: String = "",
    /** 火山引擎流式 ASR（旧版控制台）的连接配置。 */
    val asrUrl: String = DEFAULT_ASR_URL,
    val asrAppKey: String = "",
    val asrAccessKey: String = "",
    val asrResourceId: String = DEFAULT_ASR_RESOURCE_ID,
) {
    companion object {
        /** §0 生产地址（8443 规避未备案域名 80/443 拦截） */
        const val DEFAULT_SERVER_URL = "wss://codex.waibozishu.com:8443"
        /** 文档推荐的双向流式优化接口，实时返回识别结果。 */
        const val DEFAULT_ASR_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        /** 豆包流式语音识别模型 2.0 小时版；并发版可在设置中替换。 */
        const val DEFAULT_ASR_RESOURCE_ID = "volc.seedasr.sauc.duration"
    }
}

/** SharedPreferences 持久化，接入后端时 Repository 从这里读地址和 token */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("codex_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        serverUrl = prefs.getString(KEY_URL, AppSettings.DEFAULT_SERVER_URL) ?: AppSettings.DEFAULT_SERVER_URL,
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
        asrUrl = prefs.getString(KEY_ASR_URL, AppSettings.DEFAULT_ASR_URL) ?: AppSettings.DEFAULT_ASR_URL,
        asrAppKey = prefs.getString(KEY_ASR_APP_KEY, "").orEmpty(),
        asrAccessKey = prefs.getString(KEY_ASR_ACCESS_KEY, "").orEmpty(),
        asrResourceId = prefs.getString(KEY_ASR_RESOURCE_ID, AppSettings.DEFAULT_ASR_RESOURCE_ID)
            ?: AppSettings.DEFAULT_ASR_RESOURCE_ID,
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_URL, settings.serverUrl)
            .putString(KEY_TOKEN, settings.token)
            .putString(KEY_ASR_URL, settings.asrUrl)
            .putString(KEY_ASR_APP_KEY, settings.asrAppKey)
            .putString(KEY_ASR_ACCESS_KEY, settings.asrAccessKey)
            .putString(KEY_ASR_RESOURCE_ID, settings.asrResourceId)
            .apply()
        _settings.value = settings
    }

    private companion object {
        const val KEY_URL = "serverUrl"
        const val KEY_TOKEN = "token"
        const val KEY_ASR_URL = "asrUrl"
        const val KEY_ASR_APP_KEY = "asrAppKey"
        const val KEY_ASR_ACCESS_KEY = "asrAccessKey"
        const val KEY_ASR_RESOURCE_ID = "asrResourceId"
    }
}
