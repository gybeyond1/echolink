package com.echolink.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.echolink.R
import com.echolink.data.ApiClient
import com.echolink.data.AuthManager
import com.echolink.data.AvatarLoader
import com.echolink.data.P2pManager
import com.echolink.data.TopicMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.echolink.util.MediaCacheManager

class TopicAdapter(
    private val onItemLongClick: (TopicMessage) -> Unit,
    private val onItemClick: (TopicMessage) -> Unit,
    private val onImageClick: ((TopicMessage) -> Unit)? = null,
    private val onVideoClick: ((TopicMessage) -> Unit)? = null,
    private val onAvatarClick: ((TopicMessage) -> Unit)? = null,
    private val onMpButtonClick: ((String, String) -> Unit)? = null
) : RecyclerView.Adapter<TopicAdapter.ViewHolder>() {

    private val items = mutableListOf<TopicMessage>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 当前正在播放语音的图标（用于停止动画） */
    private var currentPlayingIcon: ImageView? = null
    private var currentMediaPlayer: MediaPlayer? = null

    /** 是否显示已读回执（仅 dm 私聊开启）：自己发出的消息显示单勾/双勾 */
    var showReadReceipts: Boolean = false

    /**
     * 会话「对方」头像 URL（仅私聊 dm 有意义）：当某条消息的 sender_avatar 为空
     * （例如历史消息在对方设头像之前发出）时，回退到对方当前头像，避免出现「没头像」。
     */
    var peerAvatarUrl: String? = null

    /** 是否为私聊会话：私聊里「非自己」的消息头像一律回退到 peerAvatarUrl（对方实时头像），
     *  彻底杜绝历史消息头像缺失导致的「首条没头像」。群聊/设备会话不设。 */
    var isDm: Boolean = false

    /** 是否为留言板会话：访客消息统一显示留言板新拟态图标，不加载 sender_avatar */
    var isMessageWall: Boolean = false

    /** 是否为 MoviePilot 会话：对方消息统一显示 MP 新拟态图标 */
    var isMoviePilot: Boolean = false

    /** 是否为通知会话：对方消息统一显示通知新拟态图标 */
    var isNotification: Boolean = false

    /** 是否为我的设备会话：对方消息统一显示设备新拟态图标 */
    var isMyDevice: Boolean = false

    /** 一对一对话隐藏头像（Telegram风格）：私聊/MP/我的设备/通知/留言板都去掉头像，只有群组保留头像区分发送者 */
    private val hideAvatar: Boolean
        get() = isDm || isMessageWall || isMoviePilot || isNotification || isMyDevice

    var selectionMode = false
        private set
    private val selected = mutableSetOf<Long>()

    fun setItems(list: List<TopicMessage>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        selectionMode = false
        // DM 会话：若 peerAvatarUrl 尚未设置（例如从好友页直接进、或 showChatMode 还没传），
        // 从已加载消息里自愈补全对方头像：取第一条「非自己且自带头像」的消息。
        if (isDm && peerAvatarUrl.isNullOrBlank()) {
            val peer = items.firstOrNull { !it.senderAvatar.isNullOrBlank() && !isSelfMessage(it) }
            if (peer != null) {
                peerAvatarUrl = ApiClient.fullAvatarUrl(peer.senderAvatar)
            }
        }
        notifyDataSetChanged()
    }

    /** 是否为「我」发出的消息：优先用 user_id 严格判定；当 AuthManager.userId 未初始化(<=0)
     *  或消息 user_id 无效时，回退用 senderName 与当前用户名比对，避免刷新后身份错乱。 */
    private fun isSelfMessage(item: TopicMessage): Boolean {
        // 我的设备会话：用 device_id 区分当前设备和其他设备
        val isDeviceTopic = isMyDevice || item.topic.endsWith("-devices")
        if (isDeviceTopic) {
            val result = if (item.deviceId > 0 && AuthManager.deviceId > 0) {
                item.deviceId == AuthManager.deviceId
            } else {
                true // device_id 无效时默认是自己的（同一账号）
            }
            com.echolink.util.DebugLogger.d("isSelfMessage",
                "DEVICE topic=${item.topic} isMyDevice=$isMyDevice result=$result itemDevId=${item.deviceId} authDevId=${AuthManager.deviceId} sender=${item.senderName}")
            return result
        }
        // 其他会话：优先 user_id，回退 senderName
        val result = if (item.senderUserId > 0 && AuthManager.userId > 0) {
            item.senderUserId == AuthManager.userId
        } else {
            val me = AuthManager.username
            !me.isNullOrBlank() && item.senderName == me
        }
        return result
    }

    fun appendItems(list: List<TopicMessage>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    /** 用真实消息替换发送中的临时消息（按临时 id 匹配） */
    fun replaceMessage(tempId: Long, realMsg: TopicMessage) {
        val idx = items.indexOfFirst { it.id == tempId }
        if (idx >= 0) {
            items[idx] = realMsg
            notifyItemChanged(idx)
        }
    }

    fun enterSelection(item: TopicMessage) {
        selectionMode = true
        if (item.id > 0) selected.add(item.id)
        notifyDataSetChanged()
    }

    fun toggle(item: TopicMessage) {
        if (item.id > 0) {
            if (selected.contains(item.id)) selected.remove(item.id) else selected.add(item.id)
        }
        if (selected.isEmpty()) selectionMode = false
        val pos = items.indexOfFirst { it.id == item.id }
        if (pos >= 0) notifyItemChanged(pos) else notifyDataSetChanged()
    }

    fun selectAll() {
        selected.clear()
        items.filter { it.id > 0 }.forEach { selected.add(it.id) }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
    }

    /** 本地移除一条消息（软删除后在其他设备上同步隐藏，不影响数据源之外的逻辑） */
    fun removeMessage(id: Long) {
        val pos = items.indexOfFirst { it.id == id }
        if (pos < 0) return
        items.removeAt(pos)
        selected.remove(id)
        notifyItemRemoved(pos)
    }

    fun getSelectedIds(): List<Long> = selected.filter { it > 0 }
    val selectedCount: Int get() = selected.size

    /** 已读回执：把指定 id 的消息标记为「已读」（对方已读），刷新对应气泡 */
    fun markRead(ids: Set<Long>) {
        if (ids.isEmpty()) return
        ids.forEach { id ->
            val pos = items.indexOfFirst { it.id == id }
            if (pos >= 0 && !items[pos].read) {
                items[pos] = items[pos].copy(read = true)
                notifyItemChanged(pos)
            }
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val llMessageRow: android.widget.LinearLayout = view.findViewById(R.id.llMessageRow)
        val avatarContainer: View = view.findViewById(R.id.avatarContainer)
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        val llContent: android.widget.LinearLayout = view.findViewById(R.id.llContent)
        val bubbleInner: View = view.findViewById(R.id.bubbleInner)
        val llSenderInfo: android.widget.LinearLayout = view.findViewById(R.id.llSenderInfo)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvText: TextView = view.findViewById(R.id.tvText)
        val ivMedia: ImageView = view.findViewById(R.id.ivMedia)
        val mediaContainer: android.widget.FrameLayout = view.findViewById(R.id.mediaContainer)
        val ivPlayOverlay: ImageView = view.findViewById(R.id.ivPlayOverlay)
        val llVoice: View = view.findViewById(R.id.llVoice)
        val tvVoiceDuration: TextView = view.findViewById(R.id.tvVoiceDuration)
        val ivVoiceIcon: ImageView = view.findViewById(R.id.ivVoiceIcon)
        val llFile: View = view.findViewById(R.id.llFile)
        val tvFile: TextView = view.findViewById(R.id.tvFile)
        val statusContainer: View = view.findViewById(R.id.statusContainer)
        val ivStatus: ImageView = view.findViewById(R.id.ivStatus)
        val pbSending: View = view.findViewById(R.id.pbSending)
        // MoviePilot 富文本卡片
        val cardContainer: android.widget.LinearLayout = view.findViewById(R.id.cardContainer)
        val ivCardPoster: ImageView = view.findViewById(R.id.ivCardPoster)
        val tvCardTitle: TextView = view.findViewById(R.id.tvCardTitle)
        val llCardDetails: android.widget.LinearLayout = view.findViewById(R.id.llCardDetails)
        val tvCardText: TextView = view.findViewById(R.id.tvCardText)
        val llCardButtons: android.widget.LinearLayout = view.findViewById(R.id.llCardButtons)
        var item: TopicMessage? = null
        private var selectionTapHandled = false
        var lastMine: Boolean? = null

        init {
            val ctx = view.context
            val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    selectionTapHandled = false
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val it = item ?: return false
                    if (selectionMode) {
                        selectionTapHandled = true
                        onItemClick(it)
                        return true
                    }
                    return false
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val it = item ?: return false
                    if (selectionTapHandled) {
                        selectionTapHandled = false
                        return true
                    }
                    if (!selectionMode) {
                        // Check avatar click first
                        if (avatarContainer.visibility == View.VISIBLE && inViewBounds(avatarContainer, e)) {
                            onAvatarClick?.invoke(it)
                            return true
                        }
                        openMediaIfHit(it, e)
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val it = item ?: return false
                    if (!selectionMode) copyText(view.context, it)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    val it = item ?: return
                    if (selectionMode) onItemClick(it) else onItemLongClick(it)
                }
            }
            val detector = GestureDetector(ctx, gestureListener)
            detector.setOnDoubleTapListener(gestureListener)
            view.setOnTouchListener { _, ev ->
                detector.onTouchEvent(ev)
                true
            }
        }

        private fun openMediaIfHit(item: TopicMessage, ev: MotionEvent) {
            val ctx = itemView.context
            when {
                mediaContainer.visibility == View.VISIBLE && inViewBounds(mediaContainer, ev) -> {
                    if (item.mediaType == "file" && isVideoFile(item.mediaName)) {
                        // 视频：APP 内部全屏播放
                        onVideoClick?.invoke(item)
                    } else {
                        onImageClick?.invoke(item)
                    }
                }
                llVoice.visibility == View.VISIBLE && inViewBounds(llVoice, ev) -> {
                    if (!item.mediaUrl.isNullOrEmpty()) {
                        val local = P2pManager.localP2pFile(ctx, item.mediaUrl)
                            ?: MediaCacheManager.getCachedFile(fullUrl(item.mediaUrl))
                        if (local != null) playVoice(local.absolutePath, ivVoiceIcon)
                        else {
                            MediaCacheManager.preload(fullUrl(item.mediaUrl))
                            playVoice(fullUrl(item.mediaUrl), ivVoiceIcon)
                        }
                    }
                }
                llFile.visibility == View.VISIBLE && inViewBounds(llFile, ev) -> {
                    if (!item.mediaUrl.isNullOrEmpty()) openFile(ctx, item)
                }
            }
        }

        private fun openFile(ctx: Context, item: TopicMessage) {
            val local = P2pManager.localP2pFile(ctx, item.mediaUrl!!)
            if (local != null) {
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", local
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, guessMime(item.mediaName))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(ctx, "没有应用能打开该文件", Toast.LENGTH_SHORT).show()
                }
            } else {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl(item.mediaUrl)))
                try { ctx.startActivity(intent) } catch (_: Exception) {}
            }
        }

        private fun guessMime(name: String?): String = when {
            name == null -> "*/*"
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
            name.endsWith(".png", true) -> "image/png"
            name.endsWith(".gif", true) -> "image/gif"
            name.endsWith(".webp", true) -> "image/webp"
            name.endsWith(".mp4", true) -> "video/mp4"
            name.endsWith(".mp3", true) -> "audio/mpeg"
            name.endsWith(".m4a", true) || name.endsWith(".aac", true) -> "audio/mp4"
            name.endsWith(".pdf", true) -> "application/pdf"
            name.endsWith(".txt", true) -> "text/plain"
            else -> "*/*"
        }

        /**
         * 判断触摸点是否落在目标 View 内。
         * 用全局屏幕坐标比较：getHitRect() 返回的是相对父容器的坐标，
         * 而 MotionEvent.x/y 相对监听视图（itemView 根布局）——两者直接比较会因
         * 头像占位/左右分栏导致命中区域整体偏移，图片右侧点不中。因此改用
         * getLocationOnScreen + rawX/rawY 全局坐标。
         */
        private fun inViewBounds(v: View, ev: MotionEvent): Boolean {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val rx = ev.rawX.toInt()
            val ry = ev.rawY.toInt()
            return rx >= loc[0] && rx < loc[0] + v.width &&
                   ry >= loc[1] && ry < loc[1] + v.height
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_topic_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.item = item

        // 本机（同账号）发出的消息靠右（微信式），覆盖所有会话：我的设备/群聊/私聊
        val isMine = isSelfMessage(item)
        applyOwnStyle(holder, isMine)

        // ===== Telegram-style consecutive message grouping =====
        // Show avatar + sender name only for the first message in a group.
        // Consecutive messages from the same sender hide avatar (INVISIBLE to keep spacing)
        // and sender info row (GONE).
        val prevItem = if (position > 0) items[position - 1] else null
        val isSameSenderAsPrev = prevItem != null && isSameSender(prevItem!!, item)

        if (isSameSenderAsPrev) {
            holder.avatarContainer.visibility = View.INVISIBLE
            holder.llSenderInfo.visibility = View.GONE
        } else {
            holder.avatarContainer.visibility = View.VISIBLE
            holder.llSenderInfo.visibility = View.VISIBLE
        }
        // 一对一对话：Telegram 风格，去掉头像
        // 留言板和我的设备特殊：只隐藏头像，保留发送人名字（名字+号码/设备名）
        // 其他一对一对话（MP/私聊/通知）：头像和发送人名字都隐藏
        if (hideAvatar) {
            holder.avatarContainer.visibility = View.GONE
            if (!isMessageWall && !isMyDevice) {
                holder.llSenderInfo.visibility = View.GONE
            }
        }

        // Sender display name: prefer display_name, fallback to sender_name (username)
        val displayName = item.senderDisplayName?.takeIf { it.isNotBlank() }
            ?: item.senderName.ifEmpty { "unknown" }
        holder.tvSender.text = if (!item.deviceName.isNullOrBlank()) {
            "$displayName (${item.deviceName})"
        } else {
            displayName
        }
        holder.tvTime.text = timeFormat.format(Date(item.timestamp))
        // 15分钟时间轴：与上一条消息间隔超过15分钟才显示时间
        val showTime = position == 0 || (item.timestamp - items[position - 1].timestamp) > 15 * 60 * 1000
        holder.tvTime.visibility = if (showTime) View.VISIBLE else View.GONE
        // 留言板消息上下间距增大，避免连续消息挤在一起
        val rootLp = holder.itemView.layoutParams as androidx.recyclerview.widget.RecyclerView.LayoutParams
        rootLp.topMargin = if (isMessageWall) (6 * holder.itemView.context.resources.displayMetrics.density).toInt() else 0
        rootLp.bottomMargin = if (isMessageWall) (4 * holder.itemView.context.resources.displayMetrics.density).toInt() else 0
        holder.itemView.layoutParams = rootLp

        // Title and text
        // 留言板：title 是访客 ID+联系方式，放到发送人位置单独显示，气泡里只放内容
        // MP：title 和 text 内容重复（MP 插件把截断文本当 title），只显示 text
        if (isMessageWall && item.title.isNotEmpty()) {
            holder.tvSender.text = item.title
            holder.tvTitle.visibility = View.GONE
            holder.llSenderInfo.visibility = View.VISIBLE
        } else if (isMoviePilot) {
            holder.tvTitle.visibility = View.GONE
        } else {
            holder.tvTitle.text = item.title
            holder.tvTitle.visibility = if (item.title.isNotEmpty()) View.VISIBLE else View.GONE
        }
        holder.tvText.text = item.text
        holder.tvText.visibility = if (item.text.isNotEmpty()) View.VISIBLE else View.GONE

        // Media rendering
        val isMedia = item.mediaType != "text" && (!item.mediaUrl.isNullOrEmpty() || item.mediaType == "card")
        holder.mediaContainer.visibility = View.GONE
        holder.ivPlayOverlay.visibility = View.GONE
        holder.llVoice.visibility = View.GONE
        holder.llFile.visibility = View.GONE
        // 关键修复：普通文本/图片/语音/文件消息必须隐藏 cardContainer，否则复用旧卡片ViewHolder时旧卡片内容会盖在上面
        holder.cardContainer.visibility = View.GONE
        // 同时恢复 bubbleInner 可见（bindCard 里会把它设为 GONE，复用回来时要恢复）
        holder.bubbleInner.visibility = View.VISIBLE
        // 统一恢复气泡背景（语音/文字用气泡，图片/视频去掉气泡）
        val dpRestore = holder.itemView.context.resources.displayMetrics.density
        holder.bubbleInner.setBackgroundResource(if (isMine) R.drawable.bg_msg_own else R.drawable.bg_msg_other)
        holder.bubbleInner.setPadding((8*dpRestore).toInt(), (4*dpRestore).toInt(), (8*dpRestore).toInt(), (4*dpRestore).toInt())
        holder.bubbleInner.elevation = 1.5f * dpRestore
        if (isMedia) {
            when (item.mediaType) {
                "image" -> {
                    holder.mediaContainer.visibility = View.VISIBLE
                    holder.ivPlayOverlay.visibility = View.GONE
                    val local = P2pManager.localP2pFile(holder.itemView.context, item.mediaUrl)
                        ?: MediaCacheManager.getCachedFile(fullUrl(item.mediaUrl!!))
                    if (local != null) loadLocalImage(local, holder.ivMedia)
                    else {
                        MediaCacheManager.preload(fullUrl(item.mediaUrl!!))
                        loadImage(fullUrl(item.mediaUrl!!), holder.ivMedia)
                    }
                    // 图片去掉气泡包裹
                    holder.bubbleInner.setBackgroundResource(0)
                    holder.bubbleInner.setPadding(0, 0, 0, 0)
                    holder.bubbleInner.elevation = 0f
                }
                "voice" -> {
                    holder.llVoice.visibility = View.VISIBLE
                    val dur = item.duration.coerceAtLeast(1)
                    holder.tvVoiceDuration.text = "${dur}\""
                    holder.ivVoiceIcon.setImageResource(R.drawable.ic_voice_3)
                    val isMine = isSelfMessage(item)
                    holder.llVoice.layoutDirection = if (isMine) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    holder.ivVoiceIcon.scaleX = if (isMine) 1f else -1f
                    val dp = holder.itemView.context.resources.displayMetrics.density
                    val steps = ((dur - 1) / 2).coerceIn(0, 10)
                    val widthDp = 72 + steps * 16
                    val lp = holder.llVoice.layoutParams as android.widget.LinearLayout.LayoutParams
                    lp.width = (widthDp * dp).toInt()
                    holder.llVoice.layoutParams = lp
                    holder.bubbleInner.setBackgroundResource(if (isMine) R.drawable.bg_msg_own else R.drawable.bg_msg_other)
                    holder.bubbleInner.elevation = 1.5f * dp
                    val ctx = holder.itemView.context
                    holder.tvVoiceDuration.setTextColor(ctx.getColor(R.color.on_surface))
                    holder.ivVoiceIcon.setColorFilter(ctx.getColor(R.color.on_surface))
                }
                "file" -> {
                    if (isVideoFile(item.mediaName)) {
                        // 视频：显示缩略图 + 播放按钮，去掉气泡
                        holder.mediaContainer.visibility = View.VISIBLE
                        holder.ivPlayOverlay.visibility = View.VISIBLE
                        loadVideoThumbnail(item, holder.ivMedia)
                        holder.bubbleInner.setBackgroundResource(0)
                        holder.bubbleInner.setPadding(0, 0, 0, 0)
                        holder.bubbleInner.elevation = 0f
                    } else {
                        holder.llFile.visibility = View.VISIBLE
                        val suffix = if (item.mediaUrl?.startsWith("p2p:") == true) " · P2P直传" else ""
                        holder.tvFile.text = "\uD83D\uDCC4 ${item.mediaName ?: "文件"}  (${formatSize(item.mediaSize)})$suffix"
                    }
                }
                "card" -> {
                    bindCard(holder, item)
                }
            }
        }

        // Avatar loading (only for first message in group to save bandwidth)
        if (!isSameSenderAsPrev) {
            loadAvatar(item, holder)
        }

        // 已读回执（WhatsApp 风）：仅 dm 私聊里「自己发出的」消息显示
        // 单勾（灰）= 已送达；重叠双勾（蓝）= 对方已读
        // 发送中：显示小圈圈，不显示钩子
        if (isMine && showReadReceipts) {
            if (item.sending) {
                holder.ivStatus.visibility = View.GONE
                holder.pbSending.visibility = View.VISIBLE
            } else {
                holder.pbSending.visibility = View.GONE
                holder.ivStatus.visibility = View.VISIBLE
                holder.ivStatus.setImageResource(
                    if (item.read) R.drawable.ic_double_check else R.drawable.ic_check_single
                )
            }
        } else {
            holder.ivStatus.visibility = View.GONE
            holder.pbSending.visibility = View.GONE
        }

        // Selection visual：圆角矩形高亮，匹配新拟态卡片
        if (selectionMode && selected.contains(item.id)) {
            holder.itemView.setBackgroundResource(R.drawable.bg_selected_item)
        } else {
            holder.itemView.setBackgroundResource(0)
        }
    }

    /**
     * 微信式左右分栏 + Telegram 双侧气泡材质：
     * 自己的消息头像在右、Telegram 绿气泡深色字；他人消息头像在左、白色悬浮气泡。
     * 气泡宽度自适应内容（上限屏宽 72%），只在归属变化时重排，避免复用抖动。
     */
    private fun applyOwnStyle(holder: ViewHolder, isMine: Boolean) {
        // 去掉 lastMine 缓存：RecyclerView 复用时缓存会导致样式错乱，每次都重新设置
        val row = holder.llMessageRow
        // 重排子视图：自己的消息 [占位, 已读回执, 气泡, 头像]，他人的消息 [头像, 气泡, 已读回执, 占位]
        // 用占位view占满剩余空间，让已读标志紧贴气泡
        row.removeAllViews()
        val spacer = android.view.View(holder.itemView.context)
        val spacerLp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        spacer.layoutParams = spacerLp
        if (isMine) {
            // 自己的消息：[占位, 已读标志, 气泡, 头像] —— 已读标志紧挨气泡左边
            row.addView(spacer)
            row.addView(holder.statusContainer)
            row.addView(holder.llContent)
            row.addView(holder.avatarContainer)
        } else {
            // 对面的消息：[头像, 气泡, 已读标志, 占位] —— 已读标志紧挨气泡右边
            row.addView(holder.avatarContainer)
            row.addView(holder.llContent)
            row.addView(holder.statusContainer)
            row.addView(spacer)
        }
        val g = if (isMine) Gravity.END else Gravity.START
        row.gravity = g or Gravity.CENTER_VERTICAL
        holder.llSenderInfo.gravity = g
        holder.tvTitle.gravity = android.view.Gravity.START
        holder.tvText.gravity = android.view.Gravity.START
        (holder.mediaContainer.layoutParams as android.widget.LinearLayout.LayoutParams).gravity = g
        (holder.llVoice.layoutParams as android.widget.LinearLayout.LayoutParams).gravity = g
        (holder.llFile.layoutParams as android.widget.LinearLayout.LayoutParams).gravity = g

        val ctx = holder.itemView.context
        val dp = ctx.resources.displayMetrics.density

        // 气泡宽度自适应内容，上限屏宽 60%，长文本自动换行
        val maxW = (ctx.resources.displayMetrics.widthPixels * 0.60f).toInt()
        holder.tvTitle.maxWidth = maxW
        holder.tvText.maxWidth = maxW
        // bubbleInner 显式设置 layout_gravity，确保紧贴左/右边缘
        val lp = holder.bubbleInner.layoutParams as android.widget.LinearLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.weight = 0f
        lp.gravity = g
        holder.bubbleInner.layoutParams = lp

        // llContent 用 wrap_content，让已读标志紧贴气泡（spacer 负责占满剩余空间）
        val lpContent = holder.llContent.layoutParams as android.widget.LinearLayout.LayoutParams
        lpContent.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lpContent.weight = 0f
        lpContent.topMargin = 0
        lpContent.bottomMargin = 0
        lpContent.marginStart = 0
        lpContent.marginEnd = 0
        holder.llContent.layoutParams = lpContent
        holder.llContent.gravity = g

        val padH = (8 * dp).toInt()
        val padV = (4 * dp).toInt()
        holder.bubbleInner.setPadding(padH, padV, padH, padV)
        holder.bubbleInner.elevation = 1.5f * dp  // 悬浮感（shape 背景自动生成圆角阴影轮廓）

        if (isMine) {
            // Telegram 风：绿色气泡 + 深色正文 + 淡绿时间
            holder.bubbleInner.setBackgroundResource(R.drawable.bg_msg_own)
            holder.tvTitle.setTextColor(ctx.getColor(R.color.on_surface))
            holder.tvText.setTextColor(ctx.getColor(R.color.on_surface))
            holder.tvSender.setTextColor(ctx.getColor(R.color.on_surface_variant))
            holder.tvTime.setTextColor(ctx.getColor(R.color.bubble_own_time))
        } else {
            // 对方气泡：同色浅绿 + 左下尾角（仅翻转气泡方向，内容不变）
            holder.bubbleInner.setBackgroundResource(R.drawable.bg_msg_other)
            holder.tvTitle.setTextColor(ctx.getColor(R.color.on_surface))
            holder.tvText.setTextColor(ctx.getColor(R.color.on_surface))
            holder.tvSender.setTextColor(ctx.getColor(R.color.on_surface_variant))
            holder.tvTime.setTextColor(ctx.getColor(R.color.bubble_own_time))
        }
    }

    /**
     * Two messages are from the same sender if they share the same user_id (> 0),
     * or if user_id is 0 (legacy/WS) fall back to senderName comparison.
     */
    private fun isSameSender(a: TopicMessage, b: TopicMessage): Boolean {
        if (a.senderUserId > 0 && b.senderUserId > 0) {
            return a.senderUserId == b.senderUserId
        }
        return a.senderName == b.senderName
    }

    val isAllSelected: Boolean
        get() = items.filter { it.id > 0 }.let { it.isNotEmpty() && it.all { selected.contains(it.id) } }

    fun copyMessage(context: Context, item: TopicMessage) = copyText(context, item)

    private fun copyText(context: Context, item: TopicMessage) {
        val text = listOf(item.title, item.text)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (text.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("话题消息", text))
        vibrate(context)
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun vibrate(context: Context) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(30)
            }
        } catch (_: Exception) {}
    }

    override fun getItemCount(): Int = items.size

    /** 当前会话全部消息（用于全屏查看器收集图片列表） */
    fun allItems(): List<TopicMessage> = items

    private fun fullUrl(path: String): String {
        if (path.startsWith("p2p:")) return path
        val base = AuthManager.serverUrl.trimEnd('/')
        return if (path.startsWith("http")) path else "$base$path"
    }

    private fun loadLocalImage(file: File, iv: ImageView) {
        iv.setImageBitmap(null)
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
                iv.setImageBitmap(bmp)
            } catch (_: Exception) {}
        }
    }

    private fun loadImage(url: String, iv: ImageView) {
        iv.setImageBitmap(null)
        // 以文件名（不含 host）做缓存 key：局域网与公网同一物理文件只缓存一份，换网直接命中本地
        val cache = File(iv.context.cacheDir, "img_" + url.substringAfterLast('/').substringBefore('?'))
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    if (cache.exists() && cache.length() > 0) {
                        BitmapFactory.decodeFile(cache.absolutePath)
                    } else {
                        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 10000
                            readTimeout = 30000
                            doInput = true
                            instanceFollowRedirects = true
                        }
                        conn.inputStream.use { input ->
                            cache.outputStream().use { out -> input.copyTo(out) }
                            BitmapFactory.decodeFile(cache.absolutePath)
                        }
                    }
                }
                iv.setImageBitmap(bmp)
            } catch (e: Exception) {
                // 半截损坏的缓存文件清掉，下次重新下载
                try { cache.delete() } catch (_: Exception) {}
            }
        }
    }

    /** 判断文件名是否为视频 */
    private fun isVideoFile(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi")
            || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".flv")
            || lower.endsWith(".wmv") || lower.endsWith(".m4v") || lower.endsWith(".3gp")
    }

    /** 加载视频缩略图：本地文件用 MediaMetadataRetriever 取第一帧，远程 URL 也尝试取帧 */
    private fun loadVideoThumbnail(item: TopicMessage, iv: ImageView) {
        iv.setImageBitmap(null)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    val local = P2pManager.localP2pFile(iv.context, item.mediaUrl!!)
                        ?: MediaCacheManager.getCachedFile(fullUrl(item.mediaUrl!!))
                    // 没有本地缓存时先预加载
                    if (local == null) MediaCacheManager.preload(fullUrl(item.mediaUrl!!))
                    if (local != null) {
                        retriever.setDataSource(local.absolutePath)
                    } else {
                        retriever.setDataSource(fullUrl(item.mediaUrl!!), HashMap<String, String>())
                    }
                    val frame = retriever.frameAtTime
                    retriever.release()
                    frame
                } catch (_: Exception) { null }
            }
            if (bmp != null) iv.setImageBitmap(bmp)
            else iv.setBackgroundColor(0xFF2a2d35.toInt())
        }
    }

    // ===== Avatar loading =====

    /**
     * 头像加载策略（根治「首条/对方消息没头像」）：
     *  - 自己发的消息 → 永远用 AuthManager.avatarUrl（当前实时头像），换头像后立即生效；
     *  - 他人消息：
     *      · DM 私聊 → 优先消息自带 sender_avatar，为空则回退 item.peerAvatar（服务器给的对方实时头像，
     *        每条消息自带、不依赖外部传入），再不行回退 peerAvatarUrl（showChatMode 传入），保证首条一定有头像；
     *      · 群聊/设备会话 → 优先消息自带 sender_avatar，为空则显示默认头像（群友没有统一头像）。
     *  关键修复：peer_avatar 由服务器 /messages 在 dm 下直接返回并附在每条消息上，
     *  彻底摆脱「peerAvatarUrl 外部未传/传空导致老消息无兜底」的隐患。
     */
    private fun loadAvatar(item: TopicMessage, holder: ViewHolder) {
        // 特殊会话对方消息：统一显示对应新拟态图标
        if (!isSelfMessage(item)) {
            val iconRes = when {
                isMoviePilot -> R.drawable.ic_moviepilot_neo
                isMessageWall -> R.drawable.ic_messagewall_neo
                isNotification -> R.drawable.ic_notification_neo
                isMyDevice -> R.drawable.ic_my_device_neo
                else -> null
            }
            if (iconRes != null) {
                holder.tvAvatar.visibility = View.GONE
                holder.ivAvatar.visibility = View.VISIBLE
                holder.ivAvatar.setImageResource(iconRes)
                holder.ivAvatar.setBackgroundResource(R.drawable.bg_circle_avatar)
                holder.ivAvatar.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                holder.ivAvatar.setPadding(0, 0, 0, 0)
                return
            }
        }
        val url = if (isSelfMessage(item)) {
            ApiClient.fullAvatarUrl(AuthManager.avatarUrl)
        } else if (isDm) {
            // DM 对方头像兜底链：消息自带 → 服务器给的 peer_avatar → 外部传入 peerAvatarUrl
            ApiClient.fullAvatarUrl(item.senderAvatar)
                ?: ApiClient.fullAvatarUrl(item.peerAvatar)
                ?: peerAvatarUrl
        } else {
            ApiClient.fullAvatarUrl(item.senderAvatar)
        }
        if (url.isNullOrBlank()) {
            // 无头像：显示首字母
            holder.ivAvatar.visibility = View.GONE
            holder.tvAvatar.visibility = View.VISIBLE
            holder.tvAvatar.text = initials(item.senderDisplayName ?: item.senderName)
            holder.tvAvatar.setBackgroundResource(R.drawable.bg_circle_avatar)
            holder.tvAvatar.setTextColor(android.graphics.Color.WHITE)
            return
        }
        holder.tvAvatar.visibility = View.GONE
        holder.ivAvatar.visibility = View.VISIBLE
        AvatarLoader.load(url, holder.ivAvatar)
    }

    private fun initials(name: String): String {
        val s = name.trim()
        if (s.isEmpty()) return "?"
        // 中文取最后一个字，英文取首字母
        return if (s[0].code in 0x4E00..0x9FFF) s.last().toString()
        else s.take(1).uppercase()
    }

    private fun playVoice(url: String, icon: ImageView) {
        // 停止之前的播放
        currentMediaPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        currentPlayingIcon?.setImageResource(R.drawable.ic_voice_3)
        currentPlayingIcon = null

        try {
            val mp = MediaPlayer()
            currentMediaPlayer = mp
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                it.start()
                // 启动播放动画
                icon.setImageResource(R.drawable.anim_voice_play)
                (icon.drawable as? android.graphics.drawable.AnimationDrawable)?.start()
                currentPlayingIcon = icon
            }
            mp.setOnCompletionListener {
                it.release()
                icon.setImageResource(R.drawable.ic_voice_3)
                if (currentPlayingIcon === icon) currentPlayingIcon = null
                currentMediaPlayer = null
            }
            mp.setOnErrorListener { m, _, _ ->
                m.release()
                icon.setImageResource(R.drawable.ic_voice_3)
                if (currentPlayingIcon === icon) currentPlayingIcon = null
                currentMediaPlayer = null
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            icon.setImageResource(R.drawable.ic_voice_3)
        }
    }

    /**
     * 渲染 MoviePilot 富文本卡片消息
     */
    private fun bindCard(holder: ViewHolder, item: TopicMessage) {
        val ctx = holder.itemView.context
        holder.bubbleInner.visibility = View.GONE
        holder.tvText.visibility = View.GONE
        holder.tvTitle.visibility = View.GONE
        holder.mediaContainer.visibility = View.GONE
        holder.llVoice.visibility = View.GONE
        holder.llFile.visibility = View.GONE
        holder.cardContainer.visibility = View.VISIBLE
        // 卡片消息也用气泡背景（带尾角箭头），自己的右下/对面的左下
        val isMine = isSelfMessage(item)
        holder.cardContainer.setBackgroundResource(if (isMine) R.drawable.bg_msg_own else R.drawable.bg_msg_other)
        val dp = ctx.resources.displayMetrics.density
        holder.cardContainer.setPadding((10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (8*dp).toInt())

        holder.llCardDetails.removeAllViews()
        holder.llCardButtons.removeAllViews()

        val cardJson = item.cardData ?: run {
            holder.tvCardTitle.text = item.title
            holder.ivCardPoster.visibility = View.GONE
            holder.tvCardText.visibility = View.GONE
            return
        }

        try {
            val card = org.json.JSONObject(cardJson)
            val title = card.optString("title", item.title)
            holder.tvCardTitle.text = title
            holder.tvCardTitle.visibility = if (title.isNotEmpty()) View.VISIBLE else View.GONE

            val poster = card.optString("poster", "")
            if (poster.isNotEmpty()) {
                holder.ivCardPoster.visibility = View.VISIBLE
                loadImage(poster, holder.ivCardPoster)
            } else {
                holder.ivCardPoster.visibility = View.GONE
            }

            val details = card.optJSONArray("details")
            if (details != null && details.length() > 0) {
                holder.llCardDetails.visibility = View.VISIBLE
                val dp = ctx.resources.displayMetrics.density
                for (i in 0 until details.length()) {
                    val d = details.getJSONObject(i)
                    val key = d.optString("key", "")
                    val value = d.optString("value", "")
                    if (key.isEmpty() && value.isEmpty()) continue
                    val row = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
                    }
                    val keyTv = android.widget.TextView(ctx).apply {
                        text = "$key: "
                        textSize = 12f
                        setTextColor(ctx.getColor(R.color.on_surface_variant))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    val valTv = android.widget.TextView(ctx).apply {
                        text = value
                        textSize = 12f
                        setTextColor(ctx.getColor(R.color.on_surface))
                    }
                    row.addView(keyTv)
                    row.addView(valTv)
                    holder.llCardDetails.addView(row)
                }
            } else {
                holder.llCardDetails.visibility = View.GONE
            }

            val extraText = card.optString("text", "")
            if (extraText.isNotEmpty()) {
                holder.tvCardText.text = extraText
                holder.tvCardText.visibility = View.VISIBLE
            } else {
                holder.tvCardText.visibility = View.GONE
            }

            val buttons = card.optJSONArray("buttons")
            if (buttons != null && buttons.length() > 0) {
                holder.llCardButtons.visibility = View.VISIBLE
                holder.llCardButtons.removeAllViews()
                val dp = ctx.resources.displayMetrics.density
                // DEBUG: 打印 buttons 原始结构
                com.echolink.util.DebugLogger.d("MPButtons", "raw buttons: ${buttons.toString().take(200)}")

                // Telegram 风格：整体圆角边框容器
                val containerBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12 * dp
                    setColor(ctx.getColor(R.color.bubble_other))
                    setStroke((1 * dp).toInt(), ctx.getColor(R.color.outline))
                }
                holder.llCardButtons.background = containerBg
                holder.llCardButtons.setPadding(0, 0, 0, 0)
                holder.llCardButtons.orientation = android.widget.LinearLayout.VERTICAL

                // 收集所有按钮（一维/二维数组都展平成一维，Telegram 风格一排一个）
                val allButtons = mutableListOf<org.json.JSONObject>()
                val is2D = buttons.length() > 0 && buttons.opt(0) is org.json.JSONArray
                com.echolink.util.DebugLogger.d("MPButtons", "is2D=$is2D, length=${buttons.length()}")
                if (is2D) {
                    for (rowIdx in 0 until buttons.length()) {
                        val rowArr = buttons.getJSONArray(rowIdx)
                        for (btnIdx in 0 until rowArr.length()) {
                            allButtons.add(rowArr.getJSONObject(btnIdx))
                        }
                    }
                } else {
                    for (i in 0 until buttons.length()) {
                        allButtons.add(buttons.getJSONObject(i))
                    }
                }

                // 一排一个按钮，按钮之间加细分隔线
                for (i in 0 until allButtons.size) {
                    val b = allButtons[i]
                    val btnText = b.optString("text", "按钮")
                    val callbackData = b.optString("callback_data", "")
                    com.echolink.util.DebugLogger.d("MPButtons", "  btn[$i] text='$btnText' callback='$callbackData'")

                    val btn = android.widget.Button(ctx).apply {
                        text = btnText
                        textSize = 14f
                        setTextColor(ctx.getColor(R.color.brand_primary))
                        setBackgroundResource(android.R.color.transparent)
                        setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                        setOnClickListener {
                            // 点击反馈：按钮变灰
                            isEnabled = false
                            alpha = 0.4f
                            com.echolink.util.DebugLogger.d("MPButtons", "clicked text='$btnText' callback='$callbackData'")
                            // 回调给 Fragment：走正常发消息流程 + 转发 callback 给 MP
                            onMpButtonClick?.invoke(btnText, callbackData)
                        }
                    }
                    val btnLp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    holder.llCardButtons.addView(btn, btnLp)

                    // 按钮之间加细分隔线（最后一个不加）
                    if (i < allButtons.size - 1) {
                        val divider = View(ctx).apply {
                            setBackgroundColor(ctx.getColor(R.color.outline))
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                (1 * dp).toInt()
                            )
                        }
                        holder.llCardButtons.addView(divider)
                    }
                }
            } else {
                holder.llCardButtons.visibility = View.GONE
            }
        } catch (e: Exception) {
            holder.tvCardTitle.text = "卡片解析失败"
            holder.ivCardPoster.visibility = View.GONE
            holder.llCardDetails.visibility = View.GONE
            holder.tvCardText.visibility = View.GONE
            holder.llCardButtons.visibility = View.GONE
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1fMB", bytes / 1024f / 1024f)
            bytes >= 1024 -> String.format("%.1fKB", bytes / 1024f)
            else -> "${bytes}B"
        }
    }
}
