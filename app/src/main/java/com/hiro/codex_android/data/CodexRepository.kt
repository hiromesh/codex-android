package com.hiro.codex_android.data

import com.hiro.codex_android.data.model.ApprovalDecision
import com.hiro.codex_android.data.model.Content
import com.hiro.codex_android.data.model.ModelInfo
import com.hiro.codex_android.data.model.Thread
import com.hiro.codex_android.data.model.ThreadItem
import com.hiro.codex_android.data.model.TokenUsage
import com.hiro.codex_android.data.model.Turn
import kotlinx.coroutines.flow.SharedFlow

/**
 * 服务端 → 客户端事件（通知 + 反向请求），对应文档 §4 / §6。
 * 真实实现里由 WebSocket reader 协程按 method 分发成这些事件。
 */
sealed interface CodexEvent {
    val threadId: String

    /** turn/started：一轮开始 */
    data class TurnStarted(override val threadId: String, val turnId: String) : CodexEvent

    /** item/started：一个 item 开始（命令执行等） */
    data class ItemStarted(override val threadId: String, val item: ThreadItem) : CodexEvent

    /** item/agentMessage/delta：回复正文流式增量，按 itemId 累积拼接 */
    data class AgentMessageDelta(
        override val threadId: String,
        val itemId: String,
        val delta: String,
    ) : CodexEvent

    /** item/reasoning/summaryTextDelta：服务端默认返回时才展示，不主动请求摘要。 */
    data class ReasoningSummaryDelta(
        override val threadId: String,
        val itemId: String,
        val summaryIndex: Int,
        val delta: String,
    ) : CodexEvent

    /** item/completed：item 完成，agentMessage 完整文本可用来校对/替换 */
    data class ItemCompleted(override val threadId: String, val item: ThreadItem) : CodexEvent

    /** turn/completed：一轮结束，status = completed / interrupted / failed */
    data class TurnCompleted(
        override val threadId: String,
        val turnId: String,
        val status: String,
        val error: String? = null,
    ) : CodexEvent

    /** thread/tokenUsage/updated：token 用量（做上下文占用展示） */
    data class TokenUsageUpdated(override val threadId: String, val usage: TokenUsage) : CodexEvent

    /**
     * WebSocket 重连后通知不会补发；仓库会重新 resume/read，并用此快照让当前页面对账。
     */
    data class ThreadReconciled(val thread: Thread) : CodexEvent {
        override val threadId: String = thread.id
    }

    /**
     * §6.1 item/commandExecution/requestApproval：审批反向请求，必须应答。
     * requestId 即 JSON-RPC 的 id，应答时带回。
     */
    data class ApprovalRequest(
        val requestId: Int,
        override val threadId: String,
        val turnId: String,
        val itemId: String,
        val command: String,
        val cwd: String,
        val reason: String,
    ) : CodexEvent

    /** thread/deleted：会话被彻底删除（可能来自其他设备），本地移除并停止展示 */
    data class ThreadDeleted(override val threadId: String) : CodexEvent

    /** thread/archived：会话被归档（可能来自其他设备），从默认列表移除 */
    data class ThreadArchived(override val threadId: String) : CodexEvent
}

data class ThreadPage(val data: List<Thread>, val nextCursor: String?)

/**
 * Codex 后端仓库接口。方法名刻意对应 WebSocket 协议的 RPC method（§3），
 * UI / ViewModel 层只依赖此接口；生产实现使用 OkHttp WebSocket，
 * 测试或预览时仍可注入 FakeCodexRepository。
 */
interface CodexRepository {

    /** 全局事件流：单连接 + reader 协程分发（§8.2），所有事件都从这里出来 */
    val events: SharedFlow<CodexEvent>

    /** §3.1 initialize + initialized */
    suspend fun initialize(clientName: String = "codex-android", version: String = "0.1.0")

    /** §3.4 thread/list */
    suspend fun listThreads(cursor: String? = null, limit: Int = 20): ThreadPage

    /** §3.2 thread/start，可选 model */
    suspend fun startThread(model: String? = null): Thread

    /** §3.3 thread/resume */
    suspend fun resumeThread(threadId: String, model: String? = null): Thread

    /** §3.5 thread/read，includeTurns=true 返回每轮 items 用于重建聊天界面 */
    suspend fun readThread(threadId: String, includeTurns: Boolean = true): Thread

    /** §3.10 thread/archive：归档会话（软删除，列表默认隐藏，可 unarchive 恢复） */
    suspend fun archiveThread(threadId: String)

    /** §3.10 thread/delete：彻底删除会话（不可恢复；服务端需 rust-v0.140.0+） */
    suspend fun deleteThread(threadId: String)

    /** §3.6 turn/start，发消息 */
    suspend fun startTurn(threadId: String, input: List<Content>): Turn

    /** §3.7 turn/interrupt */
    suspend fun interruptTurn(threadId: String, turnId: String)

    /** §6 审批应答：{"id": requestId, "result": {"decision": ...}} */
    suspend fun respondApproval(requestId: Int, decision: ApprovalDecision)

    /** §3.8 model/list */
    suspend fun listModels(): List<ModelInfo>

    /** §3.9② thread/settings/update：会话中途切换模型/推理档位 */
    suspend fun updateThreadSettings(threadId: String, model: String? = null, effort: String? = null)
}
