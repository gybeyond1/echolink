package com.notifysync.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * P2P 打洞直传文件（WebRTC DataChannel）
 *
 * 设计：
 * - 仅用于两人私聊（dm 话题）：文件字节走设备直连，不占服务器带宽
 * - 信令（offer/answer，含 ICE candidate 的完整 SDP）经服务器 WS 中继，只有几 KB
 * - 非 trickle ICE：等 ICE gathering 完成后整体发送 SDP，无需单独 candidate 交换
 * - 30 秒内打洞不成功 → 自动回退 HTTP 上传（服务器中转兜底）
 * - 消息里 media_url 存 "p2p:<fileKey>"；接收端文件落地 filesDir/p2p/<fileKey>
 *   渲染时从本地读，另一台设备没有该文件时提示去原设备查看
 */
object P2pManager {
    private const val TAG = "P2pManager"

    // 从开始发送到 DataChannel 打开的超时，超时回退 HTTP 上传
    private const val HOLE_PUNCH_TIMEOUT_MS = 30_000L
    // ICE gathering 完成的等待上限（一般 1~3s，公网 STUN 环境下足够）
    private const val ICE_GATHER_TIMEOUT_MS = 10_000L
    // DataChannel 发送缓冲高水位：超过则暂停发送，避免 OOM
    private const val BUFFER_HIGH_WATER = 4L * 1024 * 1024
    private const val CHUNK_SIZE = 16 * 1024

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var factory: PeerConnectionFactory? = null
    @Volatile
    private var appContext: Context? = null

    // 进行中的发送会话（fileKey → session）
    private val sendSessions = ConcurrentHashMap<String, SendSession>()
    // 进行中的接收会话
    private val recvSessions = ConcurrentHashMap<String, RecvSession>()

    val isReady: Boolean get() = appContext != null && factory != null

    /** p2p:<fileKey> → 本地落地文件（不存在返回 null） */
    fun localP2pFile(context: Context, url: String?): File? {
        if (url == null || !url.startsWith("p2p:")) return null
        val key = url.removePrefix("p2p:")
        if (key.isEmpty() || key.contains("..") || key.contains("/")) return null
        val f = File(File(context.filesDir, "p2p"), key)
        return if (f.exists()) f else null
    }

    /** SyncService 启动时调用：初始化 WebRTC 工厂并注册信令接收器（幂等） */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            try {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext!!)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                Log.i(TAG, "PeerConnectionFactory initialized")
            } catch (e: Exception) {
                Log.e(TAG, "PeerConnectionFactory init failed: ${e.message}")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val payload = intent?.getStringExtra("payload") ?: return
                    val topic = intent.getStringExtra("topic") ?: return
                    try {
                        onSignal(topic, JSONObject(payload))
                    } catch (e: Exception) {
                        Log.e(TAG, "signal parse error: ${e.message}")
                    }
                }
            }
            appContext!!.registerReceiver(
                receiver,
                IntentFilter("com.notifysync.P2P_SIGNAL"),
                Context.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun signal(topic: String, payload: JSONObject) {
        WebSocketClient.sendP2pSignal(topic, payload)
    }

    private fun newConnection(observer: PeerConnection.Observer): PeerConnection? {
        val f = factory ?: return null
        val config = PeerConnection.RtcConfiguration(iceServers).apply {
            // 打洞优先：不要求中继，直连失败也算超时回退
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        return f.createPeerConnection(config, observer)
    }

    // ===== 发送端 =====

    /**
     * P2P 发送文件；30 秒打洞不成功自动走 onFallback（HTTP 上传兜底）。
     * 成功则本地留一份副本并回调 onSuccess("p2p:<fileKey>")，由调用方 publish 消息。
     */
    fun sendFileWithFallback(
        context: Context,
        topic: String,
        file: File,
        mediaType: String,
        mediaName: String,
        onFallback: () -> Unit,
        onSuccess: (p2pUrl: String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (factory == null) {
            onFallback()
            return
        }
        val fileKey = "p2p-${System.currentTimeMillis()}-${Random.nextInt(100000, 999999)}"
        val session = SendSession(topic, file, fileKey, mediaType, mediaName)
        sendSessions[fileKey] = session

        var finished = false

        fun finishFallback(reason: String) {
            if (finished) return
            finished = true
            Log.w(TAG, "P2P fallback ($fileKey): $reason")
            session.cleanup()
            sendSessions.remove(fileKey)
            mainHandler.post { onFallback() }
        }

        fun finishError(msg: String) {
            if (finished) return
            finished = true
            session.cleanup()
            sendSessions.remove(fileKey)
            mainHandler.post { onError(msg) }
        }

        // 发送方本地副本（渲染自己发的 P2P 消息用）
        val p2pDir = File(context.filesDir, "p2p").apply { mkdirs() }
        val localCopy = File(p2pDir, fileKey)

        // 30s 总超时：打洞不成功即回退
        mainHandler.postDelayed({
            if (!session.channelOpened) finishFallback("打洞超时（30s）")
        }, HOLE_PUNCH_TIMEOUT_MS)

        session.onFallback = ::finishFallback
        session.onSuccess = { url -> mainHandler.post { onSuccess(url) } }
        session.onError = { msg -> mainHandler.post { onError(msg) } }

        Thread {
            try {
                // 1. 本地副本
                file.copyTo(localCopy, overwrite = true)

                // 2. 建 PeerConnection + DataChannel
                var gatheringDone = false
                val pcObserver = object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        if (state == PeerConnection.IceConnectionState.FAILED) finishFallback("ICE 连接失败")
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        if (state == PeerConnection.IceGatheringState.COMPLETE) {
                            synchronized(session.lock) {
                                gatheringDone = true
                                (session.lock as Object).notifyAll()
                            }
                        }
                    }
                    override fun onIceCandidate(candidate: IceCandidate?) {}
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onAddStream(stream: MediaStream?) {}
                    override fun onRemoveStream(stream: MediaStream?) {}
                    override fun onDataChannel(dc: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                }
                val pc = newConnection(pcObserver) ?: throw IllegalStateException("createPeerConnection failed")
                session.pc = pc
                val dc = pc.createDataChannel("file", DataChannel.Init().apply { ordered = true })
                session.dc = dc

                dc.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        if (dc.state() == DataChannel.State.OPEN) {
                            session.channelOpened = true
                            Thread { sendOverChannel(session) }.start()
                        } else if (dc.state() == DataChannel.State.CLOSED && !session.sentDone) {
                            finishFallback("通道提前关闭")
                        }
                    }
                    override fun onMessage(buffer: DataChannel.Buffer?) {}
                })

                // 3. offer + 等 ICE gathering 完成（非 trickle）
                val offerSdp = pc.createOfferSdp() ?: throw IllegalStateException("createOffer failed")
                pc.setLocalDescriptionSdp(offerSdp)
                synchronized(session.lock) {
                    var wait = ICE_GATHER_TIMEOUT_MS
                    while (!gatheringDone && wait > 0 && !finished) {
                        (session.lock as Object).wait(200)
                        wait -= 200
                    }
                }
                val desc = pc.localDescription ?: throw IllegalStateException("no local description")
                signal(topic, JSONObject()
                    .put("action", "offer")
                    .put("file_key", fileKey)
                    .put("file_name", mediaName)
                    .put("file_size", file.length())
                    .put("media_type", mediaType)
                    .put("sdp", desc.description)
                )
                // 等待 answer（在 onSignal 中 setRemoteDescription → 通道打开 → 发文件）
                // 超时由上面的 30s 总超时兜底
            } catch (e: Exception) {
                if (!finished) finishError("P2P 发送失败: ${e.message}")
            }
        }.start()
    }

    // 通道打开后：头部 JSON → 分块 → EOF 标记
    private fun sendOverChannel(session: SendSession) {
        val dc = session.dc ?: return
        try {
            val header = JSONObject()
                .put("type", "header")
                .put("file_key", session.fileKey)
                .put("file_name", session.mediaName)
                .put("file_size", session.file.length())
                .put("media_type", session.mediaType)
            dc.sendText(header.toString())

            val buf = ByteArray(CHUNK_SIZE)
            java.io.FileInputStream(session.file).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    // 背压：缓冲区高水位时等待对端消化
                    while (dc.bufferedAmount() > BUFFER_HIGH_WATER && dc.state() == DataChannel.State.OPEN) {
                        Thread.sleep(30)
                    }
                    if (dc.state() != DataChannel.State.OPEN) throw IllegalStateException("channel closed during send")
                    dc.sendBinary(if (n == CHUNK_SIZE) buf else buf.copyOf(n))
                }
            }
            // 等待缓冲清空再发 EOF，保证对端收齐
            var drainWait = 30_000L
            while (dc.bufferedAmount() > 0 && drainWait > 0 && dc.state() == DataChannel.State.OPEN) {
                Thread.sleep(50); drainWait -= 50
            }
            dc.sendText(JSONObject().put("type", "eof").put("file_key", session.fileKey).toString())
            session.sentDone = true
            Log.i(TAG, "P2P file sent: ${session.fileKey} (${session.file.length()}B)")
            mainHandler.postDelayed({
                session.cleanup()
                sendSessions.remove(session.fileKey)
            }, 3000)
            mainHandler.post { session.onSuccess?.invoke("p2p:${session.fileKey}") }
        } catch (e: Exception) {
            Log.e(TAG, "sendOverChannel failed: ${e.message}")
            mainHandler.post { session.onError?.invoke("P2P 传输中断: ${e.message}") }
            session.cleanup()
            sendSessions.remove(session.fileKey)
        }
    }

    private fun DataChannel.sendText(text: String) {
        send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8)), false))
    }

    private fun DataChannel.sendBinary(bytes: ByteArray) {
        send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    // 把 DataChannel.Buffer 的内容完整读出（buffer 复用，必须拷贝）
    private object ByteBufferPool {
        fun read(buffer: DataChannel.Buffer): ByteArray {
            val data = ByteArray(buffer.data.remaining())
            buffer.data.get(data)
            return data
        }
    }

    // PeerConnection.createOffer 的同步封装
    private fun PeerConnection.createOfferSdp(): SessionDescription? {
        var result: SessionDescription? = null
        val latch = Object()
        val observer = object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                result = desc
                synchronized(latch) { latch.notifyAll() }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                synchronized(latch) { latch.notifyAll() }
            }
            override fun onSetFailure(error: String?) {}
        }
        createOffer(observer, MediaConstraints())
        synchronized(latch) { latch.wait(ICE_GATHER_TIMEOUT_MS) }
        return result
    }

    private fun PeerConnection.setLocalDescriptionSdp(desc: SessionDescription) {
        val latch = Object()
        val observer = object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onSetSuccess() { synchronized(latch) { latch.notifyAll() } }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setLocalDescription failed: $error")
                synchronized(latch) { latch.notifyAll() }
            }
        }
        setLocalDescription(observer, desc)
        synchronized(latch) { latch.wait(5000) }
    }

    // ===== 接收端（信令处理） =====

    private fun onSignal(topic: String, payload: JSONObject) {
        mainHandler.post {
            val action = payload.optString("action")
            val fileKey = payload.optString("file_key")
            when (action) {
                // 发送方的 offer：建 answer 通道等收文件
                "offer" -> handleOffer(topic, fileKey, payload)
                // 对端的 answer：完成握手
                "answer" -> handleAnswer(fileKey, payload)
                "cancel" -> {
                    recvSessions.remove(fileKey)?.cleanup()
                }
            }
        }
    }

    private fun handleOffer(topic: String, fileKey: String, payload: JSONObject) {
        if (factory == null || fileKey.isEmpty()) return
        val sdp = payload.optString("sdp")
        if (sdp.isEmpty()) return
        // 同 key 重复 offer（去重）
        if (recvSessions.containsKey(fileKey)) return

        val ctx = appContext ?: return
        val session = RecvSession(topic, fileKey, payload.optString("file_name", "file"),
            payload.optLong("file_size", 0), payload.optString("media_type", "file"))
        recvSessions[fileKey] = session

        try {
            val pcObserver = object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    if (state == PeerConnection.IceConnectionState.FAILED) session.cleanup()
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) {
                        session.pc?.localDescription?.let { desc ->
                            signal(topic, JSONObject()
                                .put("action", "answer")
                                .put("file_key", fileKey)
                                .put("sdp", desc.description)
                            )
                        }
                    }
                }
                override fun onIceCandidate(candidate: IceCandidate?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {
                    dc ?: return
                    session.dc = dc
                    dc.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(previousAmount: Long) {}
                        override fun onStateChange() {
                            if (dc.state() == DataChannel.State.OPEN) session.beginReceive(ctx)
                        }
                        override fun onMessage(buffer: DataChannel.Buffer?) {
                            buffer ?: return
                            val data = ByteBufferPool.read(buffer)
                            if (!buffer.binary) {
                                // 控制消息（header/eof，JSON 文本）
                                try {
                                    val json = JSONObject(String(data, StandardCharsets.UTF_8))
                                    when (json.optString("type")) {
                                        "eof" -> session.onEof()
                                        "header" -> session.beginReceive(ctx)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "control message parse error: ${e.message}")
                                }
                            } else {
                                session.onChunk(data)
                            }
                        }
                    })
                }
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
            val pc = newConnection(pcObserver) ?: throw IllegalStateException("createPeerConnection failed")
            session.pc = pc

            // setRemote(offer) → createAnswer → setLocal →（gathering 完成时回 answer）
            Thread {
                try {
                    pc.setRemoteDescriptionSdp(SessionDescription(SessionDescription.Type.OFFER, sdp))
                    val answer = pc.createAnswerSdp() ?: throw IllegalStateException("createAnswer failed")
                    pc.setLocalDescriptionSdp(answer)
                    // gathering 完成的回调里发 answer；某些网络下 gathering 瞬间完成，
                    // 回调可能早于 setLocalDescription 结束，这里兜底补发一次
                    Thread.sleep(1500)
                    if (!session.answerSent) {
                        pc.localDescription?.let { desc ->
                            session.answerSent = true
                            signal(topic, JSONObject()
                                .put("action", "answer")
                                .put("file_key", fileKey)
                                .put("sdp", desc.description)
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "handleOffer failed: ${e.message}")
                    session.cleanup()
                    recvSessions.remove(fileKey)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "handleOffer error: ${e.message}")
            session.cleanup()
            recvSessions.remove(fileKey)
        }
    }

    private fun handleAnswer(fileKey: String, payload: JSONObject) {
        val session = sendSessions[fileKey] ?: return
        val sdp = payload.optString("sdp")
        if (sdp.isEmpty()) return
        Thread {
            try {
                session.pc?.setRemoteDescriptionSdp(SessionDescription(SessionDescription.Type.ANSWER, sdp))
            } catch (e: Exception) {
                Log.e(TAG, "handleAnswer failed: ${e.message}")
            }
        }.start()
    }

    private fun PeerConnection.createAnswerSdp(): SessionDescription? {
        var result: SessionDescription? = null
        val latch = Object()
        val observer = object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                result = desc
                synchronized(latch) { latch.notifyAll() }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { synchronized(latch) { latch.notifyAll() } }
            override fun onSetFailure(error: String?) {}
        }
        createAnswer(observer, MediaConstraints())
        synchronized(latch) { latch.wait(ICE_GATHER_TIMEOUT_MS) }
        return result
    }

    private fun PeerConnection.setRemoteDescriptionSdp(desc: SessionDescription) {
        val latch = Object()
        val observer = object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onSetSuccess() { synchronized(latch) { latch.notifyAll() } }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription failed: $error")
                synchronized(latch) { latch.notifyAll() }
            }
        }
        setRemoteDescription(observer, desc)
        synchronized(latch) { latch.wait(5000) }
    }

    // ===== 会话 =====

    private class SendSession(
        val topic: String,
        val file: File,
        val fileKey: String,
        val mediaType: String,
        val mediaName: String
    ) {
        val lock = Object()
        @Volatile var pc: PeerConnection? = null
        @Volatile var dc: DataChannel? = null
        @Volatile var channelOpened = false
        @Volatile var sentDone = false
        @Volatile var onFallback: (() -> Unit)? = null
        var onSuccess: ((String) -> Unit)? = null
        var onError: ((String) -> Unit)? = null

        fun cleanup() {
            try { dc?.close() } catch (_: Exception) {}
            try { pc?.dispose() } catch (_: Exception) {}
            dc = null
            pc = null
        }
    }

    private class RecvSession(
        val topic: String,
        val fileKey: String,
        val fileName: String,
        val fileSize: Long,
        val mediaType: String
    ) {
        @Volatile var pc: PeerConnection? = null
        @Volatile var dc: DataChannel? = null
        @Volatile var answerSent = false
        @Volatile var receiving = false
        @Volatile var out: FileOutputStream? = null
        @Volatile var received: Long = 0
        @Volatile var target: File? = null

        fun beginReceive(context: Context) {
            if (receiving) return
            receiving = true
            val dir = File(context.filesDir, "p2p").apply { mkdirs() }
            target = File(dir, fileKey)
            out = FileOutputStream(target)
            Log.i(TAG, "P2P receiving $fileKey ($fileSize bytes) as $fileName")
        }

        fun onChunk(bytes: ByteArray) {
            try {
                out?.write(bytes)
                received += bytes.size
            } catch (e: Exception) {
                Log.e(TAG, "write chunk failed: ${e.message}")
                cleanup()
            }
        }

        fun onEof() {
            try { out?.flush(); out?.close() } catch (_: Exception) {}
            out = null
            Log.i(TAG, "P2P received done: $fileKey ($received bytes)")
            mainHandler.postDelayed({ cleanup() }, 5000)
            recvSessions.remove(fileKey)
        }

        fun cleanup() {
            try { out?.close() } catch (_: Exception) {}
            out = null
            try { dc?.close() } catch (_: Exception) {}
            try { pc?.dispose() } catch (_: Exception) {}
            dc = null
            pc = null
        }
    }
}
