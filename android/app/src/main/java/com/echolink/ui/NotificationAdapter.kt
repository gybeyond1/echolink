package com.echolink.ui

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
import com.echolink.R
import com.echolink.data.NotificationItem
import com.echolink.databinding.ItemNotificationBinding
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

        var item: NotificationItem? = null

        init {
            // 行业通用做法：ViewHolder 构造时一次性建立手势处理（与 TopicAdapter 同构）。
            // onBindViewHolder 只更新数据，不再重建 listener，杜绝复用池 / submitList 刷新
            // 打断手势状态导致的"点不动 / 要点好几下"。
            val ctx = binding.root.context
            val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                // 单击：多选模式立即 toggle；普通模式忽略（通知页普通模式单击无操作）
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val it = item ?: return false
                    if (isSelectionActive) {
                        onItemClick(it)
                        return true
                    }
                    return false
                }

                // 单击确认：通知页普通模式单击无操作
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean = true

                // 双击：复制 + 震动（仅普通模式；多选模式吞掉，避免误复制）
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val it = item ?: return false
                    if (!isSelectionActive) copyText(it)
                    return true
                }

                // 长按：多选 → toggle；普通 → 进入多选
                override fun onLongPress(e: MotionEvent) {
                    val it = item ?: return
                    if (isSelectionActive) onItemClick(it) else onItemLongClick(it)
                }
            }
            val detector = GestureDetector(ctx, gestureListener)
            detector.setOnDoubleTapListener(gestureListener)  // 关键：不设置则双击/单击确认永不回调
            binding.root.setOnTouchListener { _, ev ->
                detector.onTouchEvent(ev)
                true
            }
        }

        fun bind(item: NotificationItem) {
            this.item = item
            binding.tvAppName.text = item.appName
            binding.tvTitle.text = item.title.ifEmpty { "(无标题)" }
            binding.tvText.text = item.text.ifEmpty { "(无内容)" }
            binding.tvTime.text = dateFormat.format(Date(item.timestamp))
            binding.tvPackage.text = item.packageName
            binding.tvDevice.text = item.deviceName ?: ""

            val isSel = isSelectionActive && selected.contains(item.id)
            binding.root.setBackgroundResource(
                if (isSel) R.drawable.bg_neumorph_sel else R.drawable.bg_neumorph
            )
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
