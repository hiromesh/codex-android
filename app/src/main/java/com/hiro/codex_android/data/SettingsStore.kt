package com.hiro.codex_android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** App 级全局配置（语音识别/合成）。Agent 服务器配置见 [AgentProfile]，可有多个。 */
data class AppSettings(
    /** 火山引擎流式 ASR（旧版控制台）的连接配置。 */
    val asrUrl: String = DEFAULT_ASR_URL,
    val asrAppKey: String = "",
    val asrAccessKey: String = "",
    val asrResourceId: String = DEFAULT_ASR_RESOURCE_ID,
    /** 是否启用 TTS：启用后 agent 的回答会流式语音播报（仅回答正文，工具等不读）。 */
    val ttsEnabled: Boolean = false,
    /** 豆包语音合成大模型 V3 双向流式（新版控制台鉴权：X-Api-Key）。 */
    val ttsUrl: String = DEFAULT_TTS_URL,
    val ttsApiKey: String = "",
    val ttsResourceId: String = DEFAULT_TTS_RESOURCE_ID,
    val ttsSpeaker: String = DEFAULT_TTS_SPEAKER,
    /** 语速，取值 [-50, 100]，0 为原速。 */
    val ttsSpeechRate: Int = 0,
) {
    companion object {
        /** 文档推荐的双向流式优化接口，实时返回识别结果。 */
        const val DEFAULT_ASR_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        /** 豆包流式语音识别模型 2.0 小时版；并发版可在设置中替换。 */
        const val DEFAULT_ASR_RESOURCE_ID = "volc.seedasr.sauc.duration"
        /** 文本流式输入、音频流式输出的双向接口，适合直接对接 LLM 的流式回答。 */
        const val DEFAULT_TTS_URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
        /** 豆包语音合成模型 2.0；1.0 音色（mars/moon 系列）需换成 seed-tts-1.0(-concurr)。 */
        const val DEFAULT_TTS_RESOURCE_ID = "seed-tts-2.0"
        /** 2.0 音色为 uranus 系列；与资源 ID 不匹配会被服务端拒绝合成。 */
        const val DEFAULT_TTS_SPEAKER = "zh_female_shuangkuaisisi_uranus_bigtts"
    }
}

/**
 * SharedPreferences 持久化。Agent 服务器配置以 JSON 数组存为多个 profile；
 * 全局 ASR/TTS 仍是平铺 key。Repository 由 RepositoryRegistry 按 profile 创建。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("codex_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<AgentProfile>> = _profiles.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        asrUrl = prefs.getString(KEY_ASR_URL, AppSettings.DEFAULT_ASR_URL) ?: AppSettings.DEFAULT_ASR_URL,
        asrAppKey = prefs.getString(KEY_ASR_APP_KEY, "").orEmpty(),
        asrAccessKey = prefs.getString(KEY_ASR_ACCESS_KEY, "").orEmpty(),
        asrResourceId = prefs.getString(KEY_ASR_RESOURCE_ID, AppSettings.DEFAULT_ASR_RESOURCE_ID)
            ?: AppSettings.DEFAULT_ASR_RESOURCE_ID,
        ttsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, false),
        ttsUrl = prefs.getString(KEY_TTS_URL, AppSettings.DEFAULT_TTS_URL) ?: AppSettings.DEFAULT_TTS_URL,
        ttsApiKey = prefs.getString(KEY_TTS_API_KEY, "").orEmpty(),
        ttsResourceId = prefs.getString(KEY_TTS_RESOURCE_ID, AppSettings.DEFAULT_TTS_RESOURCE_ID)
            ?: AppSettings.DEFAULT_TTS_RESOURCE_ID,
        ttsSpeaker = prefs.getString(KEY_TTS_SPEAKER, AppSettings.DEFAULT_TTS_SPEAKER)
            ?: AppSettings.DEFAULT_TTS_SPEAKER,
        ttsSpeechRate = prefs.getInt(KEY_TTS_SPEECH_RATE, 0),
    )

    /** 首次升级时把旧的单一 serverUrl/token 迁移为一个 codex profile。 */
    private fun loadProfiles(): List<AgentProfile> {
        val json = prefs.getString(KEY_PROFILES, null)
        if (json != null) return parseProfiles(json)
        if (!prefs.contains(KEY_LEGACY_URL) && !prefs.contains(KEY_LEGACY_TOKEN)) return emptyList()
        val migrated = AgentProfile(
            name = "",
            type = AgentType.CODEX,
            serverUrl = prefs.getString(KEY_LEGACY_URL, AgentType.defaultUrl(AgentType.CODEX))
                ?: AgentType.defaultUrl(AgentType.CODEX),
            token = prefs.getString(KEY_LEGACY_TOKEN, "").orEmpty(),
        )
        prefs.edit()
            .remove(KEY_LEGACY_URL)
            .remove(KEY_LEGACY_TOKEN)
            .putString(KEY_PROFILES, profilesToJson(listOf(migrated)))
            .apply()
        return listOf(migrated)
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_ASR_URL, settings.asrUrl)
            .putString(KEY_ASR_APP_KEY, settings.asrAppKey)
            .putString(KEY_ASR_ACCESS_KEY, settings.asrAccessKey)
            .putString(KEY_ASR_RESOURCE_ID, settings.asrResourceId)
            .putBoolean(KEY_TTS_ENABLED, settings.ttsEnabled)
            .putString(KEY_TTS_URL, settings.ttsUrl)
            .putString(KEY_TTS_API_KEY, settings.ttsApiKey)
            .putString(KEY_TTS_RESOURCE_ID, settings.ttsResourceId)
            .putString(KEY_TTS_SPEAKER, settings.ttsSpeaker)
            .putInt(KEY_TTS_SPEECH_RATE, settings.ttsSpeechRate)
            .apply()
        _settings.value = settings
    }

    /** 按 id upsert。 */
    fun saveProfile(profile: AgentProfile) {
        val updated = _profiles.value.filterNot { it.id == profile.id } + profile
        persistProfiles(updated)
    }

    fun deleteProfile(profileId: String) {
        persistProfiles(_profiles.value.filterNot { it.id == profileId })
    }

    private fun persistProfiles(profiles: List<AgentProfile>) {
        prefs.edit().putString(KEY_PROFILES, profilesToJson(profiles)).apply()
        _profiles.value = profiles
    }

    private fun parseProfiles(json: String): List<AgentProfile> = try {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                add(
                    AgentProfile(
                        id = id,
                        name = obj.optString("name"),
                        type = AgentType.fromWireValue(obj.optString("type")),
                        serverUrl = obj.optString("serverUrl"),
                        token = obj.optString("token"),
                        defaultCwd = obj.optString("defaultCwd"),
                        enabled = obj.optBoolean("enabled", true),
                    ),
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun profilesToJson(profiles: List<AgentProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("type", profile.type.wireValue)
                    .put("serverUrl", profile.serverUrl)
                    .put("token", profile.token)
                    .put("defaultCwd", profile.defaultCwd)
                    .put("enabled", profile.enabled),
            )
        }
        return array.toString()
    }

    private companion object {
        const val KEY_PROFILES = "agentProfiles"
        const val KEY_LEGACY_URL = "serverUrl"
        const val KEY_LEGACY_TOKEN = "token"
        const val KEY_ASR_URL = "asrUrl"
        const val KEY_ASR_APP_KEY = "asrAppKey"
        const val KEY_ASR_ACCESS_KEY = "asrAccessKey"
        const val KEY_ASR_RESOURCE_ID = "asrResourceId"
        const val KEY_TTS_ENABLED = "ttsEnabled"
        const val KEY_TTS_URL = "ttsUrl"
        const val KEY_TTS_API_KEY = "ttsApiKey"
        const val KEY_TTS_RESOURCE_ID = "ttsResourceId"
        const val KEY_TTS_SPEAKER = "ttsSpeaker"
        const val KEY_TTS_SPEECH_RATE = "ttsSpeechRate"
    }
}
