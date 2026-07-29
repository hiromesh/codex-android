package com.hiro.codex_android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import com.hiro.codex_android.data.ServiceLocator
import com.hiro.codex_android.ui.AppNav
import com.hiro.codex_android.ui.theme.CodexandroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            CodexandroidTheme {
                // 所有半透明 surface 共用这一层安静的深蓝灰背景，形成克制的玻璃层次。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF10151E),
                                    Color(0xFF0B0E13),
                                    Color(0xFF0D121A),
                                ),
                            ),
                        ),
                ) {
                    AppNav()
                }
            }
        }
    }
}
