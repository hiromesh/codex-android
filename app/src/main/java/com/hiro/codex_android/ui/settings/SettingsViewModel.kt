package com.hiro.codex_android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hiro.codex_android.data.AgentProfile
import com.hiro.codex_android.data.AgentType
import com.hiro.codex_android.data.AppSettings
import com.hiro.codex_android.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 新增/编辑中的配置草稿；id 为 null 表示新建。 */
data class ProfileDraft(
    val id: String? = null,
    val type: AgentType = AgentType.CODEX,
    val name: String = "",
    val serverUrl: String = AgentType.defaultUrl(AgentType.CODEX),
    val token: String = "",
    val defaultCwd: String = "",
    val enabled: Boolean = true,
)

data class SettingsUiState(
    val profiles: List<AgentProfile> = emptyList(),
    /** 非 null 时展示编辑弹窗 */
    val draft: ProfileDraft? = null,
    val draftError: String? = null,
    val asrUrl: String = AppSettings.DEFAULT_ASR_URL,
    val asrAppKey: String = "",
    val asrAccessKey: String = "",
    val asrResourceId: String = AppSettings.DEFAULT_ASR_RESOURCE_ID,
    val ttsEnabled: Boolean = false,
    val ttsUrl: String = AppSettings.DEFAULT_TTS_URL,
    val ttsApiKey: String = "",
    val ttsResourceId: String = AppSettings.DEFAULT_TTS_RESOURCE_ID,
    val ttsSpeaker: String = AppSettings.DEFAULT_TTS_SPEAKER,
    val ttsSpeechRate: Int = 0,
    val keepScreenOn: Boolean = false,
)

class SettingsViewModel(private val store: SettingsStore) : ViewModel() {

    private val _uiState = MutableStateFlow(
        store.settings.value.let {
            SettingsUiState(
                asrUrl = it.asrUrl,
                asrAppKey = it.asrAppKey,
                asrAccessKey = it.asrAccessKey,
                asrResourceId = it.asrResourceId,
                ttsEnabled = it.ttsEnabled,
                ttsUrl = it.ttsUrl,
                ttsApiKey = it.ttsApiKey,
                ttsResourceId = it.ttsResourceId,
                ttsSpeaker = it.ttsSpeaker,
                ttsSpeechRate = it.ttsSpeechRate,
                keepScreenOn = it.keepScreenOn,
            )
        },
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.profiles.collect { profiles -> _uiState.update { it.copy(profiles = profiles) } }
        }
    }

    // ---- Agent 服务器配置：保存/删除即时生效，不走底部“保存”按钮 ----

    fun startAddProfile() = _uiState.update { it.copy(draft = ProfileDraft(), draftError = null) }

    fun startEditProfile(profile: AgentProfile) = _uiState.update {
        it.copy(
            draft = ProfileDraft(
                id = profile.id,
                type = profile.type,
                name = profile.name,
                serverUrl = profile.serverUrl,
                token = profile.token,
                defaultCwd = profile.defaultCwd,
                enabled = profile.enabled,
            ),
            draftError = null,
        )
    }

    fun dismissDraft() = _uiState.update { it.copy(draft = null, draftError = null) }

    fun updateDraft(transform: (ProfileDraft) -> ProfileDraft) = _uiState.update { state ->
        state.copy(draft = state.draft?.let(transform), draftError = null)
    }

    fun saveDraft() {
        val draft = _uiState.value.draft ?: return
        val url = draft.serverUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(draftError = "服务器地址不能为空") }
            return
        }
        store.saveProfile(
            AgentProfile(
                id = draft.id ?: java.util.UUID.randomUUID().toString(),
                name = draft.name.trim(),
                type = draft.type,
                serverUrl = url,
                token = draft.token.trim(),
                defaultCwd = draft.defaultCwd.trim(),
                enabled = draft.enabled,
            ),
        )
        _uiState.update { it.copy(draft = null, draftError = null) }
    }

    fun deleteDraftProfile() {
        val id = _uiState.value.draft?.id ?: return
        store.deleteProfile(id)
        _uiState.update { it.copy(draft = null, draftError = null) }
    }

    fun toggleProfileEnabled(profile: AgentProfile) =
        store.saveProfile(profile.copy(enabled = !profile.enabled))

    // ---- 全局 ASR/TTS：仍由底部“保存”统一提交 ----

    fun setAsrUrl(url: String) = _uiState.update { it.copy(asrUrl = url) }
    fun setAsrAppKey(key: String) = _uiState.update { it.copy(asrAppKey = key) }
    fun setAsrAccessKey(key: String) = _uiState.update { it.copy(asrAccessKey = key) }
    fun setAsrResourceId(id: String) = _uiState.update { it.copy(asrResourceId = id) }
    fun setTtsEnabled(enabled: Boolean) = _uiState.update { it.copy(ttsEnabled = enabled) }
    fun setTtsUrl(url: String) = _uiState.update { it.copy(ttsUrl = url) }
    fun setTtsApiKey(key: String) = _uiState.update { it.copy(ttsApiKey = key) }
    fun setTtsResourceId(id: String) = _uiState.update { it.copy(ttsResourceId = id) }
    fun setTtsSpeaker(speaker: String) = _uiState.update { it.copy(ttsSpeaker = speaker) }
    fun setTtsSpeechRate(rate: Int) =
        _uiState.update { it.copy(ttsSpeechRate = rate.coerceIn(-50, 100)) }
    fun setKeepScreenOn(enabled: Boolean) = _uiState.update { it.copy(keepScreenOn = enabled) }

    fun save() {
        val s = _uiState.value
        store.save(
            AppSettings(
                asrUrl = s.asrUrl.trim().ifBlank { AppSettings.DEFAULT_ASR_URL },
                asrAppKey = s.asrAppKey.trim(),
                asrAccessKey = s.asrAccessKey.trim(),
                asrResourceId = s.asrResourceId.trim().ifBlank { AppSettings.DEFAULT_ASR_RESOURCE_ID },
                ttsEnabled = s.ttsEnabled,
                ttsUrl = s.ttsUrl.trim().ifBlank { AppSettings.DEFAULT_TTS_URL },
                ttsApiKey = s.ttsApiKey.trim(),
                ttsResourceId = s.ttsResourceId.trim().ifBlank { AppSettings.DEFAULT_TTS_RESOURCE_ID },
                ttsSpeaker = s.ttsSpeaker.trim().ifBlank { AppSettings.DEFAULT_TTS_SPEAKER },
                ttsSpeechRate = s.ttsSpeechRate,
                keepScreenOn = s.keepScreenOn,
            ),
        )
    }

    companion object {
        fun factory(store: SettingsStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(store) }
        }
    }
}
