package com.echolink.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 服务器地址智能选择：
 * - 连 WiFi 且配置了内网地址 → 先测内网连通性，通就走内网，不通自动切公网
 * - 没连 WiFi 或没配内网地址 → 直接走公网
 * 公网地址存在 AuthManager.serverUrl，内网地址存在 AuthManager.lanServerUrl。
 * 选择结果写回 AuthManager.serverUrl（ApiClient / WebSocket 都读这个字段）。
 */
object ServerSelector {

    /** 内网连通性测试超时（毫秒），要短，避免切网时卡住 */
    private const val LAN_TIMEOUT_MS = 2500

    /** 当前是否在用内网地址（仅用于日志/调试展示） */
    var usingLan: Boolean = false
        private set

    /**
     * 根据当前网络状态选择最优服务器地址并写回 AuthManager.serverUrl。
     * @return Pair(是否切换了地址, 选中的地址)
     */
    suspend fun selectOptimal(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val wan = AuthManager.serverUrl
        val lan = AuthManager.lanServerUrl
        val onWifi = isOnWifi(context)

        val target = when {
            // 没配内网 → 只能用公网
            lan.isNullOrBlank() -> {
                usingLan = false
                wan
            }
            // 没连 WiFi → 用公网
            !onWifi -> {
                usingLan = false
                wan
            }
            // 连 WiFi 且配了内网 → 先测内网
            else -> {
                val reachable = testReachable(lan)
                usingLan = reachable
                if (reachable) lan else wan
            }
        }

        val changed = AuthManager.serverUrl != target
        if (changed) AuthManager.serverUrl = target
        changed to target
    }

    /** 是否连 WiFi（包括 5GHz / 2.4GHz，不包括移动热点共享出去的） */
    private fun isOnWifi(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) {
            false
        }
    }

    /** 测一个地址是否可达：HEAD 请求 /api/health 或根路径，短超时 */
    private fun testReachable(baseUrl: String): Boolean {
        return try {
            val url = URL("${baseUrl.trimEnd('/')}/api/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = LAN_TIMEOUT_MS
                readTimeout = LAN_TIMEOUT_MS
                instanceFollowRedirects = false
            }
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            // 200/404/405 都算"服务器可达"（只是接口可能不存在）
            code in 200..599
        } catch (_: Exception) {
            false
        }
    }
}
