package com.hiro.codex_android.data.model

/**
 * 协议数据模型，字段与 docs/MOBILE_APP_API.md 中的 camelCase JSON 一一对应。
 * 接入真实后端时，在此之上加 JSON 反序列化即可（§8.7 可用 generate-ts 对照字段）。
 */

/** §3.2 thread/start 返回的 Thread */
data class Thread(
    val id: String,
    val preview: String = "",
    val name: String? = null,
    val ephemeral: Boolean = false,
    val createdAt: Long = 0,          // epoch 秒
    val updatedAt: Long = 0,          // epoch 秒
    val status: ThreadStatus = ThreadStatus("idle"),
    val cwd: String = "",
    val model: String? = null,
    val turns: List<Turn> = emptyList(),
)

/** status: {"type": "idle" | "busy" ...} */
data class ThreadStatus(val type: String)

data class Turn(
    val id: String,
    /** inProgress / completed / interrupted / failed（§5 末尾） */
    val status: String,
    val items: List<ThreadItem> = emptyList(),
    val error: String? = null,
)

/** §5 ThreadItem，以 type 区分 */
sealed interface ThreadItem {
    val id: String

    data class UserMessage(
        override val id: String,
        val content: List<Content>,
    ) : ThreadItem

    /** AI 回复，text 为 Markdown */
    data class AgentMessage(
        override val id: String,
        val text: String,
    ) : ThreadItem

    /** 命令执行 */
    data class CommandExecution(
        override val id: String,
        val command: String,
        val cwd: String = "",
        /** inProgress / completed / failed / declined */
        val status: String = "inProgress",
        val aggregatedOutput: String = "",
        val exitCode: Int? = null,
        val durationMs: Long? = null,
    ) : ThreadItem

    data class FileChange(
        override val id: String,
        val changes: List<String> = emptyList(),
        val status: String = "completed",
    ) : ThreadItem

    data class Plan(
        override val id: String,
        val text: String,
    ) : ThreadItem

    data class Reasoning(
        override val id: String,
        val summary: List<String> = emptyList(),
    ) : ThreadItem
}

/** 消息内容块，§3.6 turn/start 的 input 元素 */
data class Content(
    val type: String,               // text / image / localImage
    val text: String = "",
    val url: String? = null,
)

/** §3.8 model/list 返回的 Model */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String = "",
    val isDefault: Boolean = false,
    val hidden: Boolean = false,
    val supportedReasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String = "medium",
)

/** §4 thread/tokenUsage/updated：上下文 token 占用（字段以后端实际返回为准） */
data class TokenUsage(
    val usedTokens: Long,
    val contextWindow: Long,
)

/** §6.1 审批应答四选一 */
enum class ApprovalDecision(val wireValue: String) {
    Accept("accept"),
    AcceptForSession("acceptForSession"),
    Decline("decline"),
    Cancel("cancel"),
}
