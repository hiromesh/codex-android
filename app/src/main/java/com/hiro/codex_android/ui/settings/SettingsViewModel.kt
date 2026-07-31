package com.hiro.codex_android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hiro.codex_android.data.AppSettings
import com.hiro.codex_android.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val serverUrl: String = AppSettings.DEFAULT_SERVER_URL,
    val token: String = "",
    val asrUrl: String = AppSettings.DEFAULT_ASR_URL,
    val asrAppKey: String = "",
    val asrAccessKey: String = "",
    val asrResourceId: String = AppSettings.DEFAULT_ASR_RESOURCE_ID,
    val saved: Boolean = false,
)

class SettingsViewModel(private val store: SettingsStore) : ViewModel() {

    private val _uiState = MutableStateFlow(
        store.settings.value.let {
            SettingsUiState(
                serverUrl = it.serverUrl,
                token = it.token,
                asrUrl = it.asrUrl,
                asrAppKey = it.asrAppKey,
                asrAccessKey = it.asrAccessKey,
                asrResourceId = it.asrResourceId,
            )
        },
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setServerUrl(url: String) = _uiState.update { it.copy(serverUrl = url, saved = false) }
    fun setToken(token: String) = _uiState.update { it.copy(token = token, saved = false) }
    fun setAsrUrl(url: String) = _uiState.update { it.copy(asrUrl = url, saved = false) }
    fun setAsrAppKey(key: String) = _uiState.update { it.copy(asrAppKey = key, saved = false) }
    fun setAsrAccessKey(key: String) = _uiState.update { it.copy(asrAccessKey = key, saved = false) }
    fun setAsrResourceId(id: String) = _uiState.update { it.copy(asrResourceId = id, saved = false) }

    fun save() {
        val s = _uiState.value
        store.save(
            AppSettings(
                serverUrl = s.serverUrl.trim().ifBlank { AppSettings.DEFAULT_SERVER_URL },
                token = s.token.trim(),
                asrUrl = s.asrUrl.trim().ifBlank { AppSettings.DEFAULT_ASR_URL },
                asrAppKey = s.asrAppKey.trim(),
                asrAccessKey = s.asrAccessKey.trim(),
                asrResourceId = s.asrResourceId.trim().ifBlank { AppSettings.DEFAULT_ASR_RESOURCE_ID },
            ),
        )
        _uiState.update { it.copy(saved = true) }
    }

    companion object {
        fun factory(store: SettingsStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(store) }
        }
    }
}
