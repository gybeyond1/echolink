package com.notifysync.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.notifysync.R
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

    companion object {
        private const val REQ_SMS_PERMISSION = 1001
    }

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

        // 外观主题切换（跟随系统 / 浅色 / 深色）
        setupThemeToggle()

        // 通知监听权限状态
        updatePermissionStatus()

        // 自定义设备名
        setupDeviceRename()

        // 短信验证码自动提取
        setupSmsCapture()

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

    // ===== 外观主题切换 =====

    private fun setupThemeToggle() {
        // 初始化选中态
        when (AuthManager.themeMode) {
            "light" -> binding.toggleTheme.check(R.id.btnThemeLight)
            "dark" -> binding.toggleTheme.check(R.id.btnThemeDark)
            else -> binding.toggleTheme.check(R.id.btnThemeSystem)
        }
        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnThemeLight -> "light"
                R.id.btnThemeDark -> "dark"
                else -> "system"
            }
            AuthManager.themeMode = mode
            when (mode) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }

    // ===== 自定义设备名 =====

    private fun setupDeviceRename() {
        binding.llDeviceName.setOnClickListener {
            val input = EditText(requireContext()).apply {
                setText(AuthManager.deviceName ?: "")
                hint = "设备名（通知里会用它标识来源设备）"
            }
            val padding = (16 * resources.displayMetrics.density).toInt()
            input.setPadding(padding, padding, padding, padding)
            AlertDialog.Builder(requireContext())
                .setTitle("重命名设备")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isEmpty()) {
                        Toast.makeText(requireContext(), "设备名不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (newName.length > 64) {
                        Toast.makeText(requireContext(), "设备名最多 64 个字符", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    lifecycleScope.launch {
                        try {
                            ApiClient.renameDevice(AuthManager.deviceId, newName)
                            AuthManager.deviceName = newName
                            binding.tvDeviceName.text = newName
                            Toast.makeText(requireContext(), "设备名已更新", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "改名失败: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ===== 短信验证码自动提取 =====

    private fun setupSmsCapture() {
        binding.swSmsCapture.isChecked = AuthManager.smsCaptureEnabled
        binding.swSmsCapture.setOnCheckedChangeListener { _, isChecked ->
            AuthManager.smsCaptureEnabled = isChecked
            if (isChecked && !hasSmsPermission()) {
                Toast.makeText(requireContext(), "请先授权读取短信", Toast.LENGTH_SHORT).show()
            }
            // 让常驻服务按新状态启停短信监听
            SyncService.start(requireContext())
        }
        binding.btnSmsPermission.setOnClickListener {
            if (hasSmsPermission()) {
                Toast.makeText(requireContext(), "已拥有读取短信权限", Toast.LENGTH_SHORT).show()
            } else {
                requestSmsPermission()
            }
        }
        updateSmsStatus()
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestSmsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
                REQ_SMS_PERMISSION
            )
        } else {
            Toast.makeText(requireContext(), "当前系统版本无需额外授权", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SMS_PERMISSION) {
            // 两个权限都通过才算授权成功（RECEIVE_SMS 收广播，READ_SMS 兜底扫收件箱）
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                AuthManager.smsCaptureEnabled = true
                binding.swSmsCapture.isChecked = true
                SyncService.start(requireContext())
                Toast.makeText(requireContext(), "已授权，验证码将自动复制到剪贴板", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), "未授权读取短信", Toast.LENGTH_SHORT).show()
            }
            updateSmsStatus()
        }
    }

    private fun updateSmsStatus() {
        val granted = hasSmsPermission()
        binding.tvSmsStatus.text = if (granted) "已授权" else "未授权"
        binding.tvSmsStatus.setTextColor(
            requireContext().getColor(if (granted) R.color.ok else R.color.danger)
        )
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateSmsStatus()
    }

    private fun updatePermissionStatus() {
        val granted = NotificationListener.isListenerEnabled(requireContext())
        binding.tvPermissionStatus.text = if (granted) "已授权" else "未授权"
        binding.tvPermissionStatus.setTextColor(
            requireContext().getColor(
                if (granted) R.color.ok else R.color.danger
            )
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
