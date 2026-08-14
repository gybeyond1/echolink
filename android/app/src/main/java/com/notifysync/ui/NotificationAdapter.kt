package com.notifysync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notifysync.R
import com.notifysync.data.NotificationItem
import com.notifysync.databinding.ItemNotificationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationAdapter(
    private val onItemClick: (NotificationItem) -> Unit,
    private val onItemLongClick: (NotificationItem) -> Unit
) : ListAdapter<NotificationItem, NotificationAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<NotificationItem>() {
            override fun areItemsTheSame(a: NotificationItem, b: NotificationItem) = a.id == b.id
            override fun areContentsTheSame(a: NotificationItem, b: NotificationItem) = a == b
        }
    }

    private val selected = mutableSetOf<Long>()
    var isSelectionActive = false
        private set

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNotificationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemNotificationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: NotificationItem) {
            binding.tvAppName.text = item.appName
            binding.tvTitle.text = item.title.ifEmpty { "(无标题)" }
            binding.tvText.text = item.text.ifEmpty { "(无内容)" }
            binding.tvTime.text = dateFormat.format(Date(item.timestamp))
            binding.tvPackage.text = item.packageName
            binding.tvDevice.text = item.deviceName ?: ""

            val isSel = isSelectionActive && selected.contains(item.id)
            val ctx = binding.root.context
            binding.root.setCardBackgroundColor(
                if (isSel) ctx.getColor(R.color.brand_primary_light)
                else ctx.getColor(R.color.surface)
            )

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }

            // 双击文字区域复制；多选模式下不拦截点击，让事件冒泡到 root 执行 toggle
            if (isSelectionActive) {
                binding.tvTitle.setOnClickListener(null)
                binding.tvText.setOnClickListener(null)
            } else {
                val doubleClickListener = object : DoubleClickListener() {
                    override fun onDoubleClick(v: View) { copyText(item) }
                }
                binding.tvTitle.setOnClickListener(doubleClickListener)
                binding.tvText.setOnClickListener(doubleClickListener)
            }
        }

        private fun copyText(item: NotificationItem) {
            val text = listOf(item.title, item.text)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            if (text.isBlank()) return
            val cm = binding.root.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("通知内容", text))
            Toast.makeText(binding.root.context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    private abstract class DoubleClickListener : View.OnClickListener {
        private var lastClickTime = 0L
        override fun onClick(v: View) {
            val now = System.currentTimeMillis()
            if (now - lastClickTime < 300) onDoubleClick(v)
            lastClickTime = now
        }
        abstract fun onDoubleClick(v: View)
    }

    // ===== 多选逻辑 =====

    fun toggleSelection(id: Long) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        if (selected.isEmpty()) isSelectionActive = false
        notifyDataSetChanged()
    }

    fun enterSelectionMode() {
        isSelectionActive = true
        notifyDataSetChanged()
    }

    fun selectAll() {
        isSelectionActive = true
        selected.clear()
        selected.addAll(currentList.map { it.id })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selected.clear()
        isSelectionActive = false
        notifyDataSetChanged()
    }

    fun getSelectedIds(): List<Long> = selected.toList()

    val selectedCount: Int
        get() = selected.size
}
