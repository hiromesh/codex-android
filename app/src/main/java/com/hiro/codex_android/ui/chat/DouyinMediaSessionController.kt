package com.hiro.codex_android.ui.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * 系统授予“通知使用权”后，才能读取其他应用的媒体会话。
 * 服务本身不读取或保存通知内容；它只作为 MediaSessionManager 所要求的授权组件。
 */
class CodexMediaNotificationListener : NotificationListenerService()

/** 仅控制本机抖音相关会话，不干预音乐或其他媒体应用。 */
object DouyinMediaSessionController {
    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    private val douyinPackages = setOf(
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.zhiliaoapp.musically",
    )

    fun play(context: Context): Boolean = findDouyinController(context)?.let {
        it.transportControls.play()
        true
    } ?: false

    fun pause(context: Context): Boolean = findDouyinController(context)?.let {
        it.transportControls.pause()
        true
    } ?: false

    private fun findDouyinController(context: Context): MediaController? = runCatching {
        val listener = ComponentName(context, CodexMediaNotificationListener::class.java)
        val sessionManager = context.getSystemService(MediaSessionManager::class.java)
        sessionManager.getActiveSessions(listener).firstOrNull { it.packageName in douyinPackages }
            // 某些播放器不将会话列入普通 active sessions，但仍是系统当前媒体按键的接收者。
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sessionManager.getMediaKeyEventSession()
                    ?.let { MediaController(context, it) }
                    ?.takeIf { it.packageName in douyinPackages }
            } else {
                null
            }
    }.getOrNull()

    fun hasNotificationAccess(context: Context): Boolean {
        val listener = ComponentName(context, CodexMediaNotificationListener::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS,
        ).orEmpty()
        return enabled.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == listener }
    }

    fun openNotificationAccessSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
