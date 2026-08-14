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
    val deviceName: String?,
    val deviceId: Long = -1
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
    val deviceId: Long,
    val deviceName: String? = null,
    val mediaType: String = "text",   // "text" | "voice" | "image" | "file"
    val mediaUrl: String? = null,
    val mediaName: String? = null,
    val mediaSize: Long = 0
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
                deviceName = obj.optString("device_name", null),
                deviceId = obj.optLong("device_id", -1)
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
                deviceId = obj.optLong("device_id", -1),
                deviceName = obj.optString("device_name", null),
                mediaType = obj.optString("media_type", "text"),
                mediaUrl = obj.optString("media_url", null),
                mediaName = obj.optString("media_name", null),
                mediaSize = obj.optLong("media_size", 0)
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
        deviceId = json.optLong("device_id", -1),
        deviceName = json.optString("device_name", null),
        mediaType = json.optString("media_type", "text"),
        mediaUrl = json.optString("media_url", null),
        mediaName = json.optString("media_name", null),
        mediaSize = json.optLong("media_size", 0)
    )
}

// ===== 话题（群聊）模型 =====

// 我参与的话题
data class MyTopic(
    val name: String,
    val myRole: String,        // "owner" | "member"
    val messageCount: Int,
    val pendingRequests: Int,   // 仅创建者可见：待审批数量
    val ownerName: String?,
    val lastMessage: String? = null
)

// 可发现（非成员）的话题
data class DiscoverTopic(
    val name: String,
    val ownerName: String?,
    val memberCount: Int,
    val messageCount: Int
)

// 话题成员
data class TopicMember(
    val userId: Long,
    val username: String,
    val role: String,
    val joinedAt: String?
)

// 加入申请
data class TopicJoinRequest(
    val id: Long,
    val userId: Long,
    val username: String,
    val status: String,        // "pending" | "approved" | "rejected"
    val message: String?,
    val requestedAt: String?
)

fun parseMyTopics(jsonArray: JSONArray): List<MyTopic> {
    val list = mutableListOf<MyTopic>()
    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        list.add(
            MyTopic(
                name = obj.getString("name"),
                myRole = obj.optString("my_role", "member"),
                messageCount = obj.optInt("message_count", 0),
                pendingRequests = obj.optInt("pending_requests", 0),
                ownerName = obj.optString("owner_name", null),
                lastMessage = obj.optString("last_message", null)
            )
        )
    }
    return list
}

fun parseDiscoverTopics(jsonArray: JSONArray): List<DiscoverTopic> {
    val list = mutableListOf<DiscoverTopic>()
    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        list.add(
            DiscoverTopic(
                name = obj.getString("name"),
                ownerName = obj.optString("owner_name", null),
                memberCount = obj.optInt("member_count", 0),
                messageCount = obj.optInt("message_count", 0)
            )
        )
    }
    return list
}

fun parseTopicMembers(jsonArray: JSONArray): List<TopicMember> {
    val list = mutableListOf<TopicMember>()
    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        list.add(
            TopicMember(
                userId = obj.getLong("user_id"),
                username = obj.optString("username", "?"),
                role = obj.optString("role", "member"),
                joinedAt = obj.optString("joined_at", null)
            )
        )
    }
    return list
}

fun parseTopicRequests(jsonArray: JSONArray): List<TopicJoinRequest> {
    val list = mutableListOf<TopicJoinRequest>()
    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        list.add(
            TopicJoinRequest(
                id = obj.getLong("id"),
                userId = obj.getLong("user_id"),
                username = obj.optString("username", "?"),
                status = obj.optString("status", "pending"),
                message = obj.optString("message", null),
                requestedAt = obj.optString("requested_at", null)
            )
        )
    }
    return list
}

// ===== WebSocket 消息 =====

data class WsMessage(
    val type: String,
    val data: JSONObject?
)
