package com.notifysync.service

import com.notifysync.data.optNullable

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
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
        // P2P 打洞信令接收（好友私聊直传文件；工厂幂等初始化）
        com.notifysync.data.P2pManager.init(this)

        if (AuthManager.isLoggedIn) {
            WebSocketClient.connect()
        }

        // 短信验证码监听（按开关 + 权限决定是否启动）
        SmsCodeWatcher.sync(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统用 START_STICKY 重启 Service 时 intent == null，
        // 必须重新调 startForeground，否则 Android 12+ 会崩溃
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(connectionStatus))

        if (!AuthManager.isLoggedIn) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 账号隔离：连接若还挂在旧账号 token 上（切换账号后未重建）→ 强制用当前账号重连
        if (WebSocketClient.isStaleAccount()) {
            Log.w(TAG, "onStartCommand: WS belongs to another account, reconnecting")
            WebSocketClient.forceReconnect()
        } else if (!WebSocketClient.isConnected) {
            // WS 断了就重连（覆盖：进程被杀重启、静默断连、onCreate 里 connect 失败等场景）
            Log.i(TAG, "onStartCommand: WS not connected, connecting...")
            WebSocketClient.connect()
        }

        // 设置页改了短信开关后通过 start() 触发到这里，同步启停短信监听
        SmsCodeWatcher.sync(this)

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
        SmsCodeWatcher.stop(this)
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
                // 补拉窗口最多回看 10 分钟：离线太久只提示最近的消息（其余去通知页看），
                // 避免「一窝蜂」式弹出一大堆过期通知
                val tenMinAgo = System.currentTimeMillis() - 10 * 60 * 1000
                val since = when {
                    AuthManager.lastNotificationTs <= 0 -> System.currentTimeMillis() - 2 * 60 * 1000
                    AuthManager.lastNotificationTs < tenMinAgo -> tenMinAgo
                    else -> AuthManager.lastNotificationTs
                }
                val list = ApiClient.getNotificationsSince(since)
                var maxTs = AuthManager.lastNotificationTs
                list.forEach { n ->
                    if (n.timestamp > maxTs) maxTs = n.timestamp
                    if (n.deviceId == AuthManager.deviceId) return@forEach
                    // 短信验证码：自动复制到剪贴板
                    if (n.packageName == "com.android.sms" || n.appName == "短信验证码") {
                        val code = Regex("(?:验证码\\s*)?([0-9]{4,8})").find(n.title)?.groupValues?.get(1)
                        if (code != null) copyCodeToClipboard(code)
                        val displayTitle = if (n.title.isNotEmpty()) n.title else "短信验证码"
                        val displayText = if (code != null) "${n.text}\n验证码 $code 已复制到剪贴板" else n.text
                        showLocalNotification(displayTitle, displayText, null)
                    } else {
                        val displayTitle = if (n.title.isNotEmpty()) "[${n.appName}] ${n.title}" else "[${n.appName}]"
                        showLocalNotification(displayTitle, n.text, null)
                    }
                }
                if (maxTs > AuthManager.lastNotificationTs) AuthManager.lastNotificationTs = maxTs
            } catch (e: Exception) {
                Log.w(TAG, "pullMissedNotifications failed: ${e.message}")
            }
        }
    }

    override fun onMessage(type: String, data: JSONObject?, topic: String?) {
        // 账号隔离：连接若还挂在旧账号的 token 上，其推送一律丢弃并切回当前账号重连
        if (type != "connected" && type != "subscribed" && type != "pong" && WebSocketClient.isStaleAccount()) {
            Log.w(TAG, "WS push belongs to another account, dropping and reconnecting")
            WebSocketClient.forceReconnect()
            return
        }
        when (type) {
            "notification" -> handleSyncedNotification(data)
            "topic_message" -> handleTopicMessage(topic, data)
            // 已读回执：对方读了你发出的私聊消息 → 通知 UI 把对应气泡翻成双勾
            "message_read" -> handleMessageRead(topic, data)
            // 有人申请加我为好友 → 刷新「新的申请」+ 好友页红点，弹通知提醒
            "friend_request" -> {
                val who = data?.optString("username", "有人") ?: "有人"
                showLocalNotification("新的好友申请", "$who 请求加你为好友", null)
                sendBroadcast(Intent("com.notifysync.REQUESTS_CHANGED"))
                sendBroadcast(Intent("com.notifysync.FRIENDS_CHANGED"))
            }
            // 我的好友申请被通过 → 刷新好友列表（可能产生新私聊会话）
            "friend_accepted" -> {
                val who = data?.optString("username", "对方") ?: "对方"
                showLocalNotification("好友申请已通过", "$who 已同意你的好友申请", null)
                sendBroadcast(Intent("com.notifysync.FRIENDS_CHANGED"))
            }
            // 有人申请加入我创建的话题 → 刷新「新的申请」红点
            "topic_request" -> {
                val who = data?.optString("username", "有人") ?: "有人"
                val t = topic ?: data?.optString("topic", "") ?: ""
                showLocalNotification("新的加群申请", "$who 申请加入「$t」", null)
                sendBroadcast(Intent("com.notifysync.REQUESTS_CHANGED"))
            }
            // 我的加群申请被处理 → 刷新话题列表（通过则话题出现在列表）
            "topic_request_handled" -> {
                val name = data?.optString("topic", "") ?: ""
                val status = data?.optString("status", "") ?: ""
                if (status == "approved") showLocalNotification("加群申请已通过", "你已加入话题「$name」", name)
                else if (status == "rejected") showLocalNotification("加群申请被拒绝", "「$name」的创建者拒绝了你的申请", null)
                sendBroadcast(Intent("com.notifysync.REQUESTS_CHANGED"))
                sendBroadcast(Intent("com.notifysync.FRIENDS_CHANGED"))
            }
            // P2P 打洞信令（offer/answer），转交 P2pManager
            "p2p" -> {
                val payload = data?.optJSONObject("payload")
                if (payload != null && topic != null) {
                    val intent = Intent("com.notifysync.P2P_SIGNAL").apply {
                        putExtra("topic", topic)
                        putExtra("payload", payload.toString())
                        putExtra("from_user", data.optString("from_user", ""))
                        putExtra("from_device", data.optLong("from_device", -1))
                    }
                    sendBroadcast(intent)
                }
            }
            // 用户资料更新（昵称/头像在另一台设备修改）→ 刷新本地缓存 + 广播
            "profile_updated" -> {
                val displayName = data?.optNullable("display_name")
                val avatar = data?.optNullable("avatar")
                if (displayName != null) AuthManager.displayName = displayName
                if (avatar != null) AuthManager.avatarUrl = avatar
                sendBroadcast(Intent("com.notifysync.PROFILE_CHANGED"))
            }
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
        val packageName = data.optString("package_name", "")

        // 双保险：服务器已排除本机，这里再过滤一次自己设备发出的通知
        val fromDeviceId = data.optLong("device_id", -1)
        if (fromDeviceId == AuthManager.deviceId) return

        // 实时收到的通知推进 lastNotificationTs，
        // 防止 WS 断连重连后 pullMissedNotifications 把已收过的再拉一遍（重复弹通知）
        if (timestamp > AuthManager.lastNotificationTs) {
            AuthManager.lastNotificationTs = timestamp
        }

        Log.i(TAG, "Received synced notification: $appName - $title")

        // 短信验证码：自动复制到剪贴板 + 高优先级提示
        if (packageName == "com.android.sms" || appName == "短信验证码") {
            val code = Regex("(?:验证码\\s*)?([0-9]{4,8})").find(title)?.groupValues?.get(1)
            if (code != null) {
                copyCodeToClipboard(code)
            }
            val displayTitle = if (title.isNotEmpty()) title else "短信验证码"
            val displayText = if (code != null) "$text\n验证码 $code 已复制到剪贴板" else text
            showLocalNotification(displayTitle, displayText, null)
        } else {
            val displayTitle = if (title.isNotEmpty()) "[$appName] $title" else "[$appName]"
            showLocalNotification(displayTitle, text, null)
        }

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
        val deviceName = data.optNullable("device_name")
        val timestamp = data.optLong("timestamp", System.currentTimeMillis())
        val topicName = topic ?: data.optString("topic", "")

        Log.i(TAG, "Topic message on #$topicName from $sender: $title - $text")

        val displayTitle = if (title.isNotEmpty()) "[#$topicName] $title" else "[#$topicName] $sender"
        showLocalNotification(displayTitle, text, topicName)

        // 广播给 UI（话题页刷新），含媒体字段（图片/语音/文件实时渲染）
        val broadcastIntent = Intent("com.notifysync.TOPIC_MESSAGE_RECEIVED").apply {
            putExtra("topic", topicName)
            putExtra("title", title)
            putExtra("text", text)
            putExtra("sender_name", sender)
            putExtra("device_name", deviceName)
            putExtra("timestamp", timestamp)
            putExtra("device_id", fromDeviceId)
            putExtra("media_type", data.optString("media_type", "text"))
            if (!data.isNull("media_url")) putExtra("media_url", data.optNullable("media_url"))
            if (!data.isNull("media_name")) putExtra("media_name", data.optNullable("media_name"))
            putExtra("media_size", data.optLong("media_size", 0))
            putExtra("sender_user_id", data.optLong("user_id", 0))
            if (!data.isNull("sender_avatar")) putExtra("sender_avatar", data.optNullable("sender_avatar"))
            if (!data.isNull("sender_display_name")) putExtra("sender_display_name", data.optNullable("sender_display_name"))
            putExtra("id", data.optLong("id", 0))
        }
        sendBroadcast(broadcastIntent)
    }

    // 已读回执：对方读了你发出的私聊消息。把被读消息的 id 广播给 UI 刷新气泡。
    private fun handleMessageRead(topic: String?, data: JSONObject?) {
        if (topic == null || data == null) return
        val ids = mutableListOf<Long>()
        val arr = data.optJSONArray("ids")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val id = arr.optLong(i)
                if (id > 0) ids.add(id)
            }
        } else {
            val single = data.optLong("id", 0)
            if (single > 0) ids.add(single)
        }
        if (ids.isEmpty()) return
        val intent = Intent("com.notifysync.MESSAGE_READ").apply {
            putExtra("topic", topic)
            putExtra("ids", ids.toLongArray())
        }
        sendBroadcast(intent)
    }

    // 展示一条本地系统通知（同步通知或话题消息）
    // topic 不为空时，点击通知会打开对应的话题页
    /** 将验证码复制到剪贴板并 Toast 提示 */
    private fun copyCodeToClipboard(code: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("短信验证码", code))
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(this, "验证码 $code 已复制到剪贴板", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "copyCodeToClipboard failed: ${e.message}")
        }
    }

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
            .setContentTitle("EchoLink")
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
