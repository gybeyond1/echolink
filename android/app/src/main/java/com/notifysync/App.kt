package com.notifysync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.notifysync.data.AuthManager
import com.notifysync.data.AppFilterStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        AuthManager.init(this)
        AppFilterStore.init(this)
        applyThemeMode()
        createNotificationChannels()
    }

    private fun applyThemeMode() {
        when (AuthManager.themeMode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 前台服务通道
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "同步服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持通知同步服务运行"
            }
            manager.createNotificationChannel(serviceChannel)

            // 同步通知通道
            val notifyChannel = NotificationChannel(
                CHANNEL_NOTIFICATIONS,
                "同步通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "接收来自其他设备的通知"
            }
            manager.createNotificationChannel(notifyChannel)
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "sync_service"
        const val CHANNEL_NOTIFICATIONS = "synced_notifications"

        lateinit var instance: App
            private set

        /** 全局 Application Context（供无 View 上下文的工具类使用，如 AvatarLoader 磁盘缓存） */
        val appContext: android.content.Context
            get() = instance.applicationContext
    }
}
