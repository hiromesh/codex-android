package com.hiro.codex_android.ui.threads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hiro.codex_android.data.CodexEvent
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
    private val locallyWorkingThreadIds = mutableSetOf<String>()

    init {
        // 聊天页即使已经不在前台，仓库仍会分发 turn 事件；首页据此即时更新任务卡片状态。
        viewModelScope.launch {
            repo.events.collect { event ->
                when (event) {
                    is CodexEvent.TurnStarted -> {
                        locallyWorkingThreadIds += event.threadId
                        updateThreadStatus(event.threadId, "busy")
                    }
                    is CodexEvent.TurnCompleted -> {
                        locallyWorkingThreadIds -= event.threadId
                        updateThreadStatus(event.threadId, "idle")
                    }
                    is CodexEvent.ThreadReconciled -> replaceThread(event.thread)
                    else -> Unit
                }
            }
        }
    }

    /** §3.4 thread/list */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { repo.listThreads(limit = 15) }
                .onSuccess { page ->
                    val threads = page.data.map { thread ->
                        if (thread.id in locallyWorkingThreadIds) {
                            thread.copy(status = thread.status.copy(type = "busy"))
                        } else {
                            thread
                        }
                    }
                    _uiState.update { it.copy(loading = false, threads = threads) }
                }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message) } }
        }
    }

    private fun updateThreadStatus(threadId: String, status: String) {
        _uiState.update { state ->
            state.copy(threads = state.threads.map { thread ->
                if (thread.id == threadId) thread.copy(status = thread.status.copy(type = status)) else thread
            })
        }
    }

    private fun replaceThread(updated: Thread) {
        _uiState.update { state ->
            state.copy(threads = state.threads.map { thread -> if (thread.id == updated.id) updated else thread })
        }
    }

    companion object {
        fun factory(repo: CodexRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ThreadListViewModel(repo) }
        }
    }
}
