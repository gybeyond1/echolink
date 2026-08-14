package com.notifysync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
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

        var longPressRunnable: Runnable? = null

        fun bind(item: NotificationItem) {
            longPressRunnable?.let { binding.root.removeCallbacks(it) }
            longPressRunnable = null
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

            // 触摸处理（统一由 root 接管，子 View 全部递归禁用触摸）：
            // 普通模式：整条卡片任意位置双击复制、长按进入多选。
            // 多选模式：整条卡片任意位置单击/长按 = 选中或取消（toggle）。
            // 不用 clickable + OnClickListener（复用池中 setOnClickListener(null) 的 clickable 状态
            // 会反复翻转导致点击偶发丢失），改用 OnTouchListener 直接判定抬起，100% 可靠；
            // 滚动由 RecyclerView 拦截机制接管，不受影响。
            clearTouchListeners()
            if (isSelectionActive) {
                var downX = 0f
                var downY = 0f
                var downTime = 0L
                binding.root.setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = ev.x
                            downY = ev.y
                            downTime = System.currentTimeMillis()
                            binding.root.isPressed = true
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            binding.root.isPressed = false
                            if (downTime > 0) {  // downTime==0 表示模式切换前旧手势的 UP，忽略，防止误 toggle
                                downTime = 0L
                                val slop = ViewConfiguration.get(binding.root.context).scaledTouchSlop
                                if (Math.abs(ev.x - downX) <= slop && Math.abs(ev.y - downY) <= slop) {
                                    onItemClick(item)
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            binding.root.isPressed = false
                            downTime = 0L
                            true
                        }
                        else -> true
                    }
                }
            } else {
                // 普通模式：手写手势判定（与多选模式同一套 OnTouchListener 机制，最可靠）。
                // 长按 = DOWN 后 400ms 内未移动/未抬起 -> 进入多选；位移超 touchSlop 或提前抬起则取消。
                // 双击 = 两次单击间隔 < doubleTapTimeout -> 复制并震动；单击 = 无操作。
                // downTime 守卫：模式切换瞬间旧手势的 UP 不再误触发单击/双击。
                var downX = 0f
                var downY = 0f
                var downTime = 0L
                var moved = false
                var longPressFired = false
                var lastUpTime = 0L
                val longPressRunnable = Runnable {
                    longPressFired = true
                    onItemLongClick(item)
                }
                longPressRunnable.let { longPressRunnable ->
                    binding.root.setOnTouchListener { _, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = ev.x
                                downY = ev.y
                                downTime = System.currentTimeMillis()
                                moved = false
                                longPressFired = false
                                // 双击第二下（距上次单击 < doubleTapTimeout）不启动长按，避免双击误入多选
                                if (System.currentTimeMillis() - lastUpTime >= ViewConfiguration.getDoubleTapTimeout().toLong()) {
                                    binding.root.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                                }
                                binding.root.isPressed = true
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val slop = ViewConfiguration.get(binding.root.context).scaledTouchSlop
                                if (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop) {
                                    moved = true
                                    binding.root.removeCallbacks(longPressRunnable)
                                }
                                true
                            }
                            MotionEvent.ACTION_UP -> {
                                binding.root.removeCallbacks(longPressRunnable)
                                binding.root.isPressed = false
                                if (downTime > 0L) {  // 忽略模式切换前的旧手势 UP
                                    downTime = 0L
                                    val slop = ViewConfiguration.get(binding.root.context).scaledTouchSlop
                                    val withinSlop = Math.abs(ev.x - downX) <= slop && Math.abs(ev.y - downY) <= slop
                                    if (!longPressFired && !moved && withinSlop) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastUpTime < ViewConfiguration.getDoubleTapTimeout().toLong()) {
                                            lastUpTime = 0L
                                            copyText(item)
                                        } else {
                                            lastUpTime = now
                                        }
                                    }
                                }
                                true
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                binding.root.removeCallbacks(longPressRunnable)
                                binding.root.isPressed = false
                                downTime = 0L
                                true
                            }
                            else -> true
                        }
                    }
                }
            }
        }

        private fun clearTouchListeners() {
            disableTouch(binding.root)
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
