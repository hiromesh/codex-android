package com.hiro.codex_android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App 级配置，对应文档 §0 的连接信息。模型是会话级设置（§3.9），不放这里 */
data class AppSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val token: String = "",
) {
    companion object {
        /** §0 生产地址（8443 规避未备案域名 80/443 拦截） */
        const val DEFAULT_SERVER_URL = "wss://codex.waibozishu.com:8443"
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
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_URL, settings.serverUrl)
            .putString(KEY_TOKEN, settings.token)
            .apply()
        _settings.value = settings
    }

    private companion object {
        const val KEY_URL = "serverUrl"
        const val KEY_TOKEN = "token"
    }
}
