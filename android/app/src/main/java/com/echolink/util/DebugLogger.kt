package com.echolink.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志工具：开启后记录详细日志到文件，用于排查问题
 */
object DebugLogger {
    private const val PREFS_NAME = "debug_prefs"
    private const val KEY_ENABLED = "debug_enabled"
    private const val LOG_FILE_NAME = "echolink_debug.log"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private var enabled = false
    private var logFile: File? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        logFile = File(context.filesDir, LOG_FILE_NAME)
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        if (value) {
            d("DebugLogger", "调试日志已启用")
        } else {
            d("DebugLogger", "调试日志已关闭")
        }
    }

    fun d(tag: String, message: String) {
        if (!enabled) return
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] D/$tag: $message"
        android.util.Log.d(tag, message)
        appendToFile(line)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] E/$tag: $message" + (throwable?.let { " - ${it.message}" } ?: "")
        android.util.Log.e(tag, message, throwable)
        appendToFile(line)
    }

    fun i(tag: String, message: String) {
        if (!enabled) return
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] I/$tag: $message"
        android.util.Log.i(tag, message)
        appendToFile(line)
    }

    private fun appendToFile(line: String) {
        try {
            logFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.write(line + "\n")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DebugLogger", "写入日志失败", e)
        }
    }

    fun getLogFile(): File? = logFile

    fun getLogContent(): String {
        return try {
            logFile?.readText() ?: "日志文件不存在"
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    fun clearLog() {
        try {
            logFile?.delete()
            logFile?.createNewFile()
        } catch (e: Exception) {
            android.util.Log.e("DebugLogger", "清空日志失败", e)
        }
    }
}
