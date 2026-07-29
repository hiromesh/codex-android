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
    val saved: Boolean = false,
)

class SettingsViewModel(private val store: SettingsStore) : ViewModel() {

    private val _uiState = MutableStateFlow(
        store.settings.value.let { SettingsUiState(serverUrl = it.serverUrl, token = it.token) },
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setServerUrl(url: String) = _uiState.update { it.copy(serverUrl = url, saved = false) }
    fun setToken(token: String) = _uiState.update { it.copy(token = token, saved = false) }

    fun save() {
        val s = _uiState.value
        store.save(
            AppSettings(
                serverUrl = s.serverUrl.trim().ifBlank { AppSettings.DEFAULT_SERVER_URL },
                token = s.token.trim(),
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
