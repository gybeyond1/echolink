package com.notifysync.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地持久化「应用过滤」配置，供 NotificationListener 在上传前判断某应用是否允许同步。
 * 与服务端 /api/filters 保持一致：enabled=false 时同步所有应用；
 * enabled=true 时只同步 allowed 集合中的应用。
 */
object AppFilterStore {
    private const val PREFS = "notifysync_app_filters"
    private const val KEY_ENABLED = "filter_enabled"
    private const val KEY_PACKAGES = "allowed_packages"

    private var enabled = false
    private var allowed = emptySet<String>()

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = sp.getBoolean(KEY_ENABLED, false)
        allowed = sp.getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    val isFilterEnabled: Boolean
        get() = enabled

    /** 该应用的通知是否允许被读取并上传 */
    fun isAllowed(packageName: String): Boolean {
        if (!enabled) return true
        return allowed.contains(packageName)
    }

    /** 保存过滤配置（写入本地，监听器立即生效） */
    fun setFilter(context: Context, enabled: Boolean, packages: Set<String>) {
        this.enabled = enabled
        this.allowed = packages
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putStringSet(KEY_PACKAGES, packages)
            .apply()
    }
}
