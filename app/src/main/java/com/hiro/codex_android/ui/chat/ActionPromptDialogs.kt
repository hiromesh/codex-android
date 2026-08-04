package com.hiro.codex_android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hiro.codex_android.data.model.ReviewTarget

@Composable
fun ActionPromptDialogs(
    prompt: PendingActionPrompt?,
    onDismiss: () -> Unit,
    onConfirmReview: (ReviewTarget) -> Unit,
    onConfirmUndo: (Int) -> Unit,
    onConfirmShell: (String) -> Unit,
) {
    when (prompt) {
        null -> Unit
        PendingActionPrompt.ReviewTarget -> ReviewTargetDialog(
            onDismiss = onDismiss,
            onConfirm = onConfirmReview,
        )
        is PendingActionPrompt.ConfirmUndo -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("撤销末尾 ${prompt.numTurns} 轮？") },
            text = {
                Text("只会删除对话历史，不会回滚 agent 已经改过的文件。")
            },
            confirmButton = {
                TextButton(onClick = { onConfirmUndo(prompt.numTurns) }) { Text("撤销") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            },
        )
        is PendingActionPrompt.ConfirmShell -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("在会话上下文执行 Shell？") },
            text = {
                Column {
                    Text("此命令不受沙箱限制，将以 full access 执行：")
                    Spacer(Modifier.height(8.dp))
                    Text(prompt.command, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirmShell(prompt.command) }) { Text("执行") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ReviewTargetDialog(
    onDismiss: () -> Unit,
    onConfirm: (ReviewTarget) -> Unit,
) {
    var branch by remember { mutableStateOf("main") }
    var custom by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择审查目标", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = { onConfirm(ReviewTarget.UncommittedChanges) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("未提交改动") }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = branch,
                        onValueChange = { branch = it },
                        label = { Text("相对分支") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val b = branch.trim()
                            if (b.isNotEmpty()) onConfirm(ReviewTarget.BaseBranch(b))
                        },
                    ) { Text("审查") }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("自定义指令") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = {
                            val text = custom.trim()
                            if (text.isNotEmpty()) onConfirm(ReviewTarget.Custom(text))
                        },
                    ) { Text("按指令审查") }
                }
            }
        }
    }
}
