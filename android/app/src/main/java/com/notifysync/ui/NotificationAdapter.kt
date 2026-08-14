package com.notifysync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
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

            // 触摸处理：
            // 普通模式：整条卡片任意位置双击复制、长按进入多选；子 View 不再消费事件，
            // 统一由 root 的 GestureDetector 处理，单击不执行额外操作。
            // 多选模式：root 响应点击 toggle，所有子 View 不拦截触摸。
            // 注意：setOnClickListener(null) 不会恢复 clickable=false，必须显式 isClickable=false。
            clearTouchListeners()
            if (isSelectionActive) {
                binding.root.setOnTouchListener(null)
                binding.root.setOnClickListener { onItemClick(item) }
                binding.root.setOnLongClickListener { onItemLongClick(item); true }
            } else {
                val gesture = GestureDetector(binding.root.context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean = true
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        copyText(item)
                        return true
                    }
                    override fun onLongPress(e: MotionEvent) {
                        onItemLongClick(item)
                    }
                })
                binding.root.setOnTouchListener { _, ev -> gesture.onTouchEvent(ev) }
                binding.root.setOnClickListener(null)
                binding.root.setOnLongClickListener(null)
            }
        }

        private fun clearTouchListeners() {
            binding.tvTitle.setOnClickListener(null)
            binding.tvTitle.setOnLongClickListener(null)
            binding.tvTitle.isClickable = false
            binding.tvTitle.isFocusable = false
            binding.tvText.setOnClickListener(null)
            binding.tvText.setOnLongClickListener(null)
            binding.tvText.isClickable = false
            binding.tvText.isFocusable = false
        }

        private fun copyText(item: NotificationItem) {
            val text = listOf(item.title, item.text)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            if (text.isBlank()) return
            val cm = binding.root.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("通知内容", text))
            vibrate(binding.root.context)  // 双击复制震动反馈
            Toast.makeText(binding.root.context, "已复制", Toast.LENGTH_SHORT).show()
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
    }

    val isAllSelected: Boolean
        get() = currentList.isNotEmpty() && currentList.all { selected.contains(it.id) }

    // ===== 多选逻辑 =====

    fun toggleSelection(id: Long) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        if (selected.isEmpty()) isSelectionActive = false
        val pos = currentList.indexOfFirst { it.id == id }
        if (pos >= 0) notifyItemChanged(pos) else notifyDataSetChanged()
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
