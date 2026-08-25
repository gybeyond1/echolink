package com.echolink.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 主题偏好：跟随系统 / 浅色 / 深色。
 * 持久化在应用级 SharedPreferences，App 启动时由 App.applyThemeMode 读取并应用，
 * 设置页可随时切换并立即重建 Activity。
 */
object ThemePrefs {
    private const val PREFS = "echolink_theme"
    private const val KEY = "mode"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    fun getMode(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getInt(KEY, MODE_SYSTEM)
    }

    fun setMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY, mode)
            .apply()
    }

    /** 将本地存储的模式映射到 AppCompatDelegate 的夜间模式 */
    fun toNightMode(mode: Int): Int = when (mode) {
        MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** 应用当前存储的主题模式 */
    fun apply(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(getMode(ctx)))
    }
}
