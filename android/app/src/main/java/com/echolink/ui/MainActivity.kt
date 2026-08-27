package com.echolink.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.echolink.data.AppFilterStore
import com.echolink.data.AuthManager
import com.echolink.databinding.ActivityMainBinding
import com.echolink.R
import com.echolink.data.ServerSelector
import com.echolink.data.ThemePrefs
import com.echolink.service.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    /** 是否平板布局（最小宽度 ≥600dp） */
    private val isTablet: Boolean get() = resources.configuration.smallestScreenWidthDp >= 600

    /** 平板端 DrawerLayout（两配置根元素同名 ID 导致 ViewBinding 类型为 View?，直接强转 root） */
    private val drawer: DrawerLayout get() = binding.root as DrawerLayout

    /** 平板左半屏右滑打开侧滑栏（避免与系统左滑返回冲突） */
    private val drawerGestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!isTablet || e1 == null) return false
                val screenW = resources.displayMetrics.widthPixels
                // 只在左半屏（≤40%宽度）按下时响应右滑
                if (e1.x > screenW * 0.4f) return false
                // 右滑：distanceX < 0（手指向右移动），且水平位移 > 垂直位移
                if (distanceX < -25 && Math.abs(distanceX) > Math.abs(distanceY) * 1.5f) {
                    if (!drawer.isDrawerOpen(GravityCompat.START)) {
                        drawer.openDrawer(GravityCompat.START)
                        return true
                    }
                }
                return false
            }
        })
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
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

        CoroutineScope(Dispatchers.IO).launch {
            try { ServerSelector.selectOptimal(applicationContext) } catch (_: Exception) {}
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.setBackgroundColor(ContextCompat.getColor(this, R.color.background))

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val insetsCtrl = WindowInsetsControllerCompat(window, window.decorView)
        insetsCtrl.isAppearanceLightStatusBars = !isNight
        insetsCtrl.isAppearanceLightNavigationBars = !isNight

        if (isTablet) {
            setupDrawer()
        } else {
            binding.bottomNav?.background = null
            setupBottomNav()
        }

        SyncService.start(this)
        registerReceiver(
            notificationReceiver,
            IntentFilter("com.echolink.NOTIFICATION_RECEIVED"),
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
            if (!isTablet) {
                binding.bottomNav!!.menu.findItem(R.id.nav_topic)?.isChecked = true
            } else {
                binding.navView?.setCheckedItem(R.id.nav_messages)
            }
        }
    }

    // ===== 平板侧滑栏 =====

    private fun setupDrawer() {
        val toolbar = binding.toolbar ?: return
        val navView = binding.navView ?: return
        // 避开状态栏：顶部 padding = 状态栏高度，左侧多留 8dp
        val statusBarH = resources.getDimensionPixelSize(
            resources.getIdentifier("status_bar_height", "dimen", "android")
        ).coerceAtLeast(0)
        val left8 = (8 * resources.displayMetrics.density).toInt()
        toolbar.setPadding(toolbar.paddingStart + left8, statusBarH, toolbar.paddingEnd, toolbar.paddingBottom)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener {
            drawer.openDrawer(GravityCompat.START)
        }
        // 侧滑栏头部用户信息
        val header = navView.getHeaderView(0)
        if (header != null) {
            header.findViewById<android.widget.TextView>(R.id.navDisplayName)?.text =
                AuthManager.displayName ?: AuthManager.username ?: "用户"
            header.findViewById<android.widget.TextView>(R.id.navUsername)?.text =
                "@${AuthManager.username ?: ""}"
            // 加载用户头像
            val avatarIv = header.findViewById<android.widget.ImageView>(R.id.navAvatar)
            if (avatarIv != null) {
                com.echolink.data.AvatarLoader.load(
                    com.echolink.data.ApiClient.fullAvatarUrl(AuthManager.avatarUrl), avatarIv
                )
            }
            // 点击用户信息框 → 账号设置
            header.findViewById<View>(R.id.navUserBox)?.setOnClickListener {
                drawer.closeDrawer(GravityCompat.START)
                openAccountSettings()
            }
        }

        navView.setNavigationItemSelectedListener { item ->
            drawer.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.nav_messages -> switchFragment(TopicFragment())
                R.id.nav_friends -> switchFragment(FriendsFragment())
                R.id.nav_settings -> switchFragment(SettingsFragment())
                R.id.nav_theme -> showThemeDialog()
            }
            true
        }
    }

    /** 侧滑栏底部用户信息 → 账号设置 */
    fun openAccountSettings() {
        supportFragmentManager
            .beginTransaction()
            .replace(binding.fragmentContainer.id, AccountSettingsFragment())
            .addToBackStack(null)
            .commit()
        binding.toolbar?.title = "账号设置"
    }

    /** 切换主题对话框（跟随系统/浅色/深色） */
    private fun showThemeDialog() {
        val modes = arrayOf("跟随系统", "浅色", "深色")
        val current = ThemePrefs.getMode(this)
        val checked = when (current) {
            ThemePrefs.MODE_LIGHT -> 1
            ThemePrefs.MODE_DARK -> 2
            else -> 0
        }
        android.app.AlertDialog.Builder(this, R.style.Theme_EchoLink_Dialog)
            .setTitle("主题")
            .setSingleChoiceItems(modes, checked) { dlg, which ->
                val mode = when (which) {
                    1 -> ThemePrefs.MODE_LIGHT
                    2 -> ThemePrefs.MODE_DARK
                    else -> ThemePrefs.MODE_SYSTEM
                }
                ThemePrefs.setMode(this, mode)
                ThemePrefs.apply(this)
                dlg.dismiss()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 手机底部导航 =====

    private fun setupBottomNav() {
        binding.bottomNav!!.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_friends -> FriendsFragment()
                R.id.nav_topic -> TopicFragment()
                else -> return@setOnItemSelectedListener false
            }
            switchFragment(fragment)
            true
        }
    }

    fun openSettings() {
        switchFragment(SettingsFragment())
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(binding.fragmentContainer.id, fragment)
            .commit()
        // FAB 可见性由各 Fragment 在 onResume 中自行控制（聊天态隐藏、列表态显示）
        // 平板：更新标题和侧滑栏选中状态
        if (isTablet) {
            binding.toolbar?.title = when (fragment) {
                is TopicFragment -> "消息"
                is FriendsFragment -> "好友"
                is SettingsFragment -> "设置"
                is NotificationsFragment -> "通知"
                else -> "EchoLink"
            }
            val navItem = when (fragment) {
                is FriendsFragment -> R.id.nav_friends
                is SettingsFragment -> R.id.nav_settings
                else -> R.id.nav_messages
            }
            binding.navView?.setCheckedItem(navItem)
        }
    }

    fun openNotifications() {
        switchFragment(NotificationsFragment())
        if (!isTablet) {
            binding.bottomNav!!.menu.findItem(R.id.nav_topic)?.isChecked = true
        }
    }

    fun backToTopics() {
        switchFragment(TopicFragment())
        if (!isTablet) {
            binding.bottomNav!!.menu.findItem(R.id.nav_topic)?.isChecked = true
        }
    }

    fun openTopic(topic: String, title: String? = null) {
        val frag = TopicFragment()
        frag.arguments = Bundle().apply {
            putString("topic", topic)
            if (title != null) putString("title", title)
        }
        switchFragment(frag)
        if (!isTablet) {
            binding.bottomNav!!.menu.findItem(R.id.nav_topic)?.isChecked = true
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isTablet && !drawer.isDrawerOpen(GravityCompat.START)) {
            drawerGestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (isTablet && drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(notificationReceiver) } catch (_: Exception) {}
    }

    companion object {
        const val EXTRA_TOPIC = "com.echolink.topic"
    }
}
