package com.hiro.codex_android.ui.threads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hiro.codex_android.data.AgentProfile
import com.hiro.codex_android.data.AgentType
import com.hiro.codex_android.data.CodexEvent
import com.hiro.codex_android.data.RepositoryRegistry
import com.hiro.codex_android.data.SettingsStore
import com.hiro.codex_android.data.model.Thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 列表项：会话 + 来源配置。跨服务器 threadId 可能冲突，展示与导航都以 [key] 为准。 */
data class ThreadEntry(
    val profileId: String,
    val profileName: String,
    val agentType: AgentType,
    val thread: Thread,
) {
    val key: String get() = "$profileId:${thread.id}"
}

data class ThreadListUiState(
    val loading: Boolean = false,
    val entries: List<ThreadEntry> = emptyList(),
    /** 启用的配置，供新建会话时选择 */
    val profiles: List<AgentProfile> = emptyList(),
    /** profileId -> 错误信息；部分配置失败不影响其他配置的卡片 */
    val profileErrors: Map<String, String> = emptyMap(),
    /** 操作级错误（删除/归档失败等） */
    val error: String? = null,
)

class ThreadListViewModel(
    private val registry: RepositoryRegistry,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThreadListUiState())
    val uiState: StateFlow<ThreadListUiState> = _uiState.asStateFlow()
    private val locallyWorkingKeys = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            settingsStore.profiles.collect { profiles ->
                _uiState.update { it.copy(profiles = profiles.filter(AgentProfile::enabled)) }
            }
        }
        // 聊天页即使已经不在前台，仓库仍会分发 turn 事件；首页据此即时更新任务卡片状态。
        viewModelScope.launch {
            registry.allEvents.collect { (profileId, event) ->
                when (event) {
                    is CodexEvent.TurnStarted -> {
                        locallyWorkingKeys += key(profileId, event.threadId)
                        updateThreadStatus(profileId, event.threadId, "busy")
                    }
                    is CodexEvent.TurnCompleted -> {
                        locallyWorkingKeys -= key(profileId, event.threadId)
                        updateThreadStatus(profileId, event.threadId, "idle")
                    }
                    is CodexEvent.ThreadReconciled -> replaceThread(profileId, event.thread)
                    // 多端同步：web/其他手机删除或归档后，本机列表即时移除
                    is CodexEvent.ThreadDeleted -> removeLocally(profileId, event.threadId)
                    is CodexEvent.ThreadArchived -> removeLocally(profileId, event.threadId)
                    else -> Unit
                }
            }
        }
    }

    /** 归档（软删除，可恢复）：先本地移除，失败则刷新列表恢复 */
    fun archiveThread(profileId: String, threadId: String) {
        removeLocally(profileId, threadId)
        viewModelScope.launch {
            runCatching { registry.repositoryFor(profileId).archiveThread(threadId) }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "归档失败：${e.message}") }
                    refresh()
                }
        }
    }

    /** 彻底删除（不可恢复）：先本地移除，失败则刷新列表恢复 */
    fun deleteThread(profileId: String, threadId: String) {
        removeLocally(profileId, threadId)
        viewModelScope.launch {
            runCatching { registry.repositoryFor(profileId).deleteThread(threadId) }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "删除失败：${e.message}") }
                    refresh()
                }
        }
    }

    /** 聚合所有启用配置的 §3.4 thread/list，按更新时间倒序混排。 */
    fun refresh() {
        val profiles = _uiState.value.profiles
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val entries = mutableListOf<ThreadEntry>()
            val errors = mutableMapOf<String, String>()
            profiles.forEach { profile ->
                runCatching { registry.repositoryFor(profile.id).listThreads(limit = 20) }
                    .onSuccess { page ->
                        page.data.forEach { thread ->
                            val effective = if (key(profile.id, thread.id) in locallyWorkingKeys) {
                                thread.copy(status = thread.status.copy(type = "busy"))
                            } else {
                                thread
                            }
                            entries += ThreadEntry(profile.id, profile.displayName, profile.type, effective)
                        }
                    }
                    .onFailure { e -> errors[profile.id] = e.message ?: "连接失败" }
            }
            entries.sortByDescending { it.thread.updatedAt }
            _uiState.update { it.copy(loading = false, entries = entries, profileErrors = errors) }
        }
    }

    private fun key(profileId: String, threadId: String) = "$profileId:$threadId"

    private fun updateThreadStatus(profileId: String, threadId: String, status: String) {
        _uiState.update { state ->
            state.copy(entries = state.entries.map { entry ->
                if (entry.key == key(profileId, threadId)) {
                    entry.copy(thread = entry.thread.copy(status = entry.thread.status.copy(type = status)))
                } else {
                    entry
                }
            })
        }
    }

    private fun replaceThread(profileId: String, updated: Thread) {
        _uiState.update { state ->
            state.copy(entries = state.entries.map { entry ->
                if (entry.key == key(profileId, updated.id)) entry.copy(thread = updated) else entry
            })
        }
    }

    private fun removeLocally(profileId: String, threadId: String) {
        locallyWorkingKeys -= key(profileId, threadId)
        _uiState.update { state ->
            state.copy(entries = state.entries.filterNot { it.key == key(profileId, threadId) })
        }
    }

    companion object {
        fun factory(registry: RepositoryRegistry, settingsStore: SettingsStore): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ThreadListViewModel(registry, settingsStore) }
            }
    }
}
