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
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.notifysync.R
import com.notifysync.data.AuthManager
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

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvText: TextView = view.findViewById(R.id.tvText)
        val ivMedia: ImageView = view.findViewById(R.id.ivMedia)
        val llVoice: android.view.View = view.findViewById(R.id.llVoice)
        val llFile: android.view.View = view.findViewById(R.id.llFile)
        val tvFile: TextView = view.findViewById(R.id.tvFile)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_topic_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isMedia = item.mediaType != "text" && !item.mediaUrl.isNullOrEmpty()
        holder.tvSender.text = item.senderName.ifEmpty { "unknown" }
        holder.tvTime.text = timeFormat.format(Date(item.timestamp))
        holder.tvTitle.text = item.title
        holder.tvTitle.visibility = if (item.title.isNotEmpty()) View.VISIBLE else View.GONE
        holder.tvText.text = item.text
        holder.tvText.visibility = if (item.text.isNotEmpty()) View.VISIBLE else View.GONE

        // 媒体渲染
        holder.ivMedia.visibility = View.GONE
        holder.llVoice.visibility = View.GONE
        holder.llFile.visibility = View.GONE
        if (isMedia) {
            when (item.mediaType) {
                "image" -> {
                    holder.ivMedia.visibility = View.VISIBLE
                    loadImage(fullUrl(item.mediaUrl!!), holder.ivMedia)
                    holder.ivMedia.setOnClickListener {
                        if (!selectionMode) onImageClick?.invoke(fullUrl(item.mediaUrl!!))
                    }
                }
                "voice" -> {
                    holder.llVoice.visibility = View.VISIBLE
                    holder.llVoice.setOnClickListener { playVoice(fullUrl(item.mediaUrl!!)) }
                }
                "file" -> {
                    holder.llFile.visibility = View.VISIBLE
                    holder.tvFile.text = "📄 ${item.mediaName ?: "文件"}  (${formatSize(item.mediaSize)})"
                    holder.llFile.setOnClickListener {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl(item.mediaUrl!!)))
                        try { it.context.startActivity(intent) } catch (_: Exception) {}
                    }
                }
            }
        }

        // 多选态视觉（与通知列表一致：背景色变化，无复选框）
        holder.itemView.setBackgroundColor(
            if (selectionMode && selected.contains(item.id))
                holder.itemView.context.getColor(R.color.brand_primary_light)
            else 0x00000000
        )

        // 触摸处理（统一由 itemView 接管，子 View 全部递归禁用触摸）：
        // 普通模式：整条消息任意位置双击复制、长按进入多选；单击按落点打开媒体。
        // 多选模式：整条消息任意位置单击/长按 = 选中或取消（toggle）。
        // 不用 clickable + OnClickListener（复用池中 setOnClickListener(null) 的 clickable 状态
        // 会反复翻转导致点击偶发丢失），改用 OnTouchListener 直接判定抬起，100% 可靠；
        // 滚动由 RecyclerView 拦截机制接管，不受影响。
        clearTouchListeners(holder)
        if (selectionMode) {
            var downX = 0f
            var downY = 0f
            var downTime = 0L
            holder.itemView.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        downTime = System.currentTimeMillis()
                        holder.itemView.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        holder.itemView.isPressed = false
                        if (downTime > 0) {  // downTime==0 表示模式切换前旧手势的 UP，忽略，防止误 toggle
                            downTime = 0L
                            val slop = ViewConfiguration.get(holder.itemView.context).scaledTouchSlop
                            if (Math.abs(ev.x - downX) <= slop && Math.abs(ev.y - downY) <= slop) {
                                onItemClick(item)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        holder.itemView.isPressed = false
                        downTime = 0L
                        true
                    }
                    else -> true
                }
            }
        } else {
            val gesture = GestureDetector(holder.itemView.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    when {
                        holder.ivMedia.visibility == View.VISIBLE && inViewBounds(holder.ivMedia, e) -> {
                            if (!item.mediaUrl.isNullOrEmpty()) onImageClick?.invoke(fullUrl(item.mediaUrl))
                        }
                        holder.llVoice.visibility == View.VISIBLE && inViewBounds(holder.llVoice, e) -> {
                            if (!item.mediaUrl.isNullOrEmpty()) playVoice(fullUrl(item.mediaUrl))
                        }
                        holder.llFile.visibility == View.VISIBLE && inViewBounds(holder.llFile, e) -> {
                            if (!item.mediaUrl.isNullOrEmpty()) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl(item.mediaUrl)))
                                try { holder.itemView.context.startActivity(intent) } catch (_: Exception) {}
                            }
                        }
                    }
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    copyText(holder.itemView.context, item)
                    return true
                }
                override fun onLongPress(e: MotionEvent) {
                    onItemLongClick(item)
                }
            })
            holder.itemView.setOnTouchListener { _, ev -> gesture.onTouchEvent(ev) }
        }
    }

    private fun clearTouchListeners(holder: ViewHolder) {
        disableTouch(holder.itemView)
    }

    private fun disableTouch(v: View) {
        v.setOnClickListener(null)
        v.setOnLongClickListener(null)
        v.isClickable = false
        v.isFocusable = false
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) disableTouch(v.getChildAt(i))
        }
    }

    private fun inViewBounds(v: View, ev: MotionEvent): Boolean {
        val rect = android.graphics.Rect()
        v.getHitRect(rect)
        return rect.contains(ev.x.toInt(), ev.y.toInt())
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
        val base = AuthManager.serverUrl.trimEnd('/')
        return if (path.startsWith("http")) path else "$base$path"
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
