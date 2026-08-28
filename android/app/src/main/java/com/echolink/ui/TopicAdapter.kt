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

class TopicAdapter(
    private val onItemLongClick: (TopicMessage) -> Unit,
    private val onItemClick: (TopicMessage) -> Unit,
    private val onImageClick: ((TopicMessage) -> Unit)? = null,
    private val onVideoClick: ((TopicMessage) -> Unit)? = null,
    private val onAvatarClick: ((TopicMessage) -> Unit)? = null
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

    /** 是否为留言板会话：访客消息统一显示 📮 头像（与 WebUI 一致），不加载 sender_avatar */
    var isMessageWall: Boolean = false

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

    /** 是否为「我」发出的消息：优先用 user_id 严格判定；为兼容历史消息（user_id 可能为 0），
     *  当 user_id 无效时回退用 senderName 与当前用户名比对。 */
    private fun isSelfMessage(item: TopicMessage): Boolean {
        if (item.senderUserId > 0) return item.senderUserId == AuthManager.userId
        // user_id 缺失的旧消息：用用户名兜底（仅当已登录且有用户名时可信）
        val me = AuthManager.username
        return !me.isNullOrBlank() && item.senderName == me
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
        val avatarContainer: View = view.findViewById(R.id.avatarContainer)
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        val llContent: View = view.findViewById(R.id.llContent)
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
                        if (local != null) playVoice(local.absolutePath, ivVoiceIcon)
                        else playVoice(fullUrl(item.mediaUrl), ivVoiceIcon)
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

        // Title and text
        // 留言板：title 是访客 ID+联系方式，放到发送人位置单独显示，气泡里只放内容
        if (isMessageWall && item.title.isNotEmpty()) {
            holder.tvSender.text = item.title
            holder.tvTitle.visibility = View.GONE
            holder.llSenderInfo.visibility = View.VISIBLE
        } else {
            holder.tvTitle.text = item.title
            holder.tvTitle.visibility = if (item.title.isNotEmpty()) View.VISIBLE else View.GONE
        }
        holder.tvText.text = item.text
        holder.tvText.visibility = if (item.text.isNotEmpty()) View.VISIBLE else View.GONE

        // Media rendering
        val isMedia = item.mediaType != "text" && !item.mediaUrl.isNullOrEmpty()
        holder.mediaContainer.visibility = View.GONE
        holder.ivPlayOverlay.visibility = View.GONE
        holder.llVoice.visibility = View.GONE
        holder.llFile.visibility = View.GONE
        // 统一恢复气泡背景（语音/文字用气泡，图片/视频去掉气泡）
        val dpRestore = holder.itemView.context.resources.displayMetrics.density
        holder.bubbleInner.setBackgroundResource(R.drawable.bg_msg_own)
        holder.bubbleInner.setPadding((8*dpRestore).toInt(), (4*dpRestore).toInt(), (8*dpRestore).toInt(), (4*dpRestore).toInt())
        holder.bubbleInner.elevation = 1.5f * dpRestore
        if (isMedia) {
            when (item.mediaType) {
                "image" -> {
                    holder.mediaContainer.visibility = View.VISIBLE
                    holder.ivPlayOverlay.visibility = View.GONE
                    val local = P2pManager.localP2pFile(holder.itemView.context, item.mediaUrl)
                    if (local != null) loadLocalImage(local, holder.ivMedia)
                    else loadImage(fullUrl(item.mediaUrl!!), holder.ivMedia)
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
                    holder.bubbleInner.setBackgroundResource(R.drawable.bg_msg_own)
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

        // Selection visual
        holder.itemView.setBackgroundColor(
            if (selectionMode && selected.contains(item.id))
                holder.itemView.context.getColor(R.color.brand_primary_light)
            else 0x00000000
        )
    }

    /**
     * 微信式左右分栏 + Telegram 双侧气泡材质：
     * 自己的消息头像在右、Telegram 绿气泡深色字；他人消息头像在左、白色悬浮气泡。
     * 气泡宽度自适应内容（上限屏宽 72%），只在归属变化时重排，避免复用抖动。
     */
    private fun applyOwnStyle(holder: ViewHolder, isMine: Boolean) {
        if (holder.lastMine == isMine) return
        holder.lastMine = isMine
        val root = holder.itemView as android.widget.LinearLayout
        // 重排子视图：自己的消息 [已读回执, 气泡, 头像]，他人的消息 [头像, 气泡, 已读回执]
        // 已读回执仅在 dm 私聊自己消息时显示，要放在消息气泡左侧，不要卡在头像和气泡之间。
        root.removeAllViews()
        if (isMine) {
            root.addView(holder.statusContainer)
            root.addView(holder.llContent)
            root.addView(holder.avatarContainer)
        } else {
            root.addView(holder.avatarContainer)
            root.addView(holder.llContent)
            root.addView(holder.statusContainer)
        }
        val g = if (isMine) Gravity.END else Gravity.START
        root.gravity = g or Gravity.CENTER_VERTICAL
        holder.llContent.gravity = g or Gravity.CENTER_VERTICAL
        holder.llSenderInfo.gravity = g
        holder.tvTitle.gravity = g
        holder.tvText.gravity = g
        (holder.mediaContainer.layoutParams as android.widget.LinearLayout.LayoutParams).gravity = g
        (holder.llVoice.layoutParams as android.widget.LinearLayout.LayoutParams).gravity = g
        (holder.llFile.layoutParams as android.widget.LinearLayout.LayoutParams).gravity = g

        val ctx = holder.itemView.context
        val dp = ctx.resources.displayMetrics.density

        // 气泡宽度自适应内容，上限屏宽 72%，长文本自动换行
        val maxW = (ctx.resources.displayMetrics.widthPixels * 0.72f).toInt()
        holder.tvTitle.maxWidth = maxW
        holder.tvText.maxWidth = maxW
        val lp = holder.bubbleInner.layoutParams as android.widget.LinearLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.weight = 0f
        holder.bubbleInner.layoutParams = lp

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
            // 对方气泡与自己一致（统一绿色气泡风格）
            holder.bubbleInner.setBackgroundResource(R.drawable.bg_msg_own)
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
        // 留言板访客消息：统一显示 📮 emoji + 灰色新拟态圆底（与 WebUI 一致）
        if (isMessageWall && !isSelfMessage(item)) {
            holder.ivAvatar.visibility = View.GONE
            holder.tvAvatar.visibility = View.VISIBLE
            holder.tvAvatar.text = "\uD83D\uDCEC"
            holder.tvAvatar.setBackgroundResource(R.drawable.bg_mw_avatar)
            holder.tvAvatar.setTextColor(holder.itemView.context.getColor(R.color.on_surface))
            holder.tvAvatar.textSize = 22f // 比默认 17sp 放大约 30%
            return
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

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1fMB", bytes / 1024f / 1024f)
            bytes >= 1024 -> String.format("%.1fKB", bytes / 1024f)
            else -> "${bytes}B"
        }
    }
}
