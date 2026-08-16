package com.notifysync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TopicAdapter(
    private val onItemLongClick: (TopicMessage) -> Unit,
    private val onItemClick: (TopicMessage) -> Unit,
    private val onImageClick: ((String) -> Unit)? = null
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
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvText: TextView = view.findViewById(R.id.tvText)
        val ivMedia: ImageView = view.findViewById(R.id.ivMedia)
        val llVoice: android.view.View = view.findViewById(R.id.llVoice)
        val llFile: android.view.View = view.findViewById(R.id.llFile)
        val tvFile: TextView = view.findViewById(R.id.tvFile)
        var item: TopicMessage? = null
        private var selectionTapHandled = false  // 本次点击已在多选模式处理（onSingleTapConfirmed 不再当普通单击）

        init {
            // 行业通用做法：ViewHolder 构造时一次性建立手势处理，onBindViewHolder 只更新数据，
            // 不再重建任何 listener。这样 RecyclerView 复用池 / notifyDataSetChanged 全量刷新
            // 都不会打断或重置手势状态，从根本上杜绝"点不动 / 要点好几下才选中"。
            // 关键配置：
            //  1. onDown 必须返回 true，事件序列才会持续交给 GestureDetector；
            //  2. 必须 setOnDoubleTapListener，否则 onDoubleTap / onSingleTapConfirmed 永不回调
            //     （上一轮 GestureDetector "失灵"很可能就是漏了这一步）；
            //  3. 普通模式单击走 onSingleTapConfirmed（等双击判定，避免双击时误开媒体）；
            //  4. 多选模式单击走 onSingleTapUp（立即 toggle，没有双击等待延迟）；
            //  5. 长按触发后 GestureDetector 不会再回调单击，天然避免"长按进多选后误 toggle"。
            val ctx = view.context
            val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    selectionTapHandled = false  // 每次按下重置，防止 ViewHolder 复用时残留标记
                    return true
                }

                // 单击：多选模式立即 toggle；普通模式忽略（等 onSingleTapConfirmed 判定非双击）
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val it = item ?: return false
                    if (selectionMode) {
                        selectionTapHandled = true
                        onItemClick(it)
                        return true
                    }
                    return false
                }

                // 单击确认（非双击）：普通模式按落点打开媒体（图片/语音/文件）。
                // 若本次点击已在多选模式处理过（如取消最后一项退出多选），不再当普通单击。
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val it = item ?: return false
                    if (selectionTapHandled) {
                        selectionTapHandled = false
                        return true
                    }
                    if (!selectionMode) openMediaIfHit(it, e)
                    return true
                }

                // 双击：复制 + 震动（仅普通模式；多选模式吞掉，避免误复制）
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val it = item ?: return false
                    if (!selectionMode) copyText(view.context, it)
                    return true
                }

                // 长按：多选 → toggle；普通 → 进入多选
                override fun onLongPress(e: MotionEvent) {
                    val it = item ?: return
                    if (selectionMode) onItemClick(it) else onItemLongClick(it)
                }
            }
            val detector = GestureDetector(ctx, gestureListener)
            detector.setOnDoubleTapListener(gestureListener)  // 关键：不设置则双击/单击确认永不回调
            view.setOnTouchListener { _, ev ->
                detector.onTouchEvent(ev)
                true
            }
        }

        // 普通模式单击：按落点打开媒体
        private fun openMediaIfHit(item: TopicMessage, ev: MotionEvent) {
            val ctx = itemView.context
            when {
                ivMedia.visibility == View.VISIBLE && inViewBounds(ivMedia, ev) -> {
                    if (!item.mediaUrl.isNullOrEmpty()) onImageClick?.invoke(fullUrl(item.mediaUrl))
                }
                llVoice.visibility == View.VISIBLE && inViewBounds(llVoice, ev) -> {
                    if (!item.mediaUrl.isNullOrEmpty()) {
                        // P2P 消息：优先播本地落地文件
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

        // 文件打开：P2P 本地文件走 FileProvider；服务器文件走 URL
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
        val isMedia = item.mediaType != "text" && !item.mediaUrl.isNullOrEmpty()
        val sender = item.senderName.ifEmpty { "unknown" }
        holder.tvSender.text = if (!item.deviceName.isNullOrBlank()) "$sender (${item.deviceName})" else sender
        holder.tvTime.text = timeFormat.format(Date(item.timestamp))
        holder.tvTitle.text = item.title
        holder.tvTitle.visibility = if (item.title.isNotEmpty()) View.VISIBLE else View.GONE
        holder.tvText.text = item.text
        holder.tvText.visibility = if (item.text.isNotEmpty()) View.VISIBLE else View.GONE

        // 媒体渲染（点击打开媒体统一由 ViewHolder 的手势单击按落点分发，不再给子 View 设 listener）
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
                    holder.tvFile.text = "📄 ${item.mediaName ?: "文件"}  (${formatSize(item.mediaSize)})$suffix"
                }
            }
        }

        // 多选态视觉（与通知列表一致：背景色变化，无复选框）
        holder.itemView.setBackgroundColor(
            if (selectionMode && selected.contains(item.id))
                holder.itemView.context.getColor(R.color.brand_primary_light)
            else 0x00000000
        )
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
        vibrate(context)  // 双击复制震动反馈
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
        if (path.startsWith("p2p:")) return path  // P2P 本地文件标识，非 URL
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
