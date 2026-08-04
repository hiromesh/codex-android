package com.hiro.codex_android.ui.chat

import com.hiro.codex_android.data.model.ReviewTarget

/** 输入框 `/` 弹出的可选命令（对应 docs/CODEX_ACTIONS_API.md）。 */
data class SlashCommandSpec(
    val trigger: String,
    val title: String,
)

val SLASH_COMMANDS = listOf(
    SlashCommandSpec("/compact", "压缩上下文"),
    SlashCommandSpec("/review", "代码审查"),
    SlashCommandSpec("/fork", "分叉会话"),
    SlashCommandSpec("/undo", "撤销末轮"),
)

/**
 * 解析输入是否为动作命令。命中则不应再走 turn/start 发消息。
 * `!cmd` 走 shell；未知 `/xxx` 返回 null，仍按普通消息发送。
 */
sealed interface ParsedChatAction {
    data object Compact : ParsedChatAction
    data object ReviewNeedTarget : ParsedChatAction
    data class Review(val target: ReviewTarget) : ParsedChatAction
    data object Fork : ParsedChatAction
    data class Undo(val numTurns: Int = 1) : ParsedChatAction
    data class Shell(val command: String) : ParsedChatAction
}

fun parseChatAction(raw: String): ParsedChatAction? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (text.startsWith("!")) {
        val command = text.drop(1).trim()
        return if (command.isNotEmpty()) ParsedChatAction.Shell(command) else null
    }
    if (!text.startsWith("/")) return null
    val parts = text.split(Regex("\\s+"), limit = 2)
    val head = parts[0].lowercase()
    val rest = parts.getOrNull(1)?.trim().orEmpty()
    return when (head) {
        "/compact" -> ParsedChatAction.Compact
        "/fork" -> ParsedChatAction.Fork
        "/undo" -> ParsedChatAction.Undo(rest.toIntOrNull()?.coerceAtLeast(1) ?: 1)
        "/review" -> when {
            rest.isEmpty() -> ParsedChatAction.ReviewNeedTarget
            rest.startsWith("commit ", ignoreCase = true) -> {
                val sha = rest.removePrefix("commit ").trim().substringBefore(' ')
                if (sha.isBlank()) ParsedChatAction.ReviewNeedTarget
                else ParsedChatAction.Review(ReviewTarget.Commit(sha))
            }
            rest.startsWith("custom ", ignoreCase = true) || rest.startsWith(":", ignoreCase = false) -> {
                val instructions = when {
                    rest.startsWith("custom ", ignoreCase = true) -> rest.removePrefix("custom ").trim()
                    rest.startsWith(":") -> rest.removePrefix(":").trim()
                    else -> rest
                }
                if (instructions.isBlank()) ParsedChatAction.ReviewNeedTarget
                else ParsedChatAction.Review(ReviewTarget.Custom(instructions))
            }
            else -> ParsedChatAction.Review(ReviewTarget.BaseBranch(rest))
        }
        else -> null
    }
}

fun filterSlashCommands(query: String): List<SlashCommandSpec> {
    val q = query.trim()
    if (!q.startsWith("/")) return emptyList()
    // 已输入空格说明命令已选定，不再弹菜单（参数由用户继续敲）。
    if (q.contains(' ')) return emptyList()
    return SLASH_COMMANDS.filter { it.trigger.startsWith(q, ignoreCase = true) }
}
