package com.notifysync.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notifysync.data.AuthManager
import com.notifysync.databinding.ActivityMainBinding
import com.notifysync.service.SyncService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 通知 UI 刷新
            val frag = supportFragmentManager.fragments.firstOrNull { it is NotificationsFragment }
            (frag as? NotificationsFragment)?.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthManager.init(applicationContext)

        if (!AuthManager.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNav()

        // 启动同步服务
        SyncService.start(this)

        // 注册广播接收器
        registerReceiver(
            notificationReceiver,
            IntentFilter("com.notifysync.NOTIFICATION_RECEIVED"),
            Context.RECEIVER_NOT_EXPORTED
        )

        // 默认显示通知页
        if (savedInstanceState == null) {
            switchFragment(NotificationsFragment())
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                com.notifysync.R.id.nav_notifications -> NotificationsFragment()
                com.notifysync.R.id.nav_topic -> TopicFragment()
                com.notifysync.R.id.nav_apps -> AppFilterFragment()
                com.notifysync.R.id.nav_settings -> SettingsFragment()
                else -> return@setOnItemSelectedListener false
            }
            switchFragment(fragment)
            true
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(binding.fragmentContainer.id, fragment)
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(notificationReceiver)
        } catch (e: Exception) {
            // ignored
        }
    }
}
