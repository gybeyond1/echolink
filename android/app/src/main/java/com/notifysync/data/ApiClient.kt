package com.notifysync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // 本地内存缓存：第一次拉取后缓存，切换 tab / 重进页面即时显示，无需重新联网
    var cachedTopics: List<MyTopic>? = null
        private set
    var cachedFriends: List<Friend>? = null
        private set

    private fun buildRequest(path: String, method: String, body: JSONObject? = null, withAuth: Boolean = true): Request {
        val url = "${AuthManager.serverUrl}$path"
        val builder = Request.Builder().url(url)

        if (withAuth && AuthManager.token != null) {
            builder.addHeader("Authorization", "Bearer ${AuthManager.token}")
        }
        builder.addHeader("Content-Type", "application/json")

        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> {
                val bodyStr = (body ?: JSONObject()).toString()
                builder.post(bodyStr.toRequestBody(JSON_TYPE))
            }
            "PUT" -> {
                val bodyStr = (body ?: JSONObject()).toString()
                builder.put(bodyStr.toRequestBody(JSON_TYPE))
            }
        }

        return builder.build()
    }

    private suspend fun execute(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)
            if (!response.isSuccessful) {
                throw ApiException(response.code, json.optString("error", "Request failed"))
            }
            json
        }
    }

    // ===== 认证 =====

    suspend fun register(username: String, password: String): AuthResponse {
        val body = JSONObject().put("username", username).put("password", password)
        val json = execute(buildRequest("/api/auth/register", "POST", body, withAuth = false))
        return parseAuthResponse(json)
    }

    suspend fun login(username: String, password: String): AuthResponse {
        val body = JSONObject().put("username", username).put("password", password)
        val json = execute(buildRequest("/api/auth/login", "POST", body, withAuth = false))
        return parseAuthResponse(json)
    }

    // ===== 设备 =====

    suspend fun registerDevice(deviceName: String, platform: String = "android"): JSONObject {
        val body = JSONObject()
            .put("device_name", deviceName)
            .put("platform", platform)
            .put("client_id", AuthManager.deviceUuid)
        return execute(buildRequest("/api/devices/register", "POST", body))
    }

    // 重命名当前设备（服务器更新 devices 表，通知里的设备名随 JOIN 实时更新）
    suspend fun renameDevice(deviceId: Long, newName: String): JSONObject {
        val body = JSONObject().put("device_name", newName)
        return execute(buildRequest("/api/devices/$deviceId/name", "PUT", body))
    }

    suspend fun getDevices(): List<DeviceInfo> {
        val json = execute(buildRequest("/api/devices", "GET"))
        val arr = json.getJSONArray("devices")
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            DeviceInfo(
                id = obj.getLong("id"),
                deviceName = obj.getString("device_name"),
                platform = obj.optString("platform", "android"),
                lastSeen = obj.optNullable("last_seen")
            )
        }
    }

    // ===== 通知 =====

    suspend fun sendNotification(
        packageName: String,
        appName: String,
        title: String,
        text: String,
        timestamp: Long
    ): JSONObject {
        val body = JSONObject()
            .put("package_name", packageName)
            .put("app_name", appName)
            .put("title", title)
            .put("text", text)
            .put("timestamp", timestamp)
            .put("device_id", AuthManager.deviceId)
        return execute(buildRequest("/api/notifications", "POST", body))
    }

    suspend fun getNotifications(limit: Int = 50, offset: Int = 0): List<NotificationItem> {
        val dev = AuthManager.deviceId
        val devParam = if (dev > 0) "&device_id=$dev" else ""
        val json = execute(buildRequest("/api/notifications?limit=$limit&offset=$offset$devParam", "GET"))
        return parseNotifications(json.getJSONArray("notifications"))
    }

    suspend fun getNotificationsSince(timestamp: Long): List<NotificationItem> {
        val dev = AuthManager.deviceId
        val devParam = if (dev > 0) "&device_id=$dev" else ""
        val json = execute(buildRequest("/api/notifications/since/$timestamp$devParam", "GET"))
        return parseNotifications(json.getJSONArray("notifications"))
    }

    suspend fun clearAllNotifications() {
        val dev = AuthManager.deviceId
        val devParam = if (dev > 0) "?device_id=$dev" else ""
        execute(buildRequest("/api/notifications$devParam", "DELETE"))
    }

    suspend fun deleteNotification(id: Long) {
        val dev = AuthManager.deviceId
        val devParam = if (dev > 0) "?device_id=$dev" else ""
        execute(buildRequest("/api/notifications/$id$devParam", "DELETE"))
    }

    // ===== 应用过滤器 =====

    suspend fun getFilters(): List<AppFilter> {
        val json = execute(buildRequest("/api/filters", "GET"))
        return parseFilters(json.getJSONArray("filters"))
    }

    suspend fun saveFilter(packageName: String, appName: String, enabled: Boolean) {
        val body = JSONObject()
            .put("package_name", packageName)
            .put("app_name", appName)
            .put("enabled", enabled)
        execute(buildRequest("/api/filters", "POST", body))
    }

    suspend fun batchUpdateFilters(filters: List<AppFilter>) {
        val arr = JSONArray()
        filters.forEach { f ->
            arr.put(JSONObject()
                .put("package_name", f.packageName)
                .put("app_name", f.appName)
                .put("enabled", f.enabled)
            )
        }
        val body = JSONObject().put("filters", arr)
        execute(buildRequest("/api/filters/batch", "POST", body))
    }

    suspend fun deleteFilter(packageName: String) {
        execute(buildRequest("/api/filters/${java.net.URLEncoder.encode(packageName, "UTF-8")}", "DELETE"))
    }

    // ===== 公共话题 =====

    suspend fun publishTopicMessage(
        topic: String,
        title: String,
        text: String,
        mediaType: String = "text",
        mediaUrl: String? = null,
        mediaName: String? = null,
        mediaSize: Long = 0
    ): JSONObject {
        val body = JSONObject()
            .put("title", title)
            .put("text", text)
            .put("sender_name", AuthManager.username ?: "android")
            .put("device_id", AuthManager.deviceId)
            .put("device_name", AuthManager.deviceName ?: "")
            .put("media_type", mediaType)
        if (mediaUrl != null) body.put("media_url", mediaUrl)
        if (mediaName != null) body.put("media_name", mediaName)
        if (mediaSize > 0) body.put("media_size", mediaSize)
        return execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/publish", "POST", body))
    }

    // 上传话题媒体（图片/语音/文件），返回 { url, name, size, type }
    suspend fun uploadTopicMedia(topic: String, file: java.io.File, kind: String): JSONObject {
        val url = "${AuthManager.serverUrl}/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/media?kind=$kind"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("*/*".toMediaType())
            )
            .build()
        val request = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer ${AuthManager.token}")
            .post(body)
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (!response.isSuccessful) {
                    throw ApiException(response.code, json.optString("error", "upload failed"))
                }
                json
            }
        }
    }

    suspend fun getTopicMessages(topic: String, limit: Int = 50): List<TopicMessage> {
        val json = execute(
            buildRequest(
                "/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/messages?limit=$limit",
                "GET"
            )
        )
        return parseTopicMessages(json.getJSONArray("messages"))
    }

    // 删除单条话题消息
    suspend fun deleteTopicMessage(topic: String, id: Long) {
        execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/messages/$id", "DELETE"))
    }

    // 标记私聊消息已读（仅 dm），通知对方把「单勾」升级为「双勾」回执
    suspend fun markMessagesRead(topic: String, ids: List<Long>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        val body = JSONObject().put("ids", arr)
        execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/messages/read", "POST", body))
    }

    // 删除整个话题（该用户在此话题下的所有消息）
    suspend fun deleteTopic(topic: String) {
        execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}", "DELETE"))
    }

    // ===== 话题（群聊）成员 / 审批 =====

    // 创建话题（当前用户成为创建者）
    suspend fun createTopic(name: String, title: String, description: String): JSONObject {
        val body = JSONObject()
            .put("name", name)
            .put("title", title)
            .put("description", description)
        return execute(buildRequest("/api/topics", "POST", body))
    }

    // 申请加入话题（需创建者审批）
    suspend fun requestJoinTopic(name: String, message: String = ""): JSONObject {
        val body = JSONObject().put("message", message)
        return execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(name, "UTF-8")}/join", "POST", body))
    }

    // 我参与的话题列表
    suspend fun getMyTopics(): List<MyTopic> {
        val json = execute(buildRequest("/api/topics", "GET"))
        val list = parseMyTopics(json.getJSONArray("topics"))
        cachedTopics = list
        return list
    }

    // 可发现（非成员）的话题列表
    suspend fun getDiscoverTopics(query: String = ""): List<DiscoverTopic> {
        val q = if (query.isNotBlank()) "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" else ""
        val json = execute(buildRequest("/api/topics/discover$q", "GET"))
        return parseDiscoverTopics(json.getJSONArray("topics"))
    }

    // 话题成员列表
    suspend fun getTopicMembers(topic: String): List<TopicMember> {
        val json = execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/members", "GET"))
        return parseTopicMembers(json.getJSONArray("members"))
    }

    // 待审批申请（创建者/管理员）
    suspend fun getTopicRequests(topic: String): List<TopicJoinRequest> {
        val json = execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/requests", "GET"))
        return parseTopicRequests(json.getJSONArray("requests"))
    }

    // 审批通过
    suspend fun approveTopicRequest(topic: String, requestId: Long) {
        execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/requests/$requestId/approve", "POST"))
    }

    // 审批拒绝
    suspend fun rejectTopicRequest(topic: String, requestId: Long) {
        execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/requests/$requestId/reject", "POST"))
    }

    // 审批忽略（保留申请记录但不处理，列表不再提示）
    suspend fun ignoreTopicRequest(topic: String, requestId: Long) {
        execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/requests/$requestId/ignore", "POST"))
    }

    // 退出话题（成员）
    suspend fun leaveTopic(topic: String) {
        execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/leave", "POST"))
    }

    // ===== 好友（通讯录） =====

    // 搜索用户（加好友用）
    suspend fun searchUsers(q: String): List<SearchUser> {
        val json = execute(buildRequest("/api/friends/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}", "GET"))
        return parseSearchUsers(json.getJSONArray("users"))
    }

    // 发送好友申请
    suspend fun sendFriendRequest(username: String, message: String = ""): JSONObject {
        val body = JSONObject().put("username", username).put("message", message)
        return execute(buildRequest("/api/friends/requests", "POST", body))
    }

    // 我收到的好友申请（含全部状态，UI 过滤 pending）
    suspend fun getFriendRequests(): List<FriendRequest> {
        val json = execute(buildRequest("/api/friends/requests", "GET"))
        return parseFriendRequests(json.getJSONArray("incoming"))
    }

    suspend fun acceptFriendRequest(id: Long) {
        execute(buildRequest("/api/friends/requests/$id/accept", "POST"))
    }

    suspend fun rejectFriendRequest(id: Long) {
        execute(buildRequest("/api/friends/requests/$id/reject", "POST"))
    }

    suspend fun ignoreFriendRequest(id: Long) {
        execute(buildRequest("/api/friends/requests/$id/ignore", "POST"))
    }

    // 好友列表
    suspend fun getFriends(): List<Friend> {
        val json = execute(buildRequest("/api/friends", "GET"))
        val list = parseFriends(json.getJSONArray("friends"))
        cachedFriends = list
        return list
    }

    // 删除好友（双向）
    suspend fun deleteFriend(username: String) {
        execute(buildRequest("/api/friends/${java.net.URLEncoder.encode(username, "UTF-8")}", "DELETE"))
    }

    // 打开/创建与好友的私聊会话，返回 (topic名, 对方展示名)
    suspend fun openFriendChat(username: String): Pair<String, String> {
        val json = execute(buildRequest("/api/friends/chat/${java.net.URLEncoder.encode(username, "UTF-8")}", "POST"))
        return Pair(json.getString("topic"), json.optString("display_name", json.getString("title")))
    }

    // 统一「新的申请」汇总（好友申请 + 我创建话题的加群申请）
    suspend fun getAllRequests(): UnifiedRequests {
        val json = execute(buildRequest("/api/requests", "GET"))
        return parseUnifiedRequests(json)
    }

    // ===== 用户资料 =====

    // 获取当前用户资料（昵称 + 头像）
    suspend fun getProfile(): JSONObject {
        return execute(buildRequest("/api/user/profile", "GET"))
    }

    // 修改昵称（全账号同步）
    suspend fun updateNickname(name: String): JSONObject {
        val body = JSONObject().put("display_name", name)
        return execute(buildRequest("/api/user/nickname", "PUT", body))
    }

    // 上传头像（multipart file）
    suspend fun uploadAvatar(file: java.io.File): JSONObject {
        val url = "${AuthManager.serverUrl}/api/user/avatar"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("image/*".toMediaType())
            )
            .build()
        val request = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer ${AuthManager.token}")
            .post(body)
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (!response.isSuccessful) {
                    throw ApiException(response.code, json.optString("error", "upload failed"))
                }
                json
            }
        }
    }

    // 构建头像完整 URL（服务器路径 -> 完整 URL）
    fun fullAvatarUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return "${AuthManager.serverUrl}$path"
    }

    // ===== 健康检查 =====

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${AuthManager.serverUrl}/health")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}

class ApiException(val code: Int, val error: String) : Exception(error)
