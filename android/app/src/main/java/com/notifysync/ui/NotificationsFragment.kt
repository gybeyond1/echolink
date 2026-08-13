package com.notifysync.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private val adapter = NotificationAdapter()

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

        binding.btnRefresh.setOnClickListener { loadNotifications() }
        binding.btnClear.setOnClickListener { clearNotifications() }

        loadNotifications()
    }

    fun refresh() {
        if (_binding != null) loadNotifications()
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
            }
        }
    }

    private fun clearNotifications() {
        lifecycleScope.launch {
            try {
                ApiClient.clearAllNotifications()
                adapter.submitList(emptyList())
                binding.tvEmpty.visibility = View.VISIBLE
            } catch (e: Exception) {
                // ignored
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
