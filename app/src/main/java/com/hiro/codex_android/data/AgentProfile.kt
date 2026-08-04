package com.hiro.codex_android.data

import java.util.UUID

/**
 * Agent 类型。目前只有 CODEX 有完整实现（JSON-RPC over WebSocket）；
 * 其余类型先在设置中可见、标注"暂未支持"，后续各自实现 [CodexRepository]。
 */
enum class AgentType(
    val wireValue: String,
    val displayName: String,
    /** 列表卡片/徽章上的单字母标识 */
    val badgeLetter: String,
    /** 徽章底色（ARGB Long，UI 层转 Color） */
    val badgeColor: Long,
    /** 是否已有可用的 repository 实现 */
    val supported: Boolean,
) {
    CODEX("codex", "Codex", "C", 0xFF10A37F, true),
    KIMI("kimi", "Kimi Code", "K", 0xFF3E63DD, false),
    CLAUDE("claude", "Claude Code", "A", 0xFFD97757, false),
    OPENCODE("opencode", "OpenCode", "O", 0xFF8B5CF6, false),
    ;

    companion object {
        fun fromWireValue(value: String): AgentType =
            entries.firstOrNull { it.wireValue == value } ?: CODEX

        /** §0 生产地址（8443 规避未备案域名 80/443 拦截）；其余类型暂无默认。 */
        fun defaultUrl(type: AgentType): String = when (type) {
            CODEX -> "wss://codex.waibozishu.com:8443"
            else -> ""
        }
    }
}

/**
 * 一个 Agent 服务器配置。会话（Thread）归属某个 profile，
 * 列表聚合展示时凭 [id] 找到对应的 repository/连接。
 */
data class AgentProfile(
    val id: String = UUID.randomUUID().toString(),
    /** 用户命名；留空时展示用 [AgentType.displayName] */
    val name: String = "",
    val type: AgentType = AgentType.CODEX,
    val serverUrl: String = "",
    val token: String = "",
    val enabled: Boolean = true,
) {
    val displayName: String get() = name.ifBlank { type.displayName }

    /** 连接三元组：任一变化都需要重建 repository。 */
    fun connectionKey(): String = "${type.wireValue}|$serverUrl|$token"
}
