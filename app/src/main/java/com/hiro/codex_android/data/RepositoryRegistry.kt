package com.hiro.codex_android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** 带上来源 profile 的全局事件，聚合列表据此区分会话归属。 */
data class ProfiledEvent(val profileId: String, val event: CodexEvent)

/**
 * 按 [AgentProfile] 管理 repository 的创建与销毁。
 *
 * repository 按需创建并缓存；profile 被删除、停用或连接信息（类型/地址/token）
 * 变化时，旧实例 close 后丢弃，下次使用时按新配置重建。事件流合并为
 * [allEvents]，每个事件都带来源 profileId。
 */
class RepositoryRegistry(private val settingsStore: SettingsStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val repositories = mutableMapOf<String, CodexRepository>()
    private val connectionKeys = mutableMapOf<String, String>()
    private val eventJobs = mutableMapOf<String, Job>()

    private val _allEvents = MutableSharedFlow<ProfiledEvent>(extraBufferCapacity = 256)
    val allEvents: SharedFlow<ProfiledEvent> = _allEvents.asSharedFlow()

    init {
        scope.launch {
            settingsStore.profiles.collect(::reconcile)
        }
    }

    /**
     * 取某个 profile 的 repository，不存在则按类型创建。
     * @throws IllegalStateException 配置不存在/已停用，或该 Agent 类型暂未支持。
     */
    fun repositoryFor(profileId: String): CodexRepository {
        val profile = settingsStore.profiles.value.firstOrNull { it.id == profileId && it.enabled }
            ?: throw IllegalStateException("配置不存在或已停用")
        synchronized(lock) {
            repositories[profileId]?.let { return it }
            val repo = createRepository(profile)
            repositories[profileId] = repo
            connectionKeys[profileId] = profile.connectionKey()
            eventJobs[profileId] = scope.launch {
                repo.events.collect { event -> _allEvents.emit(ProfiledEvent(profileId, event)) }
            }
            return repo
        }
    }

    private fun createRepository(profile: AgentProfile): CodexRepository = when (profile.type) {
        AgentType.CODEX -> WebSocketCodexRepository(profile)
        AgentType.KIMI -> KimiCodexRepository(profile)
        AgentType.CLAUDE -> ClaudeCodexRepository(profile)
        else -> throw IllegalStateException("${profile.type.displayName} 暂未支持")
    }

    /** 停用/删除/改连接的 profile，其 repository 立即释放。 */
    private fun reconcile(profiles: List<AgentProfile>) {
        val active = profiles.filter { it.enabled }.associateBy { it.id }
        synchronized(lock) {
            val stale = repositories.keys.filter { id ->
                val profile = active[id]
                profile == null || profile.connectionKey() != connectionKeys[id]
            }
            stale.forEach { id ->
                eventJobs.remove(id)?.cancel()
                repositories.remove(id)?.let { repo -> runCatching { repo.close() } }
                connectionKeys.remove(id)
            }
        }
    }
}
