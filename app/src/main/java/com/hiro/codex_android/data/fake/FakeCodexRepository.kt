package com.hiro.codex_android.data.fake

import com.hiro.codex_android.data.CodexEvent
import com.hiro.codex_android.data.CodexRepository
import com.hiro.codex_android.data.ThreadPage
import com.hiro.codex_android.data.model.ApprovalDecision
import com.hiro.codex_android.data.model.Content
import com.hiro.codex_android.data.model.ModelInfo
import com.hiro.codex_android.data.model.Thread
import com.hiro.codex_android.data.model.ThreadItem
import com.hiro.codex_android.data.model.TokenUsage
import com.hiro.codex_android.data.model.Turn
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 不接后端的假实现：内存中维护会话列表，turn 用脚本模拟真实协议的流式事件
 * （turn/started → item/started → delta × N → item/completed → turn/completed），
 * 含一次命令执行审批演示。之后换成 WebSocket 实现时 UI 层零改动。
 */
class FakeCodexRepository : CodexRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<CodexEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CodexEvent> = _events.asSharedFlow()

    private val threads = mutableListOf<Thread>()
    private val turnJobs = mutableMapOf<String, Job>()
    private val pendingApprovals = mutableMapOf<Int, CompletableDeferred<ApprovalDecision>>()
    private var nextApprovalId = 100

    /** 每个会话累计已用 token（演示上下文占用环） */
    private val tokenUsage = mutableMapOf<String, Long>()
    private val contextWindow = 272_000L

    init {
        seed()
    }

    override suspend fun initialize(clientName: String, version: String) {
        delay(200) // 模拟 initialize + initialized 握手
    }

    override suspend fun listThreads(cursor: String?, limit: Int): ThreadPage {
        delay(250)
        return ThreadPage(threads.sortedByDescending { it.updatedAt }.take(limit), nextCursor = null)
    }

    override suspend fun startThread(model: String?): Thread {
        delay(200)
        val now = epochSeconds()
        val thread = Thread(
            id = uuid(),
            createdAt = now,
            updatedAt = now,
            cwd = "/home/hiro/proj",
            model = model ?: "gpt-5.6-terra",
        )
        threads += thread
        return thread
    }

    override suspend fun resumeThread(threadId: String, model: String?): Thread =
        readThread(threadId, includeTurns = true)

    override suspend fun readThread(threadId: String, includeTurns: Boolean): Thread {
        delay(150)
        return threads.firstOrNull { it.id == threadId }
            ?: throw NoSuchElementException("thread not found: $threadId")
    }

    override suspend fun startTurn(threadId: String, input: List<Content>): Turn {
        val turn = Turn(id = uuid(), status = "inProgress")
        val text = input.firstOrNull { it.type == "text" }?.text.orEmpty()
        turnJobs[turn.id] = scope.launch { runScript(threadId, turn.id, text) }
        return turn
    }

    override suspend fun interruptTurn(threadId: String, turnId: String) {
        turnJobs[turnId]?.cancel()
    }

    override suspend fun respondApproval(requestId: Int, decision: ApprovalDecision) {
        pendingApprovals.remove(requestId)?.complete(decision)
    }

    override suspend fun listModels(): List<ModelInfo> {
        delay(150)
        // 取自文档 §3.8 实测返回
        return listOf(
            ModelInfo(
                "gpt-5.6-sol", "GPT-5.6-Sol",
                supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh"),
            ),
            ModelInfo(
                "gpt-5.6-terra", "GPT-5.6-Terra",
                isDefault = true,
                supportedReasoningEfforts = listOf("low", "medium", "high"),
            ),
            ModelInfo("gpt-5.6-luna", "GPT-5.6-Luna", supportedReasoningEfforts = listOf("minimal", "low", "medium"), defaultReasoningEffort = "low"),
            ModelInfo("gpt-5.5", "GPT-5.5", supportedReasoningEfforts = listOf("low", "medium", "high")),
            ModelInfo("gpt-5.4", "GPT-5.4", supportedReasoningEfforts = listOf("low", "medium")),
            ModelInfo("gpt-5.4-mini", "GPT-5.4-Mini", supportedReasoningEfforts = listOf("minimal", "low"), defaultReasoningEffort = "low"),
        )
    }

    override suspend fun updateThreadSettings(threadId: String, model: String?, effort: String?) {
        delay(100)
        threads.replaceAll { if (it.id == threadId && model != null) it.copy(model = model) else it }
    }

    // ── 脚本 ─────────────────────────────────────────────────────────────

    private suspend fun runScript(threadId: String, turnId: String, userText: String) {
        try {
            _events.emit(CodexEvent.TurnStarted(threadId, turnId))
            delay(400)
            val wantsApproval = userText.contains("清理") || userText.contains("rm") || userText.contains("删")
            val finalStatus = if (wantsApproval) {
                approvalScript(threadId, turnId)
            } else {
                defaultScript(threadId, turnId, userText)
            }
            // §7 实测时序：tokenUsage/updated 在 turn/completed 之前
            emitTokenUsage(threadId, turnCost = if (wantsApproval) 24_000 else 18_000)
            _events.emit(CodexEvent.TurnCompleted(threadId, turnId, status = finalStatus))
        } catch (e: CancellationException) {
            _events.emit(CodexEvent.TurnCompleted(threadId, turnId, status = "interrupted"))
            throw e
        } finally {
            turnJobs.remove(turnId)
            touchThread(threadId, userText)
        }
    }

    /** 普通回复：流式文本 → 命令执行卡片 → 流式总结 */
    private suspend fun defaultScript(threadId: String, turnId: String, userText: String): String {
        streamMessage(threadId, "收到：「$userText」。我先看一下服务器上的项目目录：")
        commandItem(
            threadId,
            command = "ls -lh /home/hiro/proj",
            cwd = "/home/hiro/proj",
            output = "total 24K\n" +
                "drwxr-xr-x 8 hiro hiro 4.0K Jul 28 10:12 app\n" +
                "-rw-r--r-- 1 hiro hiro 2.1K Jul 27 22:03 README.md\n" +
                "drwxr-xr-x 3 hiro hiro 4.0K Jul 26 18:40 docs\n",
            exitCode = 0,
            durationMs = 182,
        )
        streamMessage(
            threadId,
            "目录结构正常：`app/` 是主模块，`docs/` 是接口文档。\n\n" +
                "接下来可以让我：\n" +
                "- 跑一遍构建或测试\n" +
                "- 对我说「清理 build」试试审批弹窗演示\n" +
                "- 生成过程中点输入框旁的停止键可以打断我",
        )
        return "completed"
    }

    /** 审批演示：流式文本 → 命令 → 等用户审批 → 按决定分支 */
    private suspend fun approvalScript(threadId: String, turnId: String): String {
        streamMessage(threadId, "好，清理构建缓存。先确认一下占用：")
        commandItem(threadId, "du -sh build/", "/home/hiro/proj", "312M\tbuild/\n", 0, 95)

        val requestId = nextApprovalId++
        val decision = CompletableDeferred<ApprovalDecision>()
        pendingApprovals[requestId] = decision
        _events.emit(
            CodexEvent.ApprovalRequest(
                requestId = requestId,
                threadId = threadId,
                turnId = turnId,
                itemId = uuid(),
                command = "rm -rf build/",
                cwd = "/home/hiro/proj",
                reason = "删除构建目录以释放磁盘空间",
            ),
        )
        return when (decision.await()) {
            ApprovalDecision.Accept, ApprovalDecision.AcceptForSession -> {
                commandItem(threadId, "rm -rf build/", "/home/hiro/proj", "", 0, 1240)
                streamMessage(threadId, "已清理完成，释放约 312MB 磁盘空间。")
                "completed"
            }
            ApprovalDecision.Decline -> {
                streamMessage(threadId, "好的，已跳过删除，构建目录保留。")
                "completed"
            }
            ApprovalDecision.Cancel -> {
                streamMessage(threadId, "已按你的要求中断本轮操作。")
                "interrupted"
            }
        }
    }

    /** item/started → delta × N → item/completed，逐字追加 */
    private suspend fun streamMessage(threadId: String, text: String) {
        val itemId = uuid()
        _events.emit(CodexEvent.ItemStarted(threadId, ThreadItem.AgentMessage(itemId, text = "")))
        for (chunk in text.chunked(3)) {
            _events.emit(CodexEvent.AgentMessageDelta(threadId, itemId, chunk))
            delay(40)
        }
        _events.emit(CodexEvent.ItemCompleted(threadId, ThreadItem.AgentMessage(itemId, text)))
    }

    private suspend fun commandItem(
        threadId: String,
        command: String,
        cwd: String,
        output: String,
        exitCode: Int,
        durationMs: Long,
    ) {
        val itemId = uuid()
        _events.emit(
            CodexEvent.ItemStarted(
                threadId,
                ThreadItem.CommandExecution(itemId, command, cwd, status = "inProgress"),
            ),
        )
        delay(600)
        _events.emit(
            CodexEvent.ItemCompleted(
                threadId,
                ThreadItem.CommandExecution(itemId, command, cwd, "completed", output, exitCode, durationMs),
            ),
        )
    }

    /** thread/tokenUsage/updated：累计本轮消耗后广播 */
    private suspend fun emitTokenUsage(threadId: String, turnCost: Long) {
        val used = (tokenUsage[threadId] ?: 30_000L) + turnCost
        tokenUsage[threadId] = used
        _events.emit(CodexEvent.TokenUsageUpdated(threadId, TokenUsage(used, contextWindow)))
    }

    /** 更新列表页预览/时间 */
    private fun touchThread(threadId: String, userText: String) {
        threads.replaceAll {
            if (it.id == threadId) it.copy(preview = userText.take(40), updatedAt = epochSeconds()) else it
        }
    }

    private fun seed() {
        val now = epochSeconds()
        threads += Thread(
            id = uuid(),
            preview = "看下服务器磁盘占用",
            createdAt = now - 86400,
            updatedAt = now - 3600,
            cwd = "/home/hiro",
            model = "gpt-5.6-sol",
            turns = listOf(
                Turn(
                    uuid(), "completed",
                    items = listOf(
                        ThreadItem.UserMessage(uuid(), listOf(Content("text", "看下服务器磁盘占用"))),
                        ThreadItem.AgentMessage(uuid(), "好的，我来查一下磁盘占用情况："),
                        ThreadItem.CommandExecution(
                            uuid(), "df -h /", "/home/hiro", "completed",
                            "Filesystem      Size  Used Avail Use% Mounted on\n/dev/vda1        40G   22G   16G  58% /\n",
                            0, 210,
                        ),
                        ThreadItem.AgentMessage(
                            uuid(),
                            "磁盘用了 58%（22G/40G），还算健康。大头在 `/var/lib/docker`，需要清理可以叫我。",
                        ),
                    ),
                ),
            ),
        )
        threads += Thread(
            id = uuid(), preview = "帮我写个周报模板",
            createdAt = now - 3 * 86400, updatedAt = now - 2 * 86400,
            cwd = "/home/hiro", model = "gpt-5.6-sol",
        )
        threads += Thread(
            id = uuid(), preview = "检查一下 nginx 配置",
            createdAt = now - 5 * 86400, updatedAt = now - 4 * 86400,
            cwd = "/etc/nginx", model = "gpt-5.5",
        )
    }

    private fun uuid(): String = UUID.randomUUID().toString()
    private fun epochSeconds(): Long = System.currentTimeMillis() / 1000
}
