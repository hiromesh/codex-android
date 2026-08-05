package com.hiro.codex_android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hiro.codex_android.data.CodexEvent
import com.hiro.codex_android.data.CodexRepository
import com.hiro.codex_android.data.SettingsStore
import com.hiro.codex_android.data.StreamingAsrClient
import com.hiro.codex_android.data.model.ApprovalDecision
import com.hiro.codex_android.data.model.Content
import com.hiro.codex_android.data.model.ModelInfo
import com.hiro.codex_android.data.model.ReviewTarget
import com.hiro.codex_android.data.model.ThreadItem
import com.hiro.codex_android.data.model.TokenUsage
import com.hiro.codex_android.data.model.Thread
import com.hiro.codex_android.data.model.Turn
import com.hiro.codex_android.data.tts.VolcengineTtsManager
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 需要用户二次确认/选择的动作弹窗。 */
sealed interface PendingActionPrompt {
    data object ReviewTarget : PendingActionPrompt
    data class ConfirmUndo(val numTurns: Int) : PendingActionPrompt
    data class ConfirmShell(val command: String) : PendingActionPrompt
}

data class ChatUiState(
    /** null 表示新会话，发第一条消息时才真正 thread/start */
    val threadId: String? = null,
    val title: String = "新会话",
    val model: String = "gpt-5.6-terra",
    val effort: String = "medium",
    val items: List<ThreadItem> = emptyList(),
    val loading: Boolean = false,
    val generating: Boolean = false,
    /** 斜杠动作进行中（compact/fork 等），禁用连点与发消息。 */
    val actionBusy: Boolean = false,
    val currentTurnId: String? = null,
    val pendingApproval: CodexEvent.ApprovalRequest? = null,
    val pendingActionPrompt: PendingActionPrompt? = null,
    val availableModels: List<ModelInfo> = emptyList(),
    /** 上下文占用，来自 thread/tokenUsage/updated */
    val tokenUsage: TokenUsage? = null,
    /** 本次语音会话的服务端全文结果；输入框以它覆盖上一次临时转写，避免重复拼接。 */
    val asrTranscript: String? = null,
    val asrRecording: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(
    initialThreadId: String?,
    private val repo: CodexRepository,
    private val settingsStore: SettingsStore,
    private val streamingAsrClient: StreamingAsrClient,
    private val ttsManager: VolcengineTtsManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(threadId = initialThreadId))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    /** /fork 成功后通知 UI 跳到新会话（一次性）。 */
    private val _openThread = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openThread: SharedFlow<String> = _openThread.asSharedFlow()
    private var turnWatchdog: Job? = null
    private var asrSession: StreamingAsrClient.Session? = null
    private var asrSessionId: String? = null

    init {
        if (initialThreadId != null) loadThread(initialThreadId)
        // §8.2：订阅全局事件流，按 threadId 过滤
        viewModelScope.launch { repo.events.collect(::handleEvent) }
        // §3.8：模型选择器数据；当前模型不在列表里时改用服务端默认（避免新会话显示 Codex 占位名）。
        viewModelScope.launch {
            runCatching { repo.listModels() }
                .onSuccess { models ->
                    _uiState.update { state ->
                        val preferred = models.firstOrNull { it.isDefault } ?: models.firstOrNull()
                        val modelId = if (models.any { it.id == state.model }) {
                            state.model
                        } else {
                            preferred?.id ?: state.model
                        }
                        val modelInfo = models.firstOrNull { it.id == modelId } ?: preferred
                        state.copy(
                            availableModels = models,
                            model = modelId,
                            effort = resolveEffort(modelInfo, state.effort),
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * 进入已有会话时，先用 §3.3 thread/resume 把 session 挂到当前 WebSocket，
     * 再用 §3.5 thread/read 拉完整历史。只 read 会导致后续 turn/start 找不到 session。
     */
    private fun loadThread(threadId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            runCatching {
                val resumed = repo.resumeThread(threadId)
                val loaded = repo.readThread(threadId, includeTurns = true)
                // 少数旧服务端的 read 响应不带会话设置，保留 resume 的返回。
                loaded.copy(
                    model = loaded.model ?: resumed.model,
                    effort = loaded.effort ?: resumed.effort,
                )
            }.onSuccess { thread ->
                    _uiState.update {
                        val modelId = thread.model ?: it.model
                        val modelInfo = it.availableModels.firstOrNull { m -> m.id == modelId }
                        it.copy(
                            loading = false,
                            title = thread.name ?: thread.preview.ifBlank { "会话" },
                            model = modelId,
                            effort = resolveEffort(modelInfo, thread.effort ?: it.effort),
                            items = thread.turns.flatMap(Turn::items),
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message) } }
        }
    }

    private fun handleEvent(event: CodexEvent) {
        val threadId = _uiState.value.threadId ?: return
        if (event.threadId != threadId) return
        when (event) {
            is CodexEvent.TurnStarted ->
                _uiState.update { it.copy(generating = true, currentTurnId = event.turnId) }

            is CodexEvent.ItemStarted -> {
                val item = when (val started = event.item) {
                    // 以事件为准：started = 压缩进行中，避免服务端缺 status 时误显示「已压缩」。
                    is ThreadItem.ContextCompaction -> started.copy(status = "inProgress")
                    else -> started
                }
                appendItem(item)
            }

            // TTS 只朗读回答正文：正文 delta 边到边合成，工具/思考等其他事件不喂。
            is CodexEvent.AgentMessageDelta -> {
                appendDelta(event.itemId, event.delta)
                ttsManager.onAgentDelta(event.delta)
            }

            is CodexEvent.ReasoningSummaryDelta ->
                appendReasoningDelta(event.itemId, event.summaryIndex, event.delta)

            // item/completed 里是完整 item，直接替换以校对（§4 处理要点）
            is CodexEvent.ItemCompleted -> {
                val item = when (val completed = event.item) {
                    is ThreadItem.ContextCompaction -> completed.copy(status = "completed")
                    else -> completed
                }
                replaceItem(item)
                if (item is ThreadItem.AgentMessage) ttsManager.onAgentMessageFinished()
            }

            is CodexEvent.TurnCompleted -> {
                if (event.status == "completed") {
                    // 兜底：个别服务端可能漏发 agentMessage 的 item/completed。
                    ttsManager.onAgentMessageFinished()
                } else {
                    ttsManager.stop()
                }
                _uiState.update {
                    it.copy(
                        generating = false,
                        currentTurnId = null,
                        pendingApproval = null,
                        error = event.error,
                    )
                }
            }

            is CodexEvent.TokenUsageUpdated ->
                _uiState.update { it.copy(tokenUsage = event.usage) }

            is CodexEvent.ThreadReconciled -> applyServerThread(event.thread)

            // 多端同步：当前会话被 web/其他设备删除或归档
            is CodexEvent.ThreadDeleted -> {
                ttsManager.stop()
                _uiState.update {
                    it.copy(
                        generating = false,
                        currentTurnId = null,
                        pendingApproval = null,
                        error = "会话已被其他设备删除",
                    )
                }
            }

            is CodexEvent.ThreadArchived -> {
                ttsManager.stop()
                _uiState.update {
                    it.copy(
                        generating = false,
                        currentTurnId = null,
                        pendingApproval = null,
                        error = "会话已被其他设备归档",
                    )
                }
            }

            is CodexEvent.ApprovalRequest ->
                _uiState.update { it.copy(pendingApproval = event) }
        }
    }

    /**
     * 输入框发送入口：命中 `/compact` `/review` `/fork` `/undo` 或 `!cmd` 时走动作接口，
     * 不插入用户气泡、不触发 turn/start；其余仍按普通对话发送。
     */
    fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        when (val action = parseChatAction(trimmed)) {
            null -> send(trimmed)
            else -> dispatchAction(action)
        }
    }

    fun dismissActionPrompt() {
        _uiState.update { it.copy(pendingActionPrompt = null) }
    }

    fun confirmReview(target: ReviewTarget) {
        _uiState.update { it.copy(pendingActionPrompt = null) }
        runAction("审查") { threadId ->
            val result = repo.startReview(threadId, target)
            _uiState.update {
                it.copy(generating = true, currentTurnId = result.turn.id, error = null)
            }
            startTurnWatchdog(result.reviewThreadId)
        }
    }

    fun confirmUndo(numTurns: Int) {
        _uiState.update { it.copy(pendingActionPrompt = null) }
        runAction("撤销") { threadId ->
            val thread = repo.rollbackThread(threadId, numTurns)
            applyServerThread(thread)
        }
    }

    fun confirmShell(command: String) {
        _uiState.update { it.copy(pendingActionPrompt = null) }
        runAction("Shell") { threadId ->
            repo.shellCommand(threadId, command)
        }
    }

    private fun dispatchAction(action: ParsedChatAction) {
        val state = _uiState.value
        if (state.generating || state.actionBusy) return
        when (action) {
            ParsedChatAction.Compact -> runAction("压缩") { threadId ->
                repo.startCompact(threadId)
            }
            ParsedChatAction.ReviewNeedTarget ->
                _uiState.update { it.copy(pendingActionPrompt = PendingActionPrompt.ReviewTarget) }
            is ParsedChatAction.Review -> confirmReview(action.target)
            ParsedChatAction.Fork -> runAction("分叉") { threadId ->
                val forked = repo.forkThread(threadId)
                _openThread.emit(forked.id)
            }
            is ParsedChatAction.Undo ->
                _uiState.update {
                    it.copy(pendingActionPrompt = PendingActionPrompt.ConfirmUndo(action.numTurns))
                }
            is ParsedChatAction.Shell ->
                _uiState.update {
                    it.copy(pendingActionPrompt = PendingActionPrompt.ConfirmShell(action.command))
                }
        }
    }

    private fun runAction(label: String, block: suspend (threadId: String) -> Unit) {
        val threadId = _uiState.value.threadId
        if (threadId == null) {
            _uiState.update { it.copy(error = "请先发送一条消息创建会话，再使用 $label") }
            return
        }
        if (_uiState.value.actionBusy || _uiState.value.generating) return
        ttsManager.stop()
        viewModelScope.launch {
            _uiState.update { it.copy(actionBusy = true, error = null) }
            runCatching { block(threadId) }
                .onFailure { e -> _uiState.update { it.copy(error = "$label 失败：${e.message}") } }
            _uiState.update { it.copy(actionBusy = false) }
        }
    }

    /** §3.6 turn/start */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.generating || _uiState.value.actionBusy) return
        ttsManager.stop()
        viewModelScope.launch {
            var submittedThreadId: String? = null
            appendItem(ThreadItem.UserMessage(localId(), listOf(Content("text", trimmed))))
            _uiState.update { it.copy(generating = true, error = null) }
            try {
                val existingId = _uiState.value.threadId
                val threadId = if (existingId != null) {
                    existingId
                } else {
                    // 新会话：第一条消息时才真正 thread/start（§3.2）；
                    // thread/start 只带模型；effort 在建会话后、首条 turn 前写入设置。
                    val selection = _uiState.value
                    val thread = repo.startThread(selection.model.ifBlank { null })
                    // 建会话后把当前 UI 上的 model + effort 一并写入；Kimi 的 create 本身不生效。
                    repo.updateThreadSettings(
                        thread.id,
                        model = selection.model.ifBlank { thread.model },
                        effort = selection.effort.ifBlank { null },
                    )
                    _uiState.update {
                        it.copy(
                            threadId = thread.id,
                            model = thread.model ?: it.model,
                            title = trimmed.take(20),
                        )
                    }
                    thread.id
                }
                submittedThreadId = threadId
                startTurnWatchdog(threadId)
                val turn = repo.startTurn(threadId, listOf(Content("text", trimmed)))
                _uiState.update {
                    // 正常情况会由 turn/started 通知填入；如果通知稍晚，用 RPC 结果兜底。
                    if (it.generating && it.currentTurnId == null) it.copy(currentTurnId = turn.id) else it
                }
            } catch (e: Exception) {
                // 超时/断线时服务端可能已经收到 turn/start，不能直接把它当成失败。
                // watchdog 会 read 全量会话，确认服务端最终状态。
                if (submittedThreadId != null && !e.message.orEmpty().startsWith("Codex 协议错误")) {
                    _uiState.update { it.copy(error = "连接中断，正在同步会话…") }
                } else {
                    turnWatchdog?.cancel()
                    _uiState.update { it.copy(generating = false, currentTurnId = null, error = e.message) }
                }
            }
        }
    }

    /** §3.7 turn/interrupt */
    fun interrupt() {
        val state = _uiState.value
        val threadId = state.threadId ?: return
        val turnId = state.currentTurnId ?: return
        ttsManager.stop()
        viewModelScope.launch {
            runCatching { repo.interruptTurn(threadId, turnId) }
                .onFailure { e -> _uiState.update { it.copy(error = "中断失败：${e.message}") } }
        }
    }

    /** 开始将麦克风 PCM 以 200ms 分包发送至 ASR；权限由界面层在调用前申请。 */
    fun startAsr() {
        if (_uiState.value.asrRecording) return
        val sessionId = UUID.randomUUID().toString()
        asrSessionId = sessionId
        try {
            asrSession = streamingAsrClient.start(
                settings = settingsStore.settings.value,
                onTranscript = { text ->
                    if (asrSessionId == sessionId) {
                        _uiState.update { it.copy(asrTranscript = text) }
                    }
                },
                onFailure = { message ->
                    if (asrSessionId == sessionId) {
                        asrSession = null
                        asrSessionId = null
                        _uiState.update { it.copy(asrRecording = false, error = message) }
                    }
                },
            )
            _uiState.update { it.copy(asrRecording = true, asrTranscript = null, error = null) }
        } catch (error: IllegalArgumentException) {
            asrSessionId = null
            _uiState.update { it.copy(error = error.message) }
        }
    }

    /** 立即停麦克风并发送 ASR 协议的最后一包，最终文本仍可在短暂回包后写入输入框。 */
    fun stopAsr() {
        val activeSession = asrSession ?: return
        asrSession = null
        _uiState.update { it.copy(asrRecording = false) }
        activeSession.stop()
    }

    fun reportError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    /** 从锁屏/后台回来时主动对账；后台期间 WebSocket 的通知不保证能保活或重放。 */
    fun reconcileAfterForeground() {
        val threadId = _uiState.value.threadId ?: return
        viewModelScope.launch {
            runCatching {
                val resumed = repo.resumeThread(threadId)
                val loaded = repo.readThread(threadId, includeTurns = true)
                loaded.copy(
                    model = loaded.model ?: resumed.model,
                    effort = loaded.effort ?: resumed.effort,
                )
            }.onSuccess(::applyServerThread)
        }
    }

    /**
     * 除断线重连外，再用低频读全量兜住“socket 还活着但某次通知没有到 UI”的情况。
     * 长任务可以数分钟，因此只要服务端仍标记 inProgress 就继续等待而不报假失败。
     */
    private fun startTurnWatchdog(threadId: String) {
        turnWatchdog?.cancel()
        turnWatchdog = viewModelScope.launch {
            delay(TURN_RECONCILE_INTERVAL_MS)
            while (isActive && _uiState.value.threadId == threadId && _uiState.value.generating) {
                runCatching { repo.readThread(threadId, includeTurns = true) }
                    .onSuccess(::applyServerThread)
                delay(TURN_RECONCILE_INTERVAL_MS)
            }
        }
    }

    /** §6 审批应答 */
    fun respondApproval(decision: ApprovalDecision) {
        val request = _uiState.value.pendingApproval ?: return
        _uiState.update { it.copy(pendingApproval = null) }
        viewModelScope.launch {
            runCatching { repo.respondApproval(request.requestId, decision) }
                // 应答失败时恢复弹窗让用户重试；否则审批在服务端永远挂起。
                .onFailure { e ->
                    _uiState.update {
                        it.copy(pendingApproval = request, error = "审批应答失败：${e.message}")
                    }
                }
        }
    }

    /** §3.9② thread/settings/update：模型与推理档位同属会话级设置。 */
    fun switchConfiguration(modelId: String, effort: String) {
        val threadId = _uiState.value.threadId
        _uiState.update { it.copy(model = modelId, effort = effort) }
        if (threadId != null) {
            viewModelScope.launch {
                runCatching { repo.updateThreadSettings(threadId, model = modelId, effort = effort) }
                    .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            }
        }
        // 新会话还没建：只记本地状态，send() 会在建立后写入 effort。
    }

    private fun appendItem(item: ThreadItem) {
        _uiState.update { state ->
            // reasoning 的 item/started 通常没有摘要；仅在服务端真的给出内容后展示。
            if (item is ThreadItem.Reasoning && item.summary.isEmpty()) return@update state
            // 发送时先插入 local-* 气泡；服务端随后会回传同一 userMessage。
            // 用内容匹配并替换为服务端 itemId，防止同一条消息显示两遍。
            if (item is ThreadItem.UserMessage) {
                val localIndex = state.items.indexOfLast { existing ->
                    existing is ThreadItem.UserMessage &&
                        existing.id.startsWith("local-") &&
                        existing.content == item.content
                }
                if (localIndex >= 0) {
                    state.copy(items = state.items.toMutableList().apply { set(localIndex, item) })
                } else {
                    state.copy(items = state.items + item)
                }
            } else {
                state.copy(items = state.items + item)
            }
        }
    }

    private fun appendDelta(itemId: String, delta: String) {
        _uiState.update { state ->
            val index = state.items.indexOfFirst { it.id == itemId }
            val target = state.items.getOrNull(index)
            if (target is ThreadItem.AgentMessage) {
                state.copy(
                    items = state.items.toMutableList().apply {
                        set(index, target.copy(text = target.text + delta))
                    },
                )
            } else {
                state.copy(items = state.items + ThreadItem.AgentMessage(itemId, delta))
            }
        }
    }

    /** §5.1：按 summaryIndex 为 reasoning 摘要分段，逐个 delta 追加。 */
    private fun appendReasoningDelta(itemId: String, summaryIndex: Int, delta: String) {
        _uiState.update { state ->
            val index = state.items.indexOfFirst { it.id == itemId }
            val existing = state.items.getOrNull(index) as? ThreadItem.Reasoning
            val summary = existing?.summary.orEmpty().toMutableList()
            val safeIndex = summaryIndex.coerceAtLeast(0)
            while (summary.size <= safeIndex) summary += ""
            summary[safeIndex] += delta
            val updated = ThreadItem.Reasoning(itemId, summary)
            if (index >= 0) {
                state.copy(items = state.items.toMutableList().apply { set(index, updated) })
            } else {
                state.copy(items = state.items + updated)
            }
        }
    }

    private fun replaceItem(item: ThreadItem) {
        _uiState.update { state ->
            val index = state.items.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                val existing = state.items[index]
                val merged = when {
                    // completed 可能只带 output/status；保留 started 时的工具名，避免被 id 盖掉。
                    item is ThreadItem.CommandExecution && existing is ThreadItem.CommandExecution -> {
                        item.copy(
                            command = item.command.takeIf { it.isNotBlank() && it != item.id }
                                ?: existing.command,
                            cwd = item.cwd.ifBlank { existing.cwd },
                            aggregatedOutput = item.aggregatedOutput.ifBlank { existing.aggregatedOutput },
                            exitCode = item.exitCode ?: existing.exitCode,
                            durationMs = item.durationMs ?: existing.durationMs,
                        )
                    }
                    else -> item
                }
                state.copy(items = state.items.toMutableList().apply { set(index, merged) })
            } else if (item is ThreadItem.Reasoning && item.summary.isEmpty()) {
                // 默认未开启摘要时不会产生空的“思考过程”折叠项。
                state
            } else if (item is ThreadItem.UserMessage) {
                // 某些服务端只发 item/completed，不发 userMessage 的 item/started。
                val localIndex = state.items.indexOfLast { existing ->
                    existing is ThreadItem.UserMessage &&
                        existing.id.startsWith("local-") &&
                        existing.content == item.content
                }
                if (localIndex >= 0) {
                    state.copy(items = state.items.toMutableList().apply { set(localIndex, item) })
                } else {
                    state.copy(items = state.items + item)
                }
            } else {
                state.copy(items = state.items + item)
            }
        }
    }

    /**
     * 服务端快照对账。轮次仍在进行时，本地流式增量比服务端快照新
     * （delta 不落盘，read 返回的进行中 item 可能为空或偏短），只更新元数据，
     * 不覆盖 items，也不动 pendingApproval；轮次已结束时快照是最终真相，全量替换。
     */
    private fun applyServerThread(thread: Thread) {
        val activeTurn = thread.turns.lastOrNull { it.status == "inProgress" }
        val lastError = thread.turns.lastOrNull()?.takeIf { it.status == "failed" }?.error
        val incomingItems = thread.turns.flatMap(Turn::items)
        // 对账若未带 items（Kimi snapshot 元数据同步），保留本地气泡，避免直播被残缺历史盖掉。
        val replaceItems = incomingItems.isNotEmpty()
        _uiState.update { state ->
            if (activeTurn != null) {
                val modelId = thread.model ?: state.model
                val modelInfo = state.availableModels.firstOrNull { it.id == modelId }
                state.copy(
                    title = thread.name ?: thread.preview.ifBlank { state.title },
                    model = modelId,
                    effort = resolveEffort(modelInfo, thread.effort ?: state.effort),
                    generating = true,
                    currentTurnId = activeTurn.id,
                )
            } else {
                val modelId = thread.model ?: state.model
                val modelInfo = state.availableModels.firstOrNull { it.id == modelId }
                state.copy(
                    title = thread.name ?: thread.preview.ifBlank { state.title },
                    model = modelId,
                    effort = resolveEffort(modelInfo, thread.effort ?: state.effort),
                    items = if (replaceItems) incomingItems else state.items,
                    generating = false,
                    currentTurnId = null,
                    // 轮次结束时不可能有待应答审批（服务端在等应答就不会结束轮次），可安全清除。
                    pendingApproval = null,
                    error = lastError,
                )
            }
        }
    }

    private fun localId(): String = "local-${UUID.randomUUID()}"

    /**
     * 把当前 effort 对齐到模型支持的档位：空值、或不在 support 列表里时，改用模型默认/第一项。
     * Kimi 常见 support 不含 medium，而 UI 初始是 medium，不夹会显示对不上勾选。
     */
    private fun resolveEffort(model: ModelInfo?, effort: String?): String {
        val supported = model?.supportedReasoningEfforts.orEmpty()
        val current = effort?.takeIf { it.isNotBlank() }
        if (current != null && (supported.isEmpty() || current in supported)) return current
        val fallback = model?.defaultReasoningEffort?.takeIf { it.isNotBlank() }
        if (fallback != null && (supported.isEmpty() || fallback in supported)) return fallback
        return supported.firstOrNull() ?: current ?: "medium"
    }

    override fun onCleared() {
        stopAsr()
        ttsManager.stop()
        turnWatchdog?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TURN_RECONCILE_INTERVAL_MS = 15_000L

        fun factory(
            threadId: String?,
            repo: CodexRepository,
            settingsStore: SettingsStore,
            streamingAsrClient: StreamingAsrClient,
            ttsManager: VolcengineTtsManager,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(threadId, repo, settingsStore, streamingAsrClient, ttsManager) }
        }
    }
}
