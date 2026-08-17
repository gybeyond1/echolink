package com.notifysync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
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
import com.notifysync.R
import com.notifysync.data.AuthManager
import com.notifysync.data.P2pManager
import com.notifysync.data.TopicMessage
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
    private val onImageClick: ((String) -> Unit)? = null,
    private val onAvatarClick: ((TopicMessage) -> Unit)? = null
) : RecyclerView.Adapter<TopicAdapter.ViewHolder>() {

    private val items = mutableListOf<TopicMessage>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var selectionMode = false
        private set
    private val selected = mutableSetOf<Long>()

    fun setItems(list: List<TopicMessage>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
    }

    fun appendItems(list: List<TopicMessage>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
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

    fun getSelectedIds(): List<Long> = selected.filter { it > 0 }
    val selectedCount: Int get() = selected.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        val llContent: View = view.findViewById(R.id.llContent)
        val llSenderInfo: android.widget.LinearLayout = view.findViewById(R.id.llSenderInfo)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvText: TextView = view.findViewById(R.id.tvText)
        val ivMedia: ImageView = view.findViewById(R.id.ivMedia)
        val llVoice: View = view.findViewById(R.id.llVoice)
        val llFile: View = view.findViewById(R.id.llFile)
        val tvFile: TextView = view.findViewById(R.id.tvFile)
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
                        if (ivAvatar.visibility == View.VISIBLE && inViewBounds(ivAvatar, e)) {
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
                ivMedia.visibility == View.VISIBLE && inViewBounds(ivMedia, ev) -> {
                    if (!item.mediaUrl.isNullOrEmpty()) onImageClick?.invoke(fullUrl(item.mediaUrl))
                }
                llVoice.visibility == View.VISIBLE && inViewBounds(llVoice, ev) -> {
                    if (!item.mediaUrl.isNullOrEmpty()) {
                        val local = P2pManager.localP2pFile(ctx, item.mediaUrl)
                        if (local != null) playVoice(local.absolutePath)
                        else playVoice(fullUrl(item.mediaUrl))
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

        private fun inViewBounds(v: View, ev: MotionEvent): Boolean {
            val rect = android.graphics.Rect()
            v.getHitRect(rect)
            return rect.contains(ev.x.toInt(), ev.y.toInt())
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
        val isMine = item.senderUserId > 0 && item.senderUserId == AuthManager.userId
        applyOwnStyle(holder, isMine)

        // ===== Telegram-style consecutive message grouping =====
        // Show avatar + sender name only for the first message in a group.
        // Consecutive messages from the same sender hide avatar (INVISIBLE to keep spacing)
        // and sender info row (GONE).
        val prevItem = if (position > 0) items[position - 1] else null
        val isSameSenderAsPrev = prevItem != null && isSameSender(prevItem!!, item)

        if (isSameSenderAsPrev) {
            holder.ivAvatar.visibility = View.INVISIBLE
            holder.llSenderInfo.visibility = View.GONE
        } else {
            holder.ivAvatar.visibility = View.VISIBLE
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

        // Title and text
        holder.tvTitle.text = item.title
        holder.tvTitle.visibility = if (item.title.isNotEmpty()) View.VISIBLE else View.GONE
        holder.tvText.text = item.text
        holder.tvText.visibility = if (item.text.isNotEmpty()) View.VISIBLE else View.GONE

        // Media rendering
        val isMedia = item.mediaType != "text" && !item.mediaUrl.isNullOrEmpty()
        holder.ivMedia.visibility = View.GONE
        holder.llVoice.visibility = View.GONE
        holder.llFile.visibility = View.GONE
        if (isMedia) {
            when (item.mediaType) {
                "image" -> {
                    holder.ivMedia.visibility = View.VISIBLE
                    val local = P2pManager.localP2pFile(holder.itemView.context, item.mediaUrl)
                    if (local != null) loadLocalImage(local, holder.ivMedia)
                    else loadImage(fullUrl(item.mediaUrl!!), holder.ivMedia)
                }
                "voice" -> {
                    holder.llVoice.visibility = View.VISIBLE
                }
                "file" -> {
                    holder.llFile.visibility = View.VISIBLE
                    val suffix = if (item.mediaUrl?.startsWith("p2p:") == true) " · P2P直传" else ""
                    holder.tvFile.text = "\uD83D\uDCC4 ${item.mediaName ?: "文件"}  (${formatSize(item.mediaSize)})$suffix"
                }
            }
        }

        // Avatar loading (only for first message in group to save bandwidth)
        if (!isSameSenderAsPrev) {
            loadAvatar(item, holder.ivAvatar)
        }

        // Selection visual
        holder.itemView.setBackgroundColor(
            if (selectionMode && selected.contains(item.id))
                holder.itemView.context.getColor(R.color.brand_primary_light)
            else 0x00000000
        )
    }

    /**
     * 微信式左右分栏 + 双侧气泡（高级感材质）：
     * 自己的消息头像在右、品牌渐变气泡白字；他人消息头像在左、白色悬浮气泡。
     * 气泡宽度自适应内容（上限屏宽 72%），只在归属变化时重排，避免复用抖动。
     */
    private fun applyOwnStyle(holder: ViewHolder, isMine: Boolean) {
        if (holder.lastMine == isMine) return
        holder.lastMine = isMine
        val root = holder.itemView as android.widget.LinearLayout
        val avatarIdx = root.indexOfChild(holder.ivAvatar)
        if (isMine && avatarIdx == 0) {
            root.removeView(holder.ivAvatar)
            root.addView(holder.ivAvatar)
        } else if (!isMine && avatarIdx == root.childCount - 1 && root.childCount > 1) {
            root.removeView(holder.ivAvatar)
            root.addView(holder.ivAvatar, 0)
        }
        val g = if (isMine) Gravity.END else Gravity.START
        root.gravity = g or Gravity.CENTER_VERTICAL
        holder.llSenderInfo.gravity = g
        holder.tvTitle.gravity = g
        holder.tvText.gravity = g
        (holder.ivMedia.layoutParams as android.widget.LinearLayout.LayoutParams).gravity = g
        (holder.llVoice.layoutParams as android.widget.LinearLayout.LayoutParams).gravity = g
        (holder.llFile.layoutParams as android.widget.LinearLayout.LayoutParams).gravity = g

        val ctx = holder.itemView.context
        val dp = ctx.resources.displayMetrics.density

        // 气泡宽度自适应内容，上限屏宽 72%，长文本自动换行
        val maxW = (ctx.resources.displayMetrics.widthPixels * 0.72f).toInt()
        holder.tvTitle.maxWidth = maxW
        holder.tvText.maxWidth = maxW
        val lp = holder.llContent.layoutParams as android.widget.LinearLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.weight = 0f
        holder.llContent.layoutParams = lp

        val padH = (12 * dp).toInt()
        val padV = (7 * dp).toInt()
        holder.llContent.setPadding(padH, padV, padH, padV)
        holder.llContent.elevation = 1.5f * dp  // 悬浮感（shape 背景自动生成圆角阴影轮廓）

        if (isMine) {
            holder.llContent.setBackgroundResource(R.drawable.bg_msg_own)
            val white = 0xFFFFFFFF.toInt()
            holder.tvTitle.setTextColor(white)
            holder.tvText.setTextColor(white)
            holder.tvSender.setTextColor(white)
            holder.tvTime.setTextColor(0xCCFFFFFF.toInt())
        } else {
            holder.llContent.setBackgroundResource(R.drawable.bg_msg_other)
            holder.tvTitle.setTextColor(ctx.getColor(R.color.on_surface))
            holder.tvText.setTextColor(ctx.getColor(R.color.on_surface))
            holder.tvSender.setTextColor(ctx.getColor(R.color.brand_primary))
            holder.tvTime.setTextColor(ctx.getColor(R.color.on_surface_variant))
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
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 15000
                        doInput = true
                    }
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }
                iv.setImageBitmap(bmp)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // ===== Avatar loading =====

    private fun loadAvatar(item: TopicMessage, iv: ImageView) {
        val avatarPath = item.senderAvatar
        val base = AuthManager.serverUrl.trimEnd('/')
        val fullAvatarUrl = if (avatarPath.isNullOrBlank()) {
            null
        } else if (avatarPath.startsWith("http")) {
            avatarPath
        } else if (avatarPath.startsWith("/")) {
            base + avatarPath
        } else {
            "$base/$avatarPath"
        }

        if (fullAvatarUrl.isNullOrBlank()) {
            iv.setImageResource(R.drawable.ic_default_avatar)
            return
        }

        iv.setImageResource(R.drawable.ic_default_avatar)
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { downloadBitmap(fullAvatarUrl) }
                if (bmp != null) {
                    iv.setImageBitmap(cropCircle(bmp))
                }
            } catch (_: Exception) {}
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.connect()
            BitmapFactory.decodeStream(conn.inputStream)
        } catch (e: Exception) { null }
    }

    private fun cropCircle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val x = (src.width - size) / 2
        val y = (src.height - size) / 2
        val squared = Bitmap.createBitmap(src, x, y, size, size)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(squared, rect, rect, paint)
        if (squared != src) squared.recycle()
        return output
    }

    private fun playVoice(url: String) {
        try {
            val mp = MediaPlayer()
            mp.setDataSource(url)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { m, _, _ -> m.release(); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            // ignore
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
