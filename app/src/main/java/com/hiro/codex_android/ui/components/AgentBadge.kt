package com.hiro.codex_android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiro.codex_android.data.AgentType

/** Agent 类型的字母徽章：彩色圆 + 单字母，用来在卡片/设置里区分 codex、kimi 等。 */
@Composable
fun AgentBadge(type: AgentType, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(
        modifier = modifier.size(size).background(Color(type.badgeColor), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = type.badgeLetter,
            color = Color.White,
            fontSize = (size.value * 0.48f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
