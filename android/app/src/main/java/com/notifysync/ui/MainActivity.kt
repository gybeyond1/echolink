package com.notifysync.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import android.graphics.Color
import kotlinx.coroutines.launch
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import com.notifysync.data.AppFilterStore
import com.notifysync.data.AuthManager
import com.notifysync.data.BingWallpaper
import com.notifysync.databinding.ActivityMainBinding
import com.notifysync.R
import com.notifysync.service.SyncService

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
        AppFilterStore.init(applicationContext)

        if (!AuthManager.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 浅色兜底底色（壁纸加载完成前不黑屏），壁纸就绪后整体覆盖
        binding.root.setBackgroundColor(ContextCompat.getColor(this, R.color.background))
        // 底部导航栏透明，让全局壁纸（含底栏区域）成为统一整体
        binding.bottomNav.background = null

        // 一次性把统一壁纸画到 Activity 根布局：覆盖状态栏 / 底栏 / 聊天输入框 / 通知页整页
        applyGlobalWallpaper()

        // 全面屏沉浸式：内容延伸到状态栏/导航栏，不保留系统预留内边距
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 状态栏/导航栏透明，让全局壁纸（含顶栏）成为统一整体；
        // 壁纸经浅色遮罩偏亮，故状态栏/导航栏用深色素图标（light appearance）
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val insetsCtrl = WindowInsetsControllerCompat(window, window.decorView)
        insetsCtrl.isAppearanceLightStatusBars = true
        insetsCtrl.isAppearanceLightNavigationBars = true

        setupBottomNav()

        // 启动同步服务
        SyncService.start(this)

        // 注册广播接收器
        registerReceiver(
            notificationReceiver,
            IntentFilter("com.notifysync.NOTIFICATION_RECEIVED"),
            Context.RECEIVER_NOT_EXPORTED
        )

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val topic = intent?.getStringExtra(EXTRA_TOPIC)
        if (!topic.isNullOrEmpty()) {
            openTopic(topic)
        } else if (supportFragmentManager.fragments.isEmpty()) {
            switchFragment(TopicFragment())
            binding.bottomNav.menu.findItem(com.notifysync.R.id.nav_topic)?.isChecked = true
        }
    }

    /** 把统一壁纸一次性画到 Activity 根布局，覆盖状态栏/底栏/各页面整页（含聊天输入框、通知页） */
    private fun applyGlobalWallpaper() {
        lifecycleScope.launch {
            try {
                val bmp = BingWallpaper.load(this@MainActivity)
                if (bmp != null) {
                    binding.root.background = BitmapDrawable(resources, bmp)
                }
            } catch (_: Exception) {
                // 拉取失败保持浅色兜底底色
            }
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                com.notifysync.R.id.nav_friends -> FriendsFragment()
                com.notifysync.R.id.nav_topic -> TopicFragment()
                else -> return@setOnItemSelectedListener false
            }
            switchFragment(fragment)
            true
        }
    }

    /** 顶栏齿轮 → 打开设置页（设置已从底栏移除，改由消息页顶栏进入） */
    fun openSettings() {
        switchFragment(SettingsFragment())
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(binding.fragmentContainer.id, fragment)
            .commit()
    }

    /** 话题列表顶部「通知」置顶条目 → 打开通知详情页（底栏仍高亮话题） */
    fun openNotifications() {
        switchFragment(NotificationsFragment())
        binding.bottomNav.menu.findItem(com.notifysync.R.id.nav_topic)?.isChecked = true
    }

    /** 通知详情页返回 → 回话题列表 */
    fun backToTopics() {
        switchFragment(TopicFragment())
        binding.bottomNav.menu.findItem(com.notifysync.R.id.nav_topic)?.isChecked = true
    }

    /** 打开话题页并定位到指定话题（用于点击状态栏话题通知 / 好友私聊入口） */
    fun openTopic(topic: String, title: String? = null) {
        val frag = TopicFragment()
        frag.arguments = Bundle().apply {
            putString("topic", topic)
            if (title != null) putString("title", title)
        }
        switchFragment(frag)
        // 仅高亮底栏，不触发 onItemSelected 以免丢失话题参数
        binding.bottomNav.menu.findItem(com.notifysync.R.id.nav_topic)?.isChecked = true
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(notificationReceiver)
        } catch (e: Exception) {
            // ignored
        }
    }

    companion object {
        const val EXTRA_TOPIC = "com.notifysync.topic"
    }
}
