package com.notifysync.ui

import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.notifysync.data.ApiClient
import com.notifysync.data.NotificationItem
import com.notifysync.databinding.FragmentNotificationsBinding
import kotlinx.coroutines.launch

class NotificationsFragment : Fragment() {
    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!
    private val adapter = NotificationAdapter(
        onItemClick = { onItemClick(it) },
        onItemLongClick = { onItemLongClick(it) }
    )
    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        // 下拉刷新
        binding.swipeRefresh.setOnRefreshListener { loadNotifications() }

        // 多选操作栏（右上角：全选 / 删除；退出多选用系统返回手势）
        binding.btnSelectAll.setOnClickListener {
            adapter.selectAll()
            updateSelectionUI()
        }
        binding.btnDeleteSelected.setOnClickListener { deleteSelected() }

        // 系统返回：多选态 → 先退出多选（不再直接退到桌面）；非多选态不拦截
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                adapter.clearSelection()
                updateSelectionUI()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        loadNotifications()
    }

    fun refresh() {
        if (_binding != null) loadNotifications()
    }

    private fun onItemClick(item: NotificationItem) {
        if (adapter.isSelectionActive) {
            adapter.toggleSelection(item.id)
            updateSelectionUI()
        }
    }

    private fun onItemLongClick(item: NotificationItem) {
        val firstEnter = !adapter.isSelectionActive
        adapter.enterSelectionMode()
        adapter.toggleSelection(item.id)
        if (firstEnter) vibrate()  // 只有进入多选那一下震动，点选其他不震动
        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        val active = adapter.isSelectionActive
        binding.selectionBar.visibility = if (active) View.VISIBLE else View.GONE
        backCallback.isEnabled = active
    }

    // 触觉反馈：长按进入多选时短震动一次
    private fun vibrate() {
        try {
            val v = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(30)
            }
        } catch (_: Exception) {}
    }

    private fun loadNotifications() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val notifications = ApiClient.getNotifications(50, 0)
                adapter.submitList(notifications)
                binding.tvEmpty.visibility = if (notifications.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                binding.tvEmpty.text = "加载失败: ${e.message}"
                binding.tvEmpty.visibility = View.VISIBLE
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun deleteSelected() {
        val ids = adapter.getSelectedIds()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            try {
                ids.forEach { id -> ApiClient.deleteNotification(id) }
                adapter.clearSelection()
                updateSelectionUI()
                loadNotifications()
                Toast.makeText(requireContext(), "已删除 ${ids.size} 条", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
