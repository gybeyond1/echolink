package com.notifysync.data

import org.json.JSONArray
import org.json.JSONObject

// ===== 请求/响应模型 =====

data class LoginRequest(
    val username: String,
    val password: String
)

data class AuthResponse(
    val token: String,
    val userId: Long,
    val username: String
)

data class DeviceInfo(
    val id: Long,
    val deviceName: String,
    val platform: String,
    val lastSeen: String?
)

data class NotificationItem(
    val id: Long,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val deviceName: String?
)

data class AppFilter(
    val packageName: String,
    val appName: String,
    var enabled: Boolean
)

data class TopicMessage(
    val id: Long,
    val topic: String,
    val title: String,
    val text: String,
    val senderName: String,
    val timestamp: Long,
    val deviceId: Long
)

// ===== JSON 解析扩展 =====

fun parseAuthResponse(json: JSONObject): AuthResponse {
    return AuthResponse(
        token = json.getString("token"),
        userId = json.getJSONObject("user").getLong("id"),
        username = json.getJSONObject("user").getString("username")
    )
}

fun parseNotifications(jsonArray: JSONArray): List<NotificationItem> {
    val list = mutableListOf<NotificationItem>()
    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        list.add(
            NotificationItem(
                id = obj.getLong("id"),
                packageName = obj.getString("package_name"),
                appName = obj.getString("app_name"),
                title = obj.optString("title", ""),
                text = obj.optString("text", ""),
                timestamp = obj.getLong("timestamp"),
                deviceName = obj.optString("device_name", null)
            )
        )
    }
    return list
}

fun parseFilters(jsonArray: JSONArray): List<AppFilter> {
    val list = mutableListOf<AppFilter>()
    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        list.add(
            AppFilter(
                packageName = obj.getString("package_name"),
                appName = obj.getString("app_name"),
                enabled = obj.getInt("enabled") == 1
            )
        )
    }
    return list
}

fun parseTopicMessages(jsonArray: JSONArray): List<TopicMessage> {
    val list = mutableListOf<TopicMessage>()
    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        list.add(
            TopicMessage(
                id = obj.getLong("id"),
                topic = obj.getString("topic"),
                title = obj.optString("title", ""),
                text = obj.optString("text", ""),
                senderName = obj.optString("sender_name", ""),
                timestamp = obj.getLong("timestamp"),
                deviceId = obj.optLong("device_id", -1)
            )
        )
    }
    return list
}

fun parseTopicMessage(json: JSONObject): TopicMessage {
    return TopicMessage(
        id = json.getLong("id"),
        topic = json.getString("topic"),
        title = json.optString("title", ""),
        text = json.optString("text", ""),
        senderName = json.optString("sender_name", ""),
        timestamp = json.getLong("timestamp"),
        deviceId = json.optLong("device_id", -1)
    )
}

// ===== WebSocket 消息 =====

data class WsMessage(
    val type: String,
    val data: JSONObject?
)
