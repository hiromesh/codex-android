package com.hiro.codex_android.ui.threads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hiro.codex_android.data.CodexRepository
import com.hiro.codex_android.data.model.Thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ThreadListUiState(
    val loading: Boolean = false,
    val threads: List<Thread> = emptyList(),
    val error: String? = null,
)

class ThreadListViewModel(private val repo: CodexRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ThreadListUiState())
    val uiState: StateFlow<ThreadListUiState> = _uiState.asStateFlow()

    /** §3.4 thread/list */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { repo.listThreads(limit = 15) }
                .onSuccess { page -> _uiState.update { it.copy(loading = false, threads = page.data) } }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message) } }
        }
    }

    companion object {
        fun factory(repo: CodexRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ThreadListViewModel(repo) }
        }
    }
}
