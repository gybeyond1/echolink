package com.notifysync.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notifysync.data.ApiClient
import com.notifysync.data.AuthManager
import com.notifysync.databinding.FragmentSettingsBinding
import com.notifysync.service.NotificationListener
import com.notifysync.service.SyncService
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 显示当前信息
        binding.tvUsername.text = AuthManager.username ?: "未知"
        binding.tvDeviceName.text = AuthManager.deviceName ?: "未知"
        binding.etServerUrl.setText(AuthManager.serverUrl)

        // 通知监听权限状态
        updatePermissionStatus()

        // 通知监听权限按钮
        binding.btnNotificationPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        // 保存服务器地址
        binding.btnSaveServerUrl.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                AuthManager.serverUrl = url
                Toast.makeText(requireContext(), "服务器地址已保存", Toast.LENGTH_SHORT).show()
                // 重启同步服务
                SyncService.stop(requireContext())
                SyncService.start(requireContext())
            }
        }

        // 测试连接
        binding.btnTestConnection.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                AuthManager.serverUrl = url
            }
            binding.btnTestConnection.isEnabled = false
            lifecycleScope.launch {
                val ok = ApiClient.checkHealth()
                binding.btnTestConnection.isEnabled = true
                Toast.makeText(
                    requireContext(),
                    if (ok) "连接成功" else "连接失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 退出登录
        binding.btnLogout.setOnClickListener {
            SyncService.stop(requireContext())
            AuthManager.logout()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val granted = NotificationListener.isListenerEnabled(requireContext())
        binding.tvPermissionStatus.text = if (granted) "已授权" else "未授权"
        binding.tvPermissionStatus.setTextColor(
            if (granted) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
