package com.notifysync.ui

import com.notifysync.data.optNullable

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.notifysync.R
import com.notifysync.data.ApiClient
import com.notifysync.data.AvatarLoader
import com.notifysync.data.AppFilter
import com.notifysync.data.AppFilterStore
import com.notifysync.data.AuthManager
import com.notifysync.databinding.FragmentSettingsBinding
import com.notifysync.service.NotificationListener
import com.notifysync.service.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val filterAdapter = AppFilterAdapter { filter, enabled -> onFilterToggle(filter, enabled) }
    private val selectedPackages = mutableSetOf<String>()

    // 其他设备换头像/昵称后，WS 推送 PROFILE_CHANGED，这里刷新本页头像
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.notifysync.PROFILE_CHANGED") {
                loadAvatar(AuthManager.avatarUrl)
            }
        }
    }

    companion object {
        private const val REQ_SMS_PERMISSION = 1001
        private const val REQ_PICK_AVATAR = 1002
        private const val PROJECT_URL = "https://github.com/gybeyond1/echolink"
        private const val DONATE_URL = "wxp://f2f0gpIJomgrTKj2sOG8gc64wSBei7Z5YVgXgYNcDSZTzZpfpK3RX9ZC8fn2SW5LNCeT"
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
        // 设置页从顶栏齿轮进入：返回键 → 回消息列表
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (activity as? MainActivity)?.backToTopics()
                }
            })
        try {
            setupUI()
        } catch (e: Exception) {
            android.util.Log.e("SettingsFragment", "onViewCreated error", e)
            try {
                Toast.makeText(requireContext(), "设置页出错: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) { /* ignore */ }
        }
    }

    private fun setupUI() {
        // 全面屏沉浸式：顶部栏避开状态栏
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.setPadding(binding.topBar.paddingLeft, systemBars.top, binding.topBar.paddingRight, binding.topBar.paddingBottom)
            insets
        }

        // 账号资料
        loadProfile()
        binding.tvUsername.text = "uid:${AuthManager.username ?: "未知"}"
        binding.tvDeviceName.text = AuthManager.deviceName ?: "未知"
        binding.etServerUrl.setText(AuthManager.serverUrl)

        // 昵称点击编辑
        binding.llNickname.setOnClickListener { showNicknameDialog() }

        // 设备名点击编辑
        binding.llDeviceName.setOnClickListener { showDeviceRenameDialog() }

        // 头像点击上传
        binding.flAvatar.setOnClickListener { pickAvatarImage() }

        // 项目地址
        binding.llProjectUrl.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
            startActivity(intent)
        }

        // 打赏支持（微信收款二维码）
        binding.llDonate.setOnClickListener { showDonateQrDialog() }

        // 通知监听权限状态
        updatePermissionStatus()

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

    // ===== 用户资料（昵称 + 头像） =====

    private fun loadProfile() {
        // 先用本地缓存显示
        binding.tvDisplayName.text = AuthManager.displayName ?: AuthManager.username ?: "—"
        loadAvatar(AuthManager.avatarUrl)

        // 从服务器刷新
        lifecycleScope.launch {
            try {
                val json = ApiClient.getProfile()
                val name = json.optString("display_name", AuthManager.username)
                val avatar = json.optNullable("avatar")
                AuthManager.displayName = name
                AuthManager.avatarUrl = avatar
                binding.tvDisplayName.text = name
                loadAvatar(avatar)
            } catch (e: Exception) {
                // 静默失败，用本地缓存
            }
        }
    }

    private fun loadAvatar(avatarPath: String?) {
        val fullUrl = ApiClient.fullAvatarUrl(avatarPath)
        if (fullUrl.isNullOrBlank()) {
            binding.ivAvatar.setImageResource(R.drawable.ic_default_avatar)
            return
        }
        AvatarLoader.load(fullUrl, binding.ivAvatar)
    }

    private fun cropCircle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val x = (src.width - size) / 2
        val y = (src.height - size) / 2
        val squared = Bitmap.createBitmap(src, x, y, size, size)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(squared, rect, rect, paint)
        if (squared != src) squared.recycle()
        return output
    }

    private fun showNicknameDialog() {
        val input = EditText(requireContext()).apply {
            setText(AuthManager.displayName ?: AuthManager.username ?: "")
            hint = "输入昵称"
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)
        AlertDialog.Builder(requireContext())
            .setTitle("修改昵称")
            .setMessage("昵称会同步到所有设备")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(requireContext(), "昵称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName.length > 32) {
                    Toast.makeText(requireContext(), "昵称最多 32 个字符", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        ApiClient.updateNickname(newName)
                        AuthManager.displayName = newName
                        binding.tvDisplayName.text = newName
                        Toast.makeText(requireContext(), "昵称已更新", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "修改失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .showDimmed()
    }

    private fun pickAvatarImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_PICK_AVATAR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_AVATAR && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                uploadAvatarFromUri(uri)
            }
        }
    }

    private fun uploadAvatarFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                // 把 uri 内容拷贝到临时文件
                val tmpFile = File(requireContext().cacheDir, "avatar_upload_${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("无法读取图片")
                }

                // 先在 UI 上预览（圆形裁剪）
                val bmp = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(tmpFile.absolutePath)
                }
                if (bmp != null) {
                    binding.ivAvatar.setImageBitmap(cropCircle(bmp))
                }

                // 上传到服务器
                val json = ApiClient.uploadAvatar(tmpFile)
                val avatarPath = json.optNullable("avatar")
                if (!avatarPath.isNullOrBlank()) {
                    val oldUrl = AuthManager.avatarUrl
                    AuthManager.avatarUrl = avatarPath
                    // 立即用新头像刷新显示，并让旧缓存失效（避免旧图残留）
                    AvatarLoader.refresh(ApiClient.fullAvatarUrl(avatarPath), binding.ivAvatar)
                    if (!oldUrl.isNullOrBlank()) {
                        AvatarLoader.invalidate(ApiClient.fullAvatarUrl(oldUrl))
                    }
                    Toast.makeText(requireContext(), "头像已更新", Toast.LENGTH_SHORT).show()
                }
                tmpFile.delete()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "上传头像失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDonateQrDialog() {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val imageView = ImageView(ctx)
        imageView.setImageResource(R.drawable.donate_qr)
        imageView.setPadding(pad, pad, pad, pad)
        imageView.adjustViewBounds = true

        val hint = TextView(ctx)
        hint.text = "用微信「扫一扫」扫码打赏，感谢支持！"
        hint.textSize = 14f
        hint.gravity = android.view.Gravity.CENTER
        hint.setPadding(0, 0, 0, pad)

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.addView(imageView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(hint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        AlertDialog.Builder(ctx)
            .setTitle("打赏支持")
            .setView(root)
            .setPositiveButton("知道了", null)
            .showDimmed()
    }

    // ===== 设备改名 =====

    private fun showDeviceRenameDialog() {
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
            .showDimmed()
    }

    // ===== 短信验证码自动提取 =====

    private fun setupSmsCapture() {
        binding.swSmsCapture.isChecked = AuthManager.smsCaptureEnabled
        binding.swSmsCapture.setOnCheckedChangeListener { _, isChecked ->
            AuthManager.smsCaptureEnabled = isChecked
            if (isChecked && !hasSmsPermission()) {
                Toast.makeText(requireContext(), "请先授权读取短信", Toast.LENGTH_SHORT).show()
            }
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
        requireActivity().registerReceiver(
            profileReceiver,
            IntentFilter("com.notifysync.PROFILE_CHANGED"),
            Context.RECEIVER_NOT_EXPORTED
        )
        updatePermissionStatus()
        updateSmsStatus()
    }

    override fun onPause() {
        super.onPause()
        try { requireActivity().unregisterReceiver(profileReceiver) } catch (_: Exception) {}
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

    // ===== 应用过滤 =====

    private fun setupAppFilter() {
        binding.rvAppFilter.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAppFilter.adapter = filterAdapter

        binding.swFilterEnabled.isChecked = AppFilterStore.isFilterEnabled
        binding.swFilterEnabled.setOnCheckedChangeListener { _, isChecked ->
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
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
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
        AppFilterStore.setFilter(requireContext(), binding.swFilterEnabled.isChecked, selectedPackages.toSet())
    }

    private fun saveAppFilters() {
        val isOn = binding.swFilterEnabled.isChecked
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
