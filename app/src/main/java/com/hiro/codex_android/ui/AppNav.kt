package com.hiro.codex_android.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hiro.codex_android.ui.chat.ChatScreen
import com.hiro.codex_android.ui.settings.SettingsScreen
import com.hiro.codex_android.ui.threads.ThreadListScreen

object Routes {
    const val THREADS = "threads"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{profileId}/{threadId}"

    /** threadId 为 "new" 表示新会话；profileId 决定走哪个 Agent 连接 */
    fun chat(profileId: String, threadId: String) = "chat/$profileId/$threadId"
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun AppNav() {
    val nav = rememberNavController()
    SharedTransitionLayout {
        val sharedTransitionScope: SharedTransitionScope = this
        NavHost(navController = nav, startDestination = Routes.THREADS) {
            composable(Routes.THREADS) {
                ThreadListScreen(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this,
                    onOpenThread = { profileId, threadId -> nav.navigate(Routes.chat(profileId, threadId)) },
                    onNewThread = { profileId -> nav.navigate(Routes.chat(profileId, "new")) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(
                route = Routes.CHAT,
                arguments = listOf(
                    navArgument("profileId") { type = NavType.StringType },
                    navArgument("threadId") { type = NavType.StringType },
                ),
            ) { entry ->
                ChatScreen(
                    profileIdArg = entry.arguments?.getString("profileId").orEmpty(),
                    threadIdArg = entry.arguments?.getString("threadId") ?: "new",
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this,
                    onBack = { nav.popBackStack() },
                    onOpenThread = { threadId ->
                        val profileId = entry.arguments?.getString("profileId").orEmpty()
                        nav.navigate(Routes.chat(profileId, threadId))
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
