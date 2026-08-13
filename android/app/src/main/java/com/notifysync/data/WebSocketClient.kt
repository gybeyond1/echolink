package com.notifysync.data

import android.util.Log
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

object WebSocketClient {
    private const val TAG = "WebSocketClient"

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var listener: WsEventListener? = null
    private var isConnecting = false
    private var shouldReconnect = true

    interface WsEventListener {
        fun onConnected()
        fun onMessage(type: String, data: org.json.JSONObject?, topic: String?)
        fun onDisconnected(reason: String)
        fun onError(error: String)
    }

    fun setListener(l: WsEventListener) {
        listener = l
    }

    val isConnected: Boolean
        get() = webSocket != null && !isConnecting

    fun connect() {
        if (isConnecting || webSocket != null) return
        if (!AuthManager.isLoggedIn) return

        shouldReconnect = true
        isConnecting = true

        if (client == null) {
            client = OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }

        val url = AuthManager.getWsUrl()
        Log.i(TAG, "Connecting to $url")

        val request = Request.Builder().url(url).build()
        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                isConnecting = false
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = org.json.JSONObject(text)
                    val type = json.optString("type", "")
                    val data = if (json.has("data")) json.getJSONObject("data") else null
                    val topic = if (json.has("topic")) json.optString("topic", null) else null
                    listener?.onMessage(type, data, topic)
                } catch (e: Exception) {
                    Log.e(TAG, "Parse message error", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                cleanup()
                listener?.onDisconnected(reason)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                cleanup()
                listener?.onError(t.message ?: "Connection failed")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        isConnecting = false
        Thread {
            try {
                Thread.sleep(5000) // 5秒后重连
                if (shouldReconnect) {
                    connect()
                }
            } catch (e: InterruptedException) {
                // ignored
            }
        }.start()
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "Client disconnect")
        cleanup()
    }

    fun sendPing() {
        try {
            val ping = org.json.JSONObject().put("type", "ping")
            webSocket?.send(ping.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Send ping error", e)
        }
    }

    // ===== 公共话题 =====

    fun sendSubscribe(topic: String) {
        sendJson(org.json.JSONObject().put("type", "subscribe").put("topic", topic))
    }

    fun sendUnsubscribe(topic: String) {
        sendJson(org.json.JSONObject().put("type", "unsubscribe").put("topic", topic))
    }

    fun sendPublish(topic: String, title: String, text: String) {
        sendJson(
            org.json.JSONObject()
                .put("type", "publish")
                .put("topic", topic)
                .put("title", title)
                .put("text", text)
                .put("sender_name", AuthManager.username ?: "android")
        )
    }

    private fun sendJson(json: org.json.JSONObject) {
        try {
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Send message error", e)
        }
    }

    private fun cleanup() {
        webSocket = null
        isConnecting = false
    }
}
