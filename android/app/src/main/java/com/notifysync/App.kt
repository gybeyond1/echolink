package com.notifysync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.notifysync.data.AuthManager
import com.notifysync.data.AppFilterStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        AuthManager.init(this)
        AppFilterStore.init(this)
        createNotificationChannels()
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
    }
}
