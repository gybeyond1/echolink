package com.notifysync.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

object WebSocketClient {
    private const val TAG = "WebSocketClient"

    private const val RECONNECT_DELAY_MS = 5000L
    private const val WATCHDOG_INTERVAL_MS = 30000L

    // 主线程 Handler：只要进程活着就能回调，不受 Doze 后台线程挂起影响
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var client: OkHttpClient? = null
    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var listener: WsEventListener? = null
    @Volatile
    private var isConnecting = false
    @Volatile
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
                .pingInterval(15, TimeUnit.SECONDS)  // 15s：更快检测死连接
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

        // 启动看门狗（仅启动一次，重复调用不会叠加）
        startWatchdog()
    }

    // ===== 重连 =====
    //
    // 用 Handler.postDelayed 替代裸 Thread.sleep：
    //   - 裸线程在进程进后台/Doze 时会被挂起或杀死，导致重连永远不触发
    //   - Handler 绑定主线程 Looper，只要进程活着就能回调
    //   - 前台服务保活进程 → Handler 就能工作 → WS 持续重连
    //
    private val reconnectRunnable = Runnable {
        if (shouldReconnect && !isConnecting && webSocket == null && AuthManager.isLoggedIn) {
            Log.i(TAG, "Reconnecting...")
            connect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        isConnecting = false
        // 先清理可能已有的 pending 重连，避免叠加
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    // ===== 看门狗 =====
    //
    // 每 30s 检查一次 WS 是否还活着：
    //   - 如果 webSocket == null（已断开），主动重连
    //   - 如果长时间没有 onFailure/onClosed 回调（静默断连），也能恢复
    //
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (shouldReconnect && AuthManager.isLoggedIn) {
                if (webSocket == null && !isConnecting) {
                    Log.w(TAG, "Watchdog: WS disconnected, forcing reconnect")
                    connect()
                }
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    /**
     * 强制断开并重连（外部调用，如 SyncService.onStartCommand 发现 WS 不可用时）
     */
    fun forceReconnect() {
        Log.i(TAG, "Force reconnect requested")
        webSocket?.close(1000, "Force reconnect")
        webSocket = null
        isConnecting = false
        mainHandler.removeCallbacks(reconnectRunnable)
        connect()
    }

    fun disconnect() {
        shouldReconnect = false
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(watchdogRunnable)
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
