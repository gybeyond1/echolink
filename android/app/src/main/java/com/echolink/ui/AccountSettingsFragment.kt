package com.echolink.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.echolink.R
import com.echolink.data.ApiClient
import com.echolink.data.AuthManager
import com.echolink.data.AvatarLoader
import com.echolink.databinding.FragmentAccountSettingsBinding
import com.echolink.service.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AccountSettingsFragment : Fragment() {
    private var _binding: FragmentAccountSettingsBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val REQ_PICK_AVATAR = 1002
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadProfile()

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.llNickname.setOnClickListener { showNicknameDialog() }
        binding.llDeviceName.setOnClickListener { showDeviceRenameDialog() }
        binding.flAvatar.setOnClickListener { pickAvatarImage() }
        binding.llChangePassword.setOnClickListener {
            Toast.makeText(requireContext(), "密码修改功能开发中", Toast.LENGTH_SHORT).show()
        }
        binding.btnLogout.setOnClickListener {
            SyncService.stop(requireContext())
            AuthManager.logout()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }
    }

    private fun loadProfile() {
        binding.tvDisplayName.text = AuthManager.displayName ?: AuthManager.username ?: "—"
        binding.tvDeviceName.text = AuthManager.deviceName ?: "—"
        binding.tvUsername.text = "uid:${AuthManager.username ?: "未知"}"
        loadAvatar(AuthManager.avatarUrl)

        lifecycleScope.launch {
            try {
                val json = ApiClient.getProfile()
                val name = json.optString("display_name", AuthManager.username)
                val avatar = json.optString("avatar", null)
                AuthManager.displayName = name
                AuthManager.avatarUrl = avatar
                binding.tvDisplayName.text = name
                loadAvatar(avatar)
            } catch (_: Exception) {}
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
        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
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
                        requireContext().sendBroadcast(Intent("com.echolink.PROFILE_CHANGED"))
                        Toast.makeText(requireContext(), "昵称已更新", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "修改失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeviceRenameDialog() {
        val input = EditText(requireContext()).apply {
            setText(AuthManager.deviceName ?: "")
            hint = "设备名（通知里会用它标识来源设备）"
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)
        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
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
            val uri = data?.data ?: return
            uploadAvatarFromUri(uri)
        }
    }

    private fun uploadAvatarFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val tmpFile = File(requireContext().cacheDir, "avatar_upload_${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
                    } ?: throw Exception("无法读取图片")
                }
                val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(tmpFile.absolutePath) }
                if (bmp != null) {
                    binding.ivAvatar.setImageBitmap(cropCircle(bmp))
                }
                val json = ApiClient.uploadAvatar(tmpFile)
                val avatarPath = json.optString("avatar", null)
                if (!avatarPath.isNullOrBlank()) {
                    val oldUrl = AuthManager.avatarUrl
                    AuthManager.avatarUrl = avatarPath
                    AvatarLoader.refresh(ApiClient.fullAvatarUrl(avatarPath), binding.ivAvatar)
                    if (!oldUrl.isNullOrBlank()) {
                        AvatarLoader.invalidate(ApiClient.fullAvatarUrl(oldUrl))
                    }
                    requireContext().sendBroadcast(Intent("com.echolink.PROFILE_CHANGED"))
                    Toast.makeText(requireContext(), "头像已更新", Toast.LENGTH_SHORT).show()
                }
                tmpFile.delete()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "上传头像失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
