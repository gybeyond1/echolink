package com.echolink.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

object WebSocketClient {
    private const val TAG = "WebSocketClient"

    private const val RECONNECT_DELAY_MS = 5000L
    // 应用层心跳间隔：15s 发一次 ping（JSON 消息走数据通道，反代一定能转发）
    private const val HEARTBEAT_INTERVAL_MS = 15000L
    // 看门狗间隔：15s 检查一次连接活性
    private const val WATCHDOG_INTERVAL_MS = 15000L
    // 超过 45s 没收到 pong 判定连接已死，强制重连
    private const val PONG_TIMEOUT_MS = 45000L

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

    // 最后一次收到 pong 的时间（应用层心跳响应），0 表示尚未收到过
    @Volatile
    private var lastPongTime: Long = 0L

    // 当前 WS 连接使用的 token（账号隔离：切换账号后旧连接不再属于当前账号）
    @Volatile
    private var connectedToken: String? = null

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
        get() = webSocket != null && !isConnecting && isConnectionAlive()

    // 连接活性判定：从未收到过 pong（刚连上）视为活；否则 45s 内有 pong 才算活
    private fun isConnectionAlive(): Boolean {
        if (lastPongTime == 0L) return true
        return System.currentTimeMillis() - lastPongTime < PONG_TIMEOUT_MS
    }

    fun connect() {
        if (isConnecting || webSocket != null) return
        if (!AuthManager.isLoggedIn) return

        connectedToken = AuthManager.token
        shouldReconnect = true
        isConnecting = true

        if (client == null) {
            client = OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)  // 协议层 ping：更快检测死连接
                .build()
        }

        val url = AuthManager.getWsUrl()
        Log.i(TAG, "Connecting to $url")

        val request = Request.Builder().url(url).build()
        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                isConnecting = false
                lastPongTime = System.currentTimeMillis()
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = org.json.JSONObject(text)
                    val type = json.optString("type", "")
                    // 应用层心跳：收到 pong 更新活性时间戳
                    if (type == "pong") {
                        lastPongTime = System.currentTimeMillis()
                    }
                    val data = when {
                        json.has("data") -> json.getJSONObject("data")
                        // P2P 信令中继：payload + 发送者信息打包为 data，供 P2pManager 消费
                        type == "p2p" -> org.json.JSONObject().apply {
                            put("payload", json.optJSONObject("payload") ?: org.json.JSONObject())
                            put("from_user", json.optString("from_user", ""))
                            put("from_device", json.optLong("from_device", -1))
                        }
                        else -> null
                    }
                    val topic = if (json.has("topic")) json.optNullable("topic") else null
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

        // 启动心跳与看门狗（重复调用先 removeCallbacks，不会叠加）
        startHeartbeat()
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

    // ===== 应用层心跳 =====
    //
    // 每 15s 发一次 {"type":"ping"}，服务器回 {"type":"pong"} 更新 lastPongTime。
    // 协议层 ping/pong 帧部分反代会丢弃，应用层 JSON 消息走正常数据通道一定能到达。
    // 这是「通知一窝蜂」问题的根治手段：连接静默死亡后 45s 内必被检出并重连。
    //
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (shouldReconnect && webSocket != null) {
                sendPing()
            }
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun startHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    // ===== 看门狗 =====
    //
    // 每 15s 检查一次 WS 是否还活着：
    //   - webSocket == null（已断开）→ 主动重连
    //   - 连接对象存在但 45s 没收到 pong（静默死亡，onFailure 未触发）→ 强制断开重连
    //
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (shouldReconnect && AuthManager.isLoggedIn) {
                if (webSocket == null && !isConnecting) {
                    Log.w(TAG, "Watchdog: WS disconnected, forcing reconnect")
                    connect()
                } else if (webSocket != null && !isConnectionAlive()) {
                    Log.w(TAG, "Watchdog: pong timeout (${System.currentTimeMillis() - lastPongTime}ms), forcing reconnect")
                    forceReconnect()
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
     * 账号隔离：当前连接是否仍挂在旧账号的 token 上（切换账号后连接未重建）。
     * 此时服务器推送的都是旧账号的消息，必须丢弃并以当前账号重连。
     */
    fun isStaleAccount(): Boolean =
        webSocket != null && connectedToken != null && connectedToken != AuthManager.token

    /**
     * 强制断开并重连（看门狗检出死连接 / 外部调用）
     */
    fun forceReconnect() {
        Log.i(TAG, "Force reconnect requested")
        try {
            webSocket?.cancel()  // cancel 立即断开，不等待 close 握手（死连接握不了手）
        } catch (_: Exception) { /* ignore */ }
        webSocket = null
        isConnecting = false
        lastPongTime = 0L
        mainHandler.removeCallbacks(reconnectRunnable)
        connect()
    }

    fun disconnect() {
        shouldReconnect = false
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.removeCallbacks(heartbeatRunnable)
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

    // P2P 打洞信令（offer/answer/ice 等），服务器按话题转发给对方设备
    fun sendP2pSignal(topic: String, payload: org.json.JSONObject) {
        sendJson(
            org.json.JSONObject()
                .put("type", "p2p")
                .put("topic", topic)
                .put("payload", payload)
        )
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
