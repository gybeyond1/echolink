package com.notifysync.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
        notifyDataSetChanged()
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
        val cbSelect: android.widget.CheckBox = view.findViewById(R.id.cbSelect)
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

        // 多选态视觉
        holder.cbSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.cbSelect.isChecked = selected.contains(item.id)
        holder.itemView.setBackgroundColor(
            if (selected.contains(item.id)) 0xFFE3F2FD.toInt() else 0x00000000
        )

        holder.itemView.setOnClickListener {
            if (selectionMode) onItemClick(item) else { /* 普通模式单击不处理 */ }
        }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(item)
            true
        }
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
