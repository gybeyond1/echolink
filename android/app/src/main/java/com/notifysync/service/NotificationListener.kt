package com.notifysync.service

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notifysync.data.ApiClient
import com.notifysync.data.AppFilterStore
import com.notifysync.data.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {
    companion object {
        private const val TAG = "NotifyListener"
        private const val OWN_PACKAGE = "com.notifysync"

        // 缓存已发送通知的 key，避免重复发送
        private val sentKeys = mutableSetOf<String>()
        private const val MAX_CACHE_SIZE = 200

        // 检查通知监听权限是否已授予
        fun isListenerEnabled(context: android.content.Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.split(":").any {
                val cn = android.content.ComponentName.unflattenFromString(it)
                cn?.packageName == context.packageName
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppFilterStore.init(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName

        // 忽略自己的通知
        if (packageName == OWN_PACKAGE) return

        // 忽略系统通知（可选）
        if (packageName == "android" || packageName.startsWith("com.android.systemui")) return

        // 检查是否已登录
        if (!AuthManager.isLoggedIn) return

        // 应用过滤：若已开启过滤，只同步白名单内的应用
        if (!AppFilterStore.isAllowed(packageName)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        val displayText = if (bigText.isNotEmpty()) bigText else text

        // 跳过空通知
        if (title.isEmpty() && displayText.isEmpty()) return

        // 去重：使用 packageName + title + text + 时间窗口
        val dedupKey = "$packageName|$title|$displayText"
        if (sentKeys.contains(dedupKey)) return

        sentKeys.add(dedupKey)
        if (sentKeys.size > MAX_CACHE_SIZE) {
            // 清理旧数据
            val toRemove = sentKeys.toList().take(MAX_CACHE_SIZE / 2)
            toRemove.forEach { sentKeys.remove(it) }
        }

        val appName = getAppName(packageName)
        val timestamp = sbn.postTime

        Log.i(TAG, "Notification from $packageName ($appName): $title - $displayText")

        scope.launch {
            try {
                ApiClient.sendNotification(
                    packageName = packageName,
                    appName = appName,
                    title = title,
                    text = displayText,
                    timestamp = timestamp
                )
                Log.d(TAG, "Notification sent to server")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 通知被移除时的处理（可选）
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
