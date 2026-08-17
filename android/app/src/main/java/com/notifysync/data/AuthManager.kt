package com.notifysync.data

import android.content.Context
import android.content.SharedPreferences

object AuthManager {
    private const val PREFS_NAME = "notifysync_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_DEVICE_UUID = "device_uuid"
    private const val KEY_TOPICS = "subscribed_topics"
    private const val KEY_SMS_CAPTURE = "sms_capture_enabled"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_AVATAR_URL = "avatar_url"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var userId: Long
        get() = prefs.getLong(KEY_USER_ID, -1)
        set(value) = prefs.edit().putLong(KEY_USER_ID, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "http://10.0.2.2:3000") ?: "http://10.0.2.2:3000"
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    var deviceId: Long
        get() = prefs.getLong(KEY_DEVICE_ID, -1)
        set(value) = prefs.edit().putLong(KEY_DEVICE_ID, value).apply()

    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    // 上次收到同步通知的时间戳：用于 WebSocket 重连后补拉离线期间遗漏的通知
    var lastNotificationTs: Long
        get() = prefs.getLong("last_notification_ts", 0)
        set(value) = prefs.edit().putLong("last_notification_ts", value).apply()

    // 稳定的设备标识：每个安装只生成一次，用于"按设备"持久化（重登录复用同一 deviceId）
    var deviceUuid: String
        get() {
            var uuid = prefs.getString(KEY_DEVICE_UUID, null)
            if (uuid.isNullOrBlank()) {
                uuid = java.util.UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_UUID, uuid).apply()
            }
            return uuid
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_UUID, value).apply()

    // 已订阅的公共话题列表（逗号分隔存储）
    var subscribedTopics: Set<String>
        get() = prefs.getString(KEY_TOPICS, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
        set(value) = prefs.edit().putString(KEY_TOPICS, value.joinToString(",")).apply()

    // 短信验证码自动提取开关（需 READ_SMS 权限，提取到的验证码自动复制到剪贴板）
    var smsCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS_CAPTURE, false)
        set(value) = prefs.edit().putBoolean(KEY_SMS_CAPTURE, value).apply()

    // 昵称（跟用户名走，全账号同步）
    var displayName: String?
        get() = prefs.getString(KEY_DISPLAY_NAME, null)
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    // 头像 URL（服务器路径，如 /uploads/avatars/avatar_1_xxx.png）
    var avatarUrl: String?
        get() = prefs.getString(KEY_AVATAR_URL, null)
        set(value) = prefs.edit().putString(KEY_AVATAR_URL, value).apply()

    fun addTopic(topic: String) {
        val topics = subscribedTopics.toMutableSet()
        topics.add(topic.trim().lowercase())
        subscribedTopics = topics
    }

    fun removeTopic(topic: String) {
        val topics = subscribedTopics.toMutableSet()
        topics.remove(topic.trim().lowercase())
        subscribedTopics = topics
    }

    val isLoggedIn: Boolean
        get() = !token.isNullOrEmpty() && userId > 0

    fun logout() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_TOPICS)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_AVATAR_URL)
            .apply()
    }

    // 获取 WebSocket URL（附带 device_id，供服务器过滤本机自己发的通知）
    fun getWsUrl(): String {
        val httpUrl = serverUrl
        val wsUrl = if (httpUrl.startsWith("https")) {
            httpUrl.replace("https", "wss")
        } else {
            httpUrl.replace("http", "ws")
        }
        val device = if (deviceId > 0) "&device_id=$deviceId" else ""
        return "$wsUrl/ws?token=${token ?: ""}$device"
    }
}
