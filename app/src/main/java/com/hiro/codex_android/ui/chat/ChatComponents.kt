package com.hiro.codex_android.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hiro.codex_android.data.CodexEvent
import com.hiro.codex_android.data.model.ApprovalDecision
import com.hiro.codex_android.data.model.ModelInfo
import com.hiro.codex_android.data.model.ThreadItem
import com.hiro.codex_android.data.model.TokenUsage
import com.hiro.codex_android.ui.theme.GlassBorder
import com.hiro.codex_android.ui.theme.GlassFillStrong

/** 用户消息：右对齐深灰气泡 */
@Composable
fun UserMessageBubble(item: ThreadItem.UserMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
            border = BorderStroke(1.dp, GlassBorder),
        ) {
            Text(
                text = item.content.firstOrNull { it.type == "text" }?.text.orEmpty(),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 310.dp).padding(horizontal = 14.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
fun AgentMessageItem(item: ThreadItem.AgentMessage, streaming: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        // 正文优先保证可读性：比其他玻璃层更深、更不透明，避免渐变背景吞掉 Markdown 文本。
        Surface(
            modifier = Modifier.widthIn(max = 340.dp),
            color = GlassFillStrong,
            contentColor = MaterialTheme.colorScheme.onBackground,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassBorder),
        ) {
            MarkdownMessage(
                markdown = if (streaming) item.text + " ▍" else item.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** 命令执行卡片：header 常驻，输出可展开 */
@Composable
fun CommandExecutionCard(item: ThreadItem.CommandExecution) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder(enabled = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = item.aggregatedOutput.isNotBlank()) { expanded = !expanded }
                .padding(horizontal = 13.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                CommandStatus(item)
                if (item.aggregatedOutput.isNotBlank()) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (expanded && item.aggregatedOutput.isNotBlank()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SelectionContainer {
                    Text(
                        text = item.aggregatedOutput,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandStatus(item: ThreadItem.CommandExecution) {
    when (item.status) {
        "inProgress" -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(4.dp))
            Text("执行中", style = MaterialTheme.typography.labelSmall)
        }
        "completed" -> Text(
            text = buildString {
                append("exit ${item.exitCode ?: 0}")
                item.durationMs?.let { append(" · ${it}ms") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = if ((item.exitCode ?: 0) == 0) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        else -> Text(
            text = item.status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 文件改动卡片（MVP 简化展示） */
@Composable
fun FileChangeCard(item: ThreadItem.FileChange) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.widthIn(max = 340.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder(enabled = true),
    ) {
        Column(
            Modifier
                .clickable(enabled = item.changes.isNotEmpty()) { expanded = !expanded }
                .padding(12.dp),
        ) {
            Text(
                text = if (item.changes.isEmpty()) "已修改文件" else "已修改 ${item.changes.size} 个文件",
                style = MaterialTheme.typography.labelMedium,
            )
            if (expanded) {
                item.changes.forEach { change ->
                    Text(
                        text = change,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 计划卡片 */
@Composable
fun PlanCard(item: ThreadItem.Plan) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder(enabled = true),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("计划", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 推理摘要：默认折叠 */
@Composable
fun ReasoningItem(item: ThreadItem.Reasoning) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(4.dp),
    ) {
        Text(
            text = if (expanded) "▾ thinking" else "▸ thinking",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            item.summary.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * §6.1 审批弹窗：必须四选一，不允许直接 dismiss。
 */
@Composable
fun ApprovalDialog(
    request: CodexEvent.ApprovalRequest,
    onDecision: (ApprovalDecision) -> Unit,
) {
    Dialog(onDismissRequest = { /* 协议要求必须应答，不允许点击外部关闭 */ }) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = CardDefaults.outlinedCardBorder(enabled = true),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("命令执行审批", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            text = request.command,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (request.cwd.isNotBlank()) {
                            Text(
                                text = "cwd: ${request.cwd}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (request.reason.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(request.reason, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onDecision(ApprovalDecision.Accept) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("批准本次")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onDecision(ApprovalDecision.AcceptForSession) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("批准，本会话内不再询问")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onDecision(ApprovalDecision.Decline) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("拒绝")
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { onDecision(ApprovalDecision.Cancel) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("拒绝并中断本轮", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 底部输入区：一个圆角容器，上行文本框，
 * 下行左为模型选择（§3.8/§3.9），右为上下文占用环（tokenUsage/updated）+ 发送/停止。
 */
@Composable
fun ChatInputBar(
    generating: Boolean,
    model: String,
    effort: String,
    models: List<ModelInfo>,
    onSelectConfiguration: (String, String) -> Unit,
    tokenUsage: TokenUsage?,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
) {
    val inputState = rememberTextFieldState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(
        Modifier
            .fillMaxWidth()
            // 键盘弹出时取 IME 与导航栏中较大的 inset，不能把两者相加。
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Surface(
            color = GlassFillStrong,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, GlassBorder),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)) {
                TextField(
                    state = inputState,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    placeholder = { Text("给 Codex 发消息…") },
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    ModelSelector(
                        model = model,
                        effort = effort,
                        models = models,
                        onSelect = onSelectConfiguration,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    ContextUsageRing(tokenUsage)
                    Spacer(Modifier.width(10.dp))
                    if (generating) {
                        // 不确定进度环持续旋转，明确告诉用户服务端仍在生成；中心仍可点击停止。
                        FilledIconButton(onClick = onInterrupt, modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) {
                                CircularProgressIndicator(
                                    // 环贴近按钮内沿旋转，中心只保留停止方块。
                                    modifier = Modifier.size(30.dp),
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                                    strokeWidth = 2.dp,
                                )
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp)),
                                )
                            }
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                val message = inputState.text.toString().trim()
                                if (message.isNotEmpty()) {
                                    onSend(message)
                                    inputState.edit { replace(0, length, "") }
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                }
                            },
                            enabled = inputState.text.isNotBlank(),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
