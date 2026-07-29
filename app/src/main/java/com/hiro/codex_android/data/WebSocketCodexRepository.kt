package com.hiro.codex_android.data

import com.hiro.codex_android.data.model.ApprovalDecision
import com.hiro.codex_android.data.model.Content
import com.hiro.codex_android.data.model.ModelInfo
import com.hiro.codex_android.data.model.Thread
import com.hiro.codex_android.data.model.ThreadItem
import com.hiro.codex_android.data.model.ThreadStatus
import com.hiro.codex_android.data.model.TokenUsage
import com.hiro.codex_android.data.model.Turn
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * `codex app-server` 的单 WebSocket 实现。
 *
 * 每次 RPC 前确保连接已经完成 initialize/initialized 握手；配置页变更地址或
 * token 后会关闭旧连接并重新握手。所有服务端通知和审批请求由同一 reader 分发。
 */
class WebSocketCodexRepository(
    private val settingsStore: SettingsStore,
) : CodexRepository {

    private data class ConnectionConfig(val url: String, val token: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val connectMutex = Mutex()
    private val nextRequestId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val itemThreads = ConcurrentHashMap<String, String>()
    private val turnThreads = ConcurrentHashMap<String, String>()
    private val incoming = Channel<JSONObject>(Channel.UNLIMITED)

    private val _events = MutableSharedFlow<CodexEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<CodexEvent> = _events.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var config: ConnectionConfig? = null
    @Volatile private var currentThreadId: String? = null
    /** 当前尚未收到 turn/completed 的会话；仅它需要断线后主动恢复。 */
    @Volatile private var activeTurnThreadId: String? = null
    /** 每次新建连接都要重新 attach，不能把上一条 WebSocket 的状态当作仍有效。 */
    @Volatile private var attachedThreadId: String? = null
    @Volatile private var openWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var recoveryJob: Job? = null

    init {
        // OkHttp 回调线程不保证通知顺序；用单一消费者保证流式 delta 的顺序。
        scope.launch {
            for (message in incoming) {
                if (message.has("id") && message.has("method")) handleServerRequest(message)
                else handleNotification(message)
            }
        }
    }

    override suspend fun initialize(clientName: String, version: String) {
        ensureInitialized(clientName, version)
    }

    override suspend fun listThreads(cursor: String?, limit: Int): ThreadPage {
        val params = JSONObject().put("limit", limit).put("cursor", cursor ?: JSONObject.NULL)
            .put("archived", false)
        val result = request("thread/list", params)
        val threads = result.optJSONArray("data").objects().map(::parseThread)
        return ThreadPage(threads, result.nullableString("nextCursor"))
    }

    override suspend fun startThread(model: String?): Thread {
        val params = JSONObject().also { if (model != null) it.put("model", model) }
        val result = request("thread/start", params)
        return parseThread(result.optJSONObject("thread") ?: throw protocolError("thread/start missing thread"))
            .let { thread ->
                val resolved = if (thread.model == null) thread.copy(model = result.nullableString("model")) else thread
                currentThreadId = resolved.id
                attachedThreadId = resolved.id
                resolved
            }
    }

    override suspend fun resumeThread(threadId: String, model: String?): Thread {
        val params = JSONObject().put("threadId", threadId).also { if (model != null) it.put("model", model) }
        val result = request("thread/resume", params)
        return parseThread(result.optJSONObject("thread") ?: throw protocolError("thread/resume missing thread"))
            .let { thread ->
                val resolved = if (thread.model == null) thread.copy(model = result.nullableString("model")) else thread
                currentThreadId = resolved.id
                attachedThreadId = resolved.id
                resolved
            }
    }

    override suspend fun readThread(threadId: String, includeTurns: Boolean): Thread {
        return try {
            readThreadRaw(threadId, includeTurns)
        } catch (firstError: IOException) {
            // 部分 app-server 版本要求先将持久化会话 attach 到当前 WebSocket。
            // 标准 read 成功时不额外 resume，避免不必要的状态变更。
            if (!firstError.message.orEmpty().contains("thread not found", ignoreCase = true)) {
                throw firstError
            }
            val resumed = resumeThread(threadId)
            try {
                readThreadRaw(threadId, includeTurns)
            } catch (retryError: IOException) {
                // resume 的响应本身也可携带历史；若旧服务端不支持 read，仍可继续对话。
                if (retryError.message.orEmpty().contains("thread not found", ignoreCase = true)) resumed
                else throw retryError
            }
        }
    }

    override suspend fun startTurn(threadId: String, input: List<Content>): Turn {
        // 通知里 item/started 和 delta 可能不带 threadId；先登记，避免首批流式消息丢失。
        currentThreadId = threadId
        activeTurnThreadId = threadId
        ensureThreadAttached(threadId)
        val content = JSONArray().also { array -> input.forEach { array.put(it.toJson()) } }
        val result = requestRaw("turn/start", JSONObject().put("threadId", threadId).put("input", content))
        val turn = parseTurn(result.optJSONObject("turn") ?: throw protocolError("turn/start missing turn"))
        turnThreads[turn.id] = threadId
        currentThreadId = threadId
        return turn
    }

    override suspend fun interruptTurn(threadId: String, turnId: String) {
        request("turn/interrupt", JSONObject().put("threadId", threadId).put("turnId", turnId))
    }

    override suspend fun respondApproval(requestId: Int, decision: ApprovalDecision) {
        sendResponse(requestId, JSONObject().put("decision", decision.wireValue))
    }

    override suspend fun listModels(): List<ModelInfo> {
        val result = request(
            "model/list",
            JSONObject().put("cursor", JSONObject.NULL).put("limit", JSONObject.NULL).put("includeHidden", false),
        )
        return result.optJSONArray("data").objects().map(::parseModel)
    }

    override suspend fun updateThreadSettings(threadId: String, model: String?, effort: String?) {
        val params = JSONObject().put("threadId", threadId)
        model?.let { params.put("model", it) }
        effort?.let { params.put("effort", it) }
        request("thread/settings/update", params)
    }

    private suspend fun ensureInitialized(clientName: String = "codex-android", version: String = "1.0.0") {
        connectMutex.withLock {
            val desired = settingsStore.settings.value.toConnectionConfig()
            if (socket != null && config == desired) return

            socket?.close(1000, "connection settings changed")
            failPending(IOException("WebSocket connection was replaced"))
            socket = null
            config = null
            attachedThreadId = null

            val opened = CompletableDeferred<Unit>()
            openWaiter = opened
            val request = Request.Builder().url(desired.url)
                .header("Authorization", "Bearer ${desired.token}")
                .build()
            socket = client.newWebSocket(request, listener)
            try {
                withTimeout(CONNECT_TIMEOUT_MS) { opened.await() }
            } catch (e: TimeoutCancellationException) {
                socket?.cancel()
                socket = null
                throw IOException("连接服务器超时")
            }
            config = desired
            attachedThreadId = null

            val init = JSONObject()
                .put("clientInfo", JSONObject().put("name", clientName).put("version", version))
                .put("capabilities", JSONObject().put("experimentalApi", true))
            requestRaw("initialize", init)
            sendNotification("initialized")
        }
    }

    private suspend fun request(method: String, params: JSONObject? = null): JSONObject {
        ensureInitialized()
        return requestRaw(method, params)
    }

    private suspend fun readThreadRaw(threadId: String, includeTurns: Boolean): Thread {
        val result = request(
            "thread/read",
            JSONObject().put("threadId", threadId).put("includeTurns", includeTurns),
        )
        return parseThread(result.optJSONObject("thread") ?: result)
    }

    /** 当前连接是无状态的：换了 WebSocket 后必须重新 resume 才能继续 turn/start。 */
    private suspend fun ensureThreadAttached(threadId: String) {
        ensureInitialized()
        if (attachedThreadId == threadId) return
        val result = requestRaw("thread/resume", JSONObject().put("threadId", threadId))
        val resumed = parseThread(result.optJSONObject("thread") ?: throw protocolError("thread/resume missing thread"))
        currentThreadId = resumed.id
        attachedThreadId = resumed.id
    }

    private suspend fun requestRaw(method: String, params: JSONObject? = null): JSONObject {
        val id = nextRequestId.incrementAndGet()
        val response = CompletableDeferred<JSONObject>()
        pending[id] = response
        val message = JSONObject().put("id", id).put("method", method)
        params?.let { message.put("params", it) }
        if (socket?.send(message.toString()) != true) {
            pending.remove(id)
            throw IOException("WebSocket 未连接，无法发送 $method")
        }
        return try {
            withTimeout(RPC_TIMEOUT_MS) { response.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            throw IOException("$method 请求超时")
        }
    }

    private fun sendNotification(method: String, params: JSONObject? = null) {
        val message = JSONObject().put("method", method)
        params?.let { message.put("params", it) }
        check(socket?.send(message.toString()) == true) { "WebSocket 未连接" }
    }

    private fun sendResponse(id: Int, result: JSONObject) {
        val message = JSONObject().put("id", id).put("result", result)
        if (socket?.send(message.toString()) != true) throw IOException("WebSocket 未连接，无法回复审批")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            openWaiter?.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = try {
                JSONObject(text)
            } catch (_: Exception) {
                return
            }
            when {
                message.has("id") && !message.has("method") -> handleResponse(message)
                message.has("method") -> incoming.trySend(message)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) {
                socket = null
                config = null
                attachedThreadId = null
                openWaiter?.completeExceptionally(IOException("连接失败：${t.message ?: "未知错误"}", t))
                failPending(IOException("WebSocket 已断开：${t.message ?: "未知错误"}", t))
                scheduleActiveTurnRecovery()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) {
                socket = null
                config = null
                attachedThreadId = null
                failPending(IOException("WebSocket 已关闭${if (reason.isBlank()) "" else "：$reason"}"))
                scheduleActiveTurnRecovery()
            }
        }
    }

    private fun handleResponse(message: JSONObject) {
        val id = message.optInt("id", -1)
        val deferred = pending.remove(id) ?: return
        message.optJSONObject("error")?.let { error ->
            deferred.completeExceptionally(protocolError(error.optString("message", error.toString())))
        } ?: deferred.complete(message.optJSONObject("result") ?: JSONObject())
    }

    private suspend fun handleServerRequest(message: JSONObject) {
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        if (method !in APPROVAL_METHODS) {
            // 不能让服务端因未知反向请求永远阻塞；保守地拒绝。
            sendResponse(message.getInt("id"), JSONObject().put("decision", ApprovalDecision.Decline.wireValue))
            return
        }
        _events.emit(
            CodexEvent.ApprovalRequest(
                requestId = message.getInt("id"),
                threadId = params.optString("threadId", currentThreadId.orEmpty()),
                turnId = params.optString("turnId"),
                itemId = params.optString("itemId"),
                command = params.optString("command", params.optString("reason")),
                cwd = params.optString("cwd", params.optString("grantRoot")),
                reason = params.optString("reason"),
            ),
        )
    }

    private suspend fun handleNotification(message: JSONObject) {
        val params = message.optJSONObject("params") ?: JSONObject()
        when (message.optString("method")) {
            "turn/started" -> {
                val threadId = params.optString("threadId", currentThreadId.orEmpty())
                val turnId = params.optJSONObject("turn")?.optString("id").orEmpty()
                if (threadId.isNotBlank()) currentThreadId = threadId
                if (threadId.isNotBlank()) activeTurnThreadId = threadId
                if (turnId.isNotBlank() && threadId.isNotBlank()) turnThreads[turnId] = threadId
                emitWhenThreadKnown(threadId) { CodexEvent.TurnStarted(it, turnId) }
            }
            "item/started", "item/completed" -> {
                val item = parseItem(params.optJSONObject("item")) ?: return
                val threadId = params.optString("threadId", itemThreads[item.id] ?: currentThreadId.orEmpty())
                if (threadId.isNotBlank()) itemThreads[item.id] = threadId
                emitWhenThreadKnown(threadId) {
                    if (message.optString("method") == "item/started") CodexEvent.ItemStarted(it, item)
                    else CodexEvent.ItemCompleted(it, item)
                }
            }
            "item/agentMessage/delta" -> {
                val itemId = params.optString("itemId")
                val threadId = params.optString("threadId", itemThreads[itemId] ?: currentThreadId.orEmpty())
                if (threadId.isNotBlank() && itemId.isNotBlank()) itemThreads[itemId] = threadId
                emitWhenThreadKnown(threadId) { CodexEvent.AgentMessageDelta(it, itemId, params.optString("delta")) }
            }
            "item/reasoning/summaryTextDelta" -> {
                val itemId = params.optString("itemId")
                val threadId = params.optString("threadId", itemThreads[itemId] ?: currentThreadId.orEmpty())
                if (threadId.isNotBlank() && itemId.isNotBlank()) itemThreads[itemId] = threadId
                emitWhenThreadKnown(threadId) {
                    CodexEvent.ReasoningSummaryDelta(
                        threadId = it,
                        itemId = itemId,
                        summaryIndex = params.optInt("summaryIndex", 0),
                        delta = params.optString("delta"),
                    )
                }
            }
            "turn/completed" -> {
                val turn = params.optJSONObject("turn")
                val turnId = turn?.optString("id").orEmpty()
                val threadId = params.optString("threadId", turnThreads[turnId] ?: currentThreadId.orEmpty())
                emitWhenThreadKnown(threadId) {
                    CodexEvent.TurnCompleted(it, turnId, turn?.statusValue().orEmpty(), turn?.nullableString("error"))
                }
                if (activeTurnThreadId == threadId) activeTurnThreadId = null
            }
            "thread/tokenUsage/updated" -> {
                val threadId = params.optString("threadId", currentThreadId.orEmpty())
                val usage = params.optJSONObject("tokenUsage") ?: params
                emitWhenThreadKnown(threadId) {
                    CodexEvent.TokenUsageUpdated(
                        it,
                        TokenUsage(usage.optLong("usedTokens", usage.optLong("totalTokens")), usage.optLong("contextWindow")),
                    )
                }
            }
        }
    }

    /**
     * 服务端不重放 WebSocket 通知。断线时正在跑的轮次会继续在服务端执行，
     * 所以恢复连接后重新挂载会话并读全量历史，页面即可补齐最终结果。
     */
    private fun scheduleActiveTurnRecovery() {
        val threadId = activeTurnThreadId ?: return
        if (recoveryJob?.isActive == true) return
        recoveryJob = scope.launch {
            repeat(RECONNECT_ATTEMPTS) { attempt ->
                try {
                    if (attempt > 0) delay(RECONNECT_BASE_DELAY_MS * (1L shl (attempt - 1)))
                    // resumeThread 会对新 socket 执行 initialize + attach。
                    resumeThread(threadId)
                    val thread = readThreadRaw(threadId, includeTurns = true)
                    _events.emit(CodexEvent.ThreadReconciled(thread))
                    val activeTurn = thread.turns.lastOrNull { it.status == "inProgress" }
                    activeTurnThreadId = activeTurn?.let { threadId }
                    return@launch
                } catch (_: Throwable) { /* retry with exponential backoff */ }
            }
            // 不把网络错误伪装成“生成完成”；下次回到前台仍会主动 read 对账。
        }
    }

    private suspend fun emitWhenThreadKnown(threadId: String, event: (String) -> CodexEvent) {
        if (threadId.isNotBlank()) _events.emit(event(threadId))
    }

    private fun failPending(error: Throwable) {
        pending.entries.forEach { (id, deferred) -> if (pending.remove(id, deferred)) deferred.completeExceptionally(error) }
    }

    private fun AppSettings.toConnectionConfig(): ConnectionConfig {
        val normalized = serverUrl.trim().removeSuffix("/")
        require(normalized.startsWith("ws://") || normalized.startsWith("wss://")) { "服务器地址必须以 ws:// 或 wss:// 开头" }
        require(token.isNotBlank()) { "请先在设置中填写 Token" }
        return ConnectionConfig(normalized, token.trim())
    }

    private fun Content.toJson(): JSONObject = JSONObject().put("type", type).also {
        if (text.isNotEmpty()) it.put("text", text)
        url?.let { value -> it.put("url", value) }
    }

    private fun parseThread(value: JSONObject): Thread = Thread(
        id = value.optString("id"),
        preview = value.optString("preview"),
        name = value.nullableString("name"),
        ephemeral = value.optBoolean("ephemeral"),
        createdAt = value.optLong("createdAt"),
        updatedAt = value.optLong("updatedAt"),
        status = ThreadStatus(value.optJSONObject("status")?.optString("type") ?: value.optString("status", "idle")),
        cwd = value.optString("cwd"),
        model = value.nullableString("model"),
        turns = value.optJSONArray("turns").objects().map(::parseTurn),
    )

    private fun parseTurn(value: JSONObject): Turn = Turn(
        id = value.optString("id"),
        status = value.statusValue(),
        items = value.optJSONArray("items").objects().mapNotNull(::parseItem),
        error = value.nullableString("error"),
    )

    private fun parseItem(value: JSONObject?): ThreadItem? {
        value ?: return null
        val id = value.optString("id")
        if (id.isBlank()) return null
        return when (value.optString("type")) {
            "userMessage" -> ThreadItem.UserMessage(id, value.optJSONArray("content").objects().map(::parseContent))
            "agentMessage" -> ThreadItem.AgentMessage(id, value.optString("text"))
            "commandExecution" -> ThreadItem.CommandExecution(
                id, value.optString("command"), value.optString("cwd"), value.statusValue(),
                value.optString("aggregatedOutput"), value.optIntOrNull("exitCode"), value.optLongOrNull("durationMs"),
            )
            "fileChange" -> ThreadItem.FileChange(id, value.optJSONArray("changes").fileChanges(), value.statusValue("completed"))
            "plan" -> ThreadItem.Plan(id, value.optString("text"))
            "reasoning" -> ThreadItem.Reasoning(id, value.optJSONArray("summary").textFragments())
            else -> null
        }
    }

    private fun parseContent(value: JSONObject) = Content(value.optString("type"), value.optString("text"), value.nullableString("url"))

    private fun parseModel(value: JSONObject): ModelInfo = ModelInfo(
        id = value.optString("id"),
        displayName = value.optString("displayName", value.optString("id")),
        description = value.optString("description"),
        isDefault = value.optBoolean("isDefault"),
        hidden = value.optBoolean("hidden"),
        supportedReasoningEfforts = value.optJSONArray("supportedReasoningEfforts").reasoningEfforts(),
        defaultReasoningEffort = value.optString("defaultReasoningEffort", "medium"),
    )

    private fun JSONObject.statusValue(default: String = "inProgress"): String = when (val status = opt("status")) {
        is JSONObject -> status.optString("type", default)
        is String -> status
        else -> default
    }

    private fun JSONObject.nullableString(name: String): String? = if (has(name) && !isNull(name)) optString(name) else null
    private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
    private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null
    private fun JSONArray?.objects(): List<JSONObject> = buildList {
        this@objects?.let { array ->
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(it) }
        }
    }
    /** fileChange.changes 有些服务端版本会返回对象（甚至带完整 diff）；只保留可读的文件摘要。 */
    private fun JSONArray?.fileChanges(): List<String> = buildList {
        this@fileChanges?.let { array ->
            for (i in 0 until array.length()) {
                when (val entry = array.opt(i)) {
                    is String -> entry.takeIf(String::isNotBlank)?.let(::add)
                    is JSONObject -> {
                        val path = sequenceOf("path", "filePath", "filename", "name")
                            .mapNotNull(entry::nullableString)
                            .firstOrNull()
                        val kind = sequenceOf("kind", "status", "changeType")
                            .mapNotNull(entry::nullableString)
                            .firstOrNull()
                        when {
                            path != null && kind != null -> add("$kind · $path")
                            path != null -> add(path)
                            kind != null -> add(kind)
                            else -> add("文件改动")
                        }
                    }
                }
            }
        }
    }
    /** summary 既可能是字符串数组，也可能是 [{"text":"..."}]（当前 app-server）。 */
    private fun JSONArray?.textFragments(): List<String> = buildList {
        this@textFragments?.let { array ->
            for (i in 0 until array.length()) {
                when (val entry = array.opt(i)) {
                    is String -> if (entry.isNotBlank()) add(entry)
                    is JSONObject -> entry.firstTextValue()?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }
    private fun JSONObject.firstTextValue(): String? = sequenceOf("text", "content", "summary")
        .mapNotNull { name -> nullableString(name) }
        .firstOrNull()
    private fun JSONArray?.reasoningEfforts(): List<String> = buildList {
        this@reasoningEfforts?.let { array ->
            for (i in 0 until array.length()) {
                when (val entry = array.opt(i)) {
                    is String -> add(entry)
                    is JSONObject -> entry.nullableString("reasoningEffort")?.let(::add)
                }
            }
        }
    }

    private fun protocolError(message: String) = IOException("Codex 协议错误：$message")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val RPC_TIMEOUT_MS = 30_000L
        const val RECONNECT_ATTEMPTS = 5
        const val RECONNECT_BASE_DELAY_MS = 1_000L
        val APPROVAL_METHODS = setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval")
    }
}
