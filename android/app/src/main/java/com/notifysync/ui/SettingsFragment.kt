package com.notifysync.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.notifysync.data.ApiClient
import com.notifysync.data.AppFilter
import com.notifysync.data.AppFilterStore
import com.notifysync.data.AuthManager
import com.notifysync.databinding.FragmentSettingsBinding
import com.notifysync.service.NotificationListener
import com.notifysync.service.SyncService
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val filterAdapter = AppFilterAdapter { filter, enabled -> onFilterToggle(filter, enabled) }
    private val selectedPackages = mutableSetOf<String>()

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
        try {
            setupUI()
        } catch (e: Exception) {
            // 兜底：任何异常都不要在打开设置时直接闪退，给出可阅读的错误提示
            android.util.Log.e("SettingsFragment", "onViewCreated error", e)
            try {
                Toast.makeText(requireContext(), "设置页出错: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) { /* ignore */ }
        }
    }

    private fun setupUI() {
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

        // 应用过滤
        setupAppFilter()
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

    // ===== 应用过滤（选择哪些应用的通知会被读取并上传） =====

    private fun setupAppFilter() {
        binding.rvAppFilter.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAppFilter.adapter = filterAdapter

        binding.swFilterEnabled.isChecked = AppFilterStore.isFilterEnabled
        binding.swFilterEnabled.setOnCheckedChangeListener { _, isChecked ->
            // 实时更新本地过滤（监听器立即生效），保存按钮再同步到服务器
            AppFilterStore.setFilter(requireContext(), isChecked, selectedPackages.toSet())
        }

        binding.btnSaveFilters.setOnClickListener { saveAppFilters() }

        loadAppFilters()
    }

    private fun loadAppFilters() {
        binding.progressBarFilters.visibility = View.VISIBLE
        binding.tvEmptyFilters.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val serverFilters = ApiClient.getFilters().associateBy { it.packageName }

                val pm = requireContext().packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 } // 只显示非系统应用
                    .map {
                        val packageName = it.packageName
                        val existing = serverFilters[packageName]
                        AppFilter(
                            packageName = packageName,
                            appName = pm.getApplicationLabel(it).toString(),
                            enabled = existing?.enabled ?: false
                        )
                    }
                    .sortedBy { it.appName.lowercase() }

                selectedPackages.clear()
                selectedPackages.addAll(apps.filter { it.enabled }.map { it.packageName })

                filterAdapter.submitList(apps)
                binding.tvEmptyFilters.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                binding.tvEmptyFilters.text = "加载失败: ${e.message}"
                binding.tvEmptyFilters.visibility = View.VISIBLE
            } finally {
                binding.progressBarFilters.visibility = View.GONE
            }
        }
    }

    private fun onFilterToggle(filter: AppFilter, enabled: Boolean) {
        filter.enabled = enabled
        if (enabled) selectedPackages.add(filter.packageName) else selectedPackages.remove(filter.packageName)
        // 本地立即生效，服务器在「保存」时同步
        AppFilterStore.setFilter(requireContext(), binding.swFilterEnabled.isChecked, selectedPackages.toSet())
    }

    private fun saveAppFilters() {
        val isOn = binding.swFilterEnabled.isChecked
        // 启用时只把勾选的应用提交到服务器；关闭时提交空列表（= 同步所有）
        val enabledItems = if (isOn) filterAdapter.currentList.filter { it.enabled } else emptyList()

        binding.progressBarFilters.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                ApiClient.batchUpdateFilters(enabledItems)
                AppFilterStore.setFilter(requireContext(), isOn, selectedPackages.toSet())
                Toast.makeText(requireContext(), "应用过滤已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBarFilters.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
