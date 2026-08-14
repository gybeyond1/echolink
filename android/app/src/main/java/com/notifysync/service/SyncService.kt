package com.notifysync.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.notifysync.App
import com.notifysync.R
import com.notifysync.data.ApiClient
import com.notifysync.data.AuthManager
import com.notifysync.data.WebSocketClient
import com.notifysync.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class SyncService : Service(), WebSocketClient.WsEventListener {
    companion object {
        private const val TAG = "SyncService"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val SYNCED_NOTIFICATION_START_ID = 1000

        // 全局状态
        @Volatile
        var isRunning = false
            private set

        @Volatile
        var connectionStatus = "未连接"
            private set

        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }

    private var notificationIdCounter = SYNCED_NOTIFICATION_START_ID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification("正在连接..."))
        WebSocketClient.setListener(this)

        if (AuthManager.isLoggedIn) {
            WebSocketClient.connect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统用 START_STICKY 重启 Service 时 intent == null，
        // 必须重新调 startForeground，否则 Android 12+ 会崩溃
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(connectionStatus))

        if (!AuthManager.isLoggedIn) {
            stopSelf()
            return START_NOT_STICKY
        }

        // WS 断了就重连（覆盖：进程被杀重启、静默断连、onCreate 里 connect 失败等场景）
        if (!WebSocketClient.isConnected) {
            Log.i(TAG, "onStartCommand: WS not connected, connecting...")
            WebSocketClient.connect()
        }

        return START_STICKY
    }

    /**
     * 用户从最近任务列表划掉 App 时触发。
     * 用 AlarmManager 1s 后重启 Service，保证同步不断。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, SyncService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
        Log.i(TAG, "onTaskRemoved: scheduled service restart in 1s")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        connectionStatus = "已停止"
        WebSocketClient.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===== WebSocket 事件 =====

    override fun onConnected() {
        connectionStatus = "已连接"
        updateForegroundNotification("已连接 - 同步中")
        // 重连成功后重新订阅所有已保存的话题
        AuthManager.subscribedTopics.forEach { WebSocketClient.sendSubscribe(it) }
        // 补拉离线/断连期间遗漏的通知，保证跨设备实时性
        pullMissedNotifications()
    }

    // WebSocket 连接成功后，拉取本机离线期间（自上次收到之后）遗漏的通知并本地提示。
    // 过滤掉本机自己发出的通知，避免「自己通知自己」。
    private fun pullMissedNotifications() {
        scope.launch {
            try {
                // 首次（lastNotificationTs=0）只回看最近 2 分钟，避免把历史通知全部刷成提示
                val since = if (AuthManager.lastNotificationTs > 0) AuthManager.lastNotificationTs
                else System.currentTimeMillis() - 2 * 60 * 1000
                val list = ApiClient.getNotificationsSince(since)
                var maxTs = AuthManager.lastNotificationTs
                list.forEach { n ->
                    if (n.timestamp > maxTs) maxTs = n.timestamp
                    if (n.deviceId == AuthManager.deviceId) return@forEach
                    val displayTitle = if (n.title.isNotEmpty()) "[${n.appName}] ${n.title}" else "[${n.appName}]"
                    showLocalNotification(displayTitle, n.text, null)
                }
                if (maxTs > AuthManager.lastNotificationTs) AuthManager.lastNotificationTs = maxTs
            } catch (e: Exception) {
                Log.w(TAG, "pullMissedNotifications failed: ${e.message}")
            }
        }
    }

    override fun onMessage(type: String, data: JSONObject?, topic: String?) {
        when (type) {
            "notification" -> handleSyncedNotification(data)
            "topic_message" -> handleTopicMessage(topic, data)
            "connected" -> {
                connectionStatus = "已连接"
                updateForegroundNotification("已连接 - 同步中")
            }
            "subscribed" -> { /* 话题订阅成功 */ }
            "pong" -> { /* 心跳响应 */ }
        }
    }

    override fun onDisconnected(reason: String) {
        connectionStatus = "断开 - 重连中"
        updateForegroundNotification("断开 - 重连中")
    }

    override fun onError(error: String) {
        connectionStatus = "错误: $error"
        Log.e(TAG, "WebSocket error: $error")
    }

    // ===== 处理收到的通知 =====

    private fun handleSyncedNotification(data: JSONObject?) {
        if (data == null) return

        val appName = data.optString("app_name", "未知应用")
        val title = data.optString("title", "")
        val text = data.optString("text", "")
        val timestamp = data.optLong("timestamp", System.currentTimeMillis())

        // 双保险：服务器已排除本机，这里再过滤一次自己设备发出的通知
        val fromDeviceId = data.optLong("device_id", -1)
        if (fromDeviceId == AuthManager.deviceId) return

        Log.i(TAG, "Received synced notification: $appName - $title")

        val displayTitle = if (title.isNotEmpty()) "[$appName] $title" else "[$appName]"
        showLocalNotification(displayTitle, text, null)

        // 发送广播通知 UI 更新
        val broadcastIntent = Intent("com.notifysync.NOTIFICATION_RECEIVED").apply {
            putExtra("app_name", appName)
            putExtra("title", title)
            putExtra("text", text)
            putExtra("timestamp", timestamp)
        }
        sendBroadcast(broadcastIntent)
    }

    // ===== 处理话题消息 =====

    private fun handleTopicMessage(topic: String?, data: JSONObject?) {
        if (data == null) return

        // 过滤本机自己发布的消息（服务器已排除，这里双保险）
        val fromDeviceId = data.optLong("device_id", -1)
        if (fromDeviceId == AuthManager.deviceId) return

        val title = data.optString("title", "")
        val text = data.optString("text", "")
        val sender = data.optString("sender_name", "未知")
        val timestamp = data.optLong("timestamp", System.currentTimeMillis())
        val topicName = topic ?: data.optString("topic", "")

        Log.i(TAG, "Topic message on #$topicName from $sender: $title - $text")

        val displayTitle = if (title.isNotEmpty()) "[#$topicName] $title" else "[#$topicName] $sender"
        showLocalNotification(displayTitle, text, topicName)

        // 广播给 UI（话题页刷新）
        val broadcastIntent = Intent("com.notifysync.TOPIC_MESSAGE_RECEIVED").apply {
            putExtra("topic", topicName)
            putExtra("title", title)
            putExtra("text", text)
            putExtra("sender_name", sender)
            putExtra("timestamp", timestamp)
            putExtra("device_id", fromDeviceId)
        }
        sendBroadcast(broadcastIntent)
    }

    // 展示一条本地系统通知（同步通知或话题消息）
    // topic 不为空时，点击通知会打开对应的话题页
    private fun showLocalNotification(displayTitle: String, displayText: String, topic: String? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (topic != null) putExtra(MainActivity.EXTRA_TOPIC, topic)
        }
        // 用不同 requestCode 区分「普通通知」与「话题消息」，避免 PendingIntent 相互覆盖
        val requestCode = if (topic != null) 1 else 0
        val pendingIntent = PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, App.CHANNEL_NOTIFICATIONS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(displayTitle)
            .setContentText(displayText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationIdCounter++, notification)
    }

    // ===== 前台通知 =====

    private fun buildForegroundNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("NotifySync")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(text))
    }
}
