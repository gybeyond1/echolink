package com.echolink.service

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.echolink.App
import com.echolink.data.ApiClient
import com.echolink.data.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 短信验证码自动提取（双通道）：
 * 主通道 = SmsReceiver 广播（SMS_RECEIVED，各版本行为一致，最可靠）；
 * 兜底 = 监听系统收件箱（content://sms/inbox）变化。
 * 提取验证码后：复制到剪贴板 + 弹系统通知 + 上报服务器（跨设备同步）。
 * 开关与权限均在设置页控制（AuthManager.smsCaptureEnabled + READ_SMS/RECEIVE_SMS）。
 * 两通道通过「60s 内同验证码去重」避免重复处理。
 */
object SmsCodeWatcher {
    private const val TAG = "SmsCodeWatcher"

    @Volatile
    private var thread: HandlerThread? = null

    @Volatile
    private var observer: ContentObserver? = null

    // 已处理过的短信 id（防重复提取，只保留最近 50 条）
    private val handledIds = ArrayDeque<Long>()

    private var notificationId = 2000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean
        get() = observer != null

    /** 按「开关 + 权限」同步启停监听，常驻服务每次 onStartCommand 时调用 */
    fun sync(context: Context) {
        if (AuthManager.smsCaptureEnabled && hasPermission(context)) {
            start(context)
        } else {
            stop(context)
        }
    }

    fun start(context: Context) {
        if (observer != null) return
        try {
            val t = HandlerThread("SmsCodeWatcher").apply { start() }
            thread = t
            val handler = Handler(t.looper)
            val obs = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    checkNewSms(context.applicationContext)
                }
            }
            observer = obs
            context.applicationContext.contentResolver.registerContentObserver(
                Uri.parse("content://sms/inbox"),
                true,
                obs
            )
            Log.i(TAG, "SMS watcher started")
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
        }
    }

    /** 带 context 的停止：注销 observer 并退出线程 */
    fun stop(context: Context) {
        val obs = observer
        if (obs != null) {
            try {
                context.applicationContext.contentResolver.unregisterContentObserver(obs)
            } catch (_: Exception) { /* ignore */ }
        }
        observer = null
        stopInternal()
        Log.i(TAG, "SMS watcher stopped")
    }

    private fun stopInternal() {
        val t = thread ?: return
        thread = null
        try {
            if (t.isAlive) t.quitSafely()
        } catch (_: Exception) { /* ignore */ }
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    // ===== 收件箱检查 =====

    // 防重复处理：广播与收件箱观察者可能对同一条短信各触发一次（60s 内同验证码只处理一次）
    private var lastCode: String? = null
    private var lastCodeTime: Long = 0L

    private fun isDuplicate(code: String): Boolean {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (code == lastCode && now - lastCodeTime < 60_000L) return true
            lastCode = code
            lastCodeTime = now
            return false
        }
    }

    /**
     * 处理收到的一条短信（由 SmsReceiver 广播调用，主检测通道）：
     * 判定开关 + 权限 → 提取验证码 → 复制 + 本地通知 + 上报服务器。
     */
    fun processReceivedSms(context: Context, body: String, address: String) {
        if (!AuthManager.smsCaptureEnabled) return
        if (!hasPermission(context)) return

        val code = extractCode(body) ?: return  // 不是验证码短信，忽略
        if (isDuplicate(code)) return
        copyCode(context, code, address)
    }

    private fun checkNewSms(context: Context) {
        try {
            val cr = context.contentResolver
            val uri = Uri.parse("content://sms/inbox")
            val cursor = cr.query(
                uri,
                arrayOf("_id", "body", "address", "date"),
                null,
                null,
                "_id DESC LIMIT 1"
            ) ?: return
            cursor.use {
                if (!it.moveToFirst()) return
                val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                val address = it.getString(it.getColumnIndexOrThrow("address")) ?: ""

                synchronized(handledIds) {
                    if (handledIds.contains(id)) return
                }

                val code = extractCode(body)
                if (code == null) {
                    // 不是验证码短信，标记已处理避免重复扫描
                    remember(id)
                    return
                }

                remember(id)
                if (!isDuplicate(code)) copyCode(context, code, address)
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkNewSms failed: ${e.message}")
        }
    }

    private fun remember(id: Long) {
        synchronized(handledIds) {
            handledIds.addLast(id)
            while (handledIds.size > 50) handledIds.removeFirst()
        }
    }

    private fun copyCode(context: Context, code: String, address: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("短信验证码", code))

        val from = address.ifBlank { "短信" }
        val text = "验证码 $code 已复制到剪贴板"
        // Toast 提示
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "$from: $text", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) { /* ignore */ }

        // 系统通知提示
        try {
            val notification = NotificationCompat.Builder(context, App.CHANNEL_NOTIFICATIONS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("验证码已复制")
                .setContentText("来自 $from：$code")
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(notificationId++, notification)
        } catch (_: Exception) { /* ignore */ }

        Log.i(TAG, "SMS code extracted from $address: $code")

        // 上报到服务器，让其他设备也能收到验证码
        if (AuthManager.isLoggedIn) {
            scope.launch {
                try {
                    ApiClient.sendNotification(
                        packageName = "com.android.sms",
                        appName = "短信验证码",
                        title = "验证码 $code",
                        text = "来自 $address",
                        timestamp = System.currentTimeMillis()
                    )
                    Log.i(TAG, "SMS code reported to server for cross-device sync")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to report SMS code: ${e.message}")
                }
            }
        }
    }

    // ===== 验证码提取 =====

    /**
     * 从短信正文提取验证码：
     * 1. 优先匹配"验证码/校验码/动态码/code"等关键词前后的 4-8 位数字；
     * 2. 全文若只有唯一一段 4-8 位数字（无其他候选干扰），也直接采用；
     * 3. 多个候选时无法确定，返回 null（不误伤）。
     */
    private fun extractCode(body: String): String? {
        if (body.isBlank()) return null
        // 归一化：统一全角数字为半角，方便匹配
        val normalized = body
            .replace('０', '0').replace('１', '1').replace('２', '2').replace('３', '3').replace('４', '4')
            .replace('５', '5').replace('６', '6').replace('７', '7').replace('８', '8').replace('９', '9')

        // 关键词在前：验证码 123456 / code 123456
        val kwBefore = Regex(
            "(?:验证码|校验码|动态码|安全码|验证|code)[^0-9A-Za-z]{0,12}([0-9]{4,8})(?![0-9])",
            RegexOption.IGNORE_CASE
        )
        kwBefore.find(normalized)?.let { return it.groupValues[1] }

        // 关键词在后：123456 是您的验证码 / 123456 is your code
        val kwAfter = Regex(
            "(?<![0-9])([0-9]{4,8})(?![0-9])[^0-9A-Za-z]{0,12}(?:验证码|校验码|动态码|安全码|code)",
            RegexOption.IGNORE_CASE
        )
        kwAfter.find(normalized)?.let { return it.groupValues[1] }

        // 兜底：全文唯一一段 4-8 位数字
        val candidates = Regex("(?<![0-9])([0-9]{4,8})(?![0-9])").findAll(normalized).toList()
        if (candidates.size == 1) return candidates[0].groupValues[1]

        return null
    }
}
