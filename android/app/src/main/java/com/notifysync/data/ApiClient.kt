package com.notifysync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
        val body = JSONObject().put("device_name", deviceName).put("platform", platform)
        return execute(buildRequest("/api/devices/register", "POST", body))
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
                lastSeen = obj.optString("last_seen", null)
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
        val json = execute(buildRequest("/api/notifications?limit=$limit&offset=$offset", "GET"))
        return parseNotifications(json.getJSONArray("notifications"))
    }

    suspend fun getNotificationsSince(timestamp: Long): List<NotificationItem> {
        val json = execute(buildRequest("/api/notifications/since/$timestamp", "GET"))
        return parseNotifications(json.getJSONArray("notifications"))
    }

    suspend fun clearAllNotifications() {
        execute(buildRequest("/api/notifications", "DELETE"))
    }

    suspend fun deleteNotification(id: Long) {
        execute(buildRequest("/api/notifications/$id", "DELETE"))
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

    suspend fun publishTopicMessage(topic: String, title: String, text: String): JSONObject {
        val body = JSONObject()
            .put("title", title)
            .put("text", text)
            .put("sender_name", AuthManager.username ?: "android")
            .put("device_id", AuthManager.deviceId)
        return execute(buildRequest("/api/topics/${java.net.URLEncoder.encode(topic, "UTF-8")}/publish", "POST", body))
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
