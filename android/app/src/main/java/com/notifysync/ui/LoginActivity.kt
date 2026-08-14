package com.notifysync.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notifysync.data.ApiClient
import com.notifysync.data.AuthManager
import com.notifysync.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private var isRegisterMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthManager.init(applicationContext)

        // 已登录则直接进入主页
        if (AuthManager.isLoggedIn) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
    }

    private fun setupViews() {
        updateMode()

        // 预填上次保存的服务器地址（便于切换设备后快速登录）
        binding.etServerUrl.setText(AuthManager.serverUrl)

        // 预填设备名：默认"厂商 型号"，可改成"手机""平板"等自定义名字
        binding.etDeviceName.setText(
            AuthManager.deviceName ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        )

        binding.btnToggleMode.setOnClickListener {
            isRegisterMode = !isRegisterMode
            updateMode()
        }

        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val serverUrl = binding.etServerUrl.text.toString().trim()

            // 服务器地址必填，必须 http/https 开头
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "请填写服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
                Toast.makeText(this, "服务器地址需以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请填写用户名和密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (username.length < 3) {
                Toast.makeText(this, "用户名至少 3 个字符", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.length < 6) {
                Toast.makeText(this, "密码至少 6 个字符", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AuthManager.serverUrl = serverUrl

            setLoading(true)
            lifecycleScope.launch {
                try {
                    val response = if (isRegisterMode) {
                        ApiClient.register(username, password)
                    } else {
                        ApiClient.login(username, password)
                    }

                    AuthManager.token = response.token
                    AuthManager.userId = response.userId
                    AuthManager.username = response.username

                    // 注册设备（设备名可自定义，默认厂商+型号）
                    val deviceName = binding.etDeviceName.text.toString()
                        .trim()
                        .ifEmpty { "${Build.MANUFACTURER} ${Build.MODEL}" }
                    val deviceResp = ApiClient.registerDevice(deviceName)
                    AuthManager.deviceId = deviceResp.getLong("device_id")
                    AuthManager.deviceName = deviceName

                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    setLoading(false)
                    Toast.makeText(
                        this@LoginActivity,
                        "操作失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.btnTestConnection.setOnClickListener {
            val serverUrl = binding.etServerUrl.text.toString().trim()
            if (serverUrl.isNotEmpty()) {
                AuthManager.serverUrl = serverUrl
            }
            setLoading(true)
            lifecycleScope.launch {
                val ok = ApiClient.checkHealth()
                setLoading(false)
                Toast.makeText(
                    this@LoginActivity,
                    if (ok) "服务器连接成功" else "无法连接到服务器",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateMode() {
        if (isRegisterMode) {
            binding.btnLogin.text = "注册"
            binding.btnToggleMode.text = "已有账号？去登录"
            binding.tvTitle.text = "创建账号"
        } else {
            binding.btnLogin.text = "登录"
            binding.btnToggleMode.text = "没有账号？去注册"
            binding.tvTitle.text = "欢迎回来"
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnTestConnection.isEnabled = !loading
    }
}
