package com.echolink.util

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 媒体缓存管理器：
 * - 图片/视频/语音消息下载到本地缓存，二次打开不再走网络
 * - 新消息到达后后台预加载
 * - 缓存总量超过 1GB 时自动清理最旧的文件（LRU）
 */
object MediaCacheManager {

    private const val MAX_CACHE_BYTES = 1L * 1024 * 1024 * 1024 // 1GB
    private const val CACHE_DIR = "media_cache"

    private var cacheDir: File? = null
    private val downloading = ConcurrentHashMap<String, Boolean>()
    private val totalSize = AtomicLong(0)

    fun init(ctx: Context) {
        val dir = File(ctx.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        cacheDir = dir
        // 启动时计算已有缓存大小
        var size = 0L
        dir.listFiles()?.forEach { size += it.length() }
        totalSize.set(size)
    }

    /** 根据 URL 生成本地缓存文件名 */
    private fun cacheFileFor(url: String): File? {
        val dir = cacheDir ?: return null
        val name = url.hashCode().toString() + "_" + url.substringAfterLast('/').substringBefore('?')
        return File(dir, name)
    }

    /** 检查 URL 是否已有本地缓存 */
    fun getCachedFile(url: String): File? {
        val f = cacheFileFor(url) ?: return null
        return if (f.exists() && f.length() > 0) f else null
    }

    /** 后台预加载：如果没有缓存则下载，有缓存则跳过。线程安全，同一 URL 不会重复下载。 */
    fun preload(url: String?) {
        if (url.isNullOrBlank()) return
        if (url.startsWith("p2p:")) return // P2P 文件已有本地机制
        if (getCachedFile(url) != null) return
        if (downloading.putIfAbsent(url, true) != null) return

        Thread {
            try {
                download(url)
            } catch (_: Exception) {
            } finally {
                downloading.remove(url)
            }
        }.start()
    }

    /** 同步下载并返回本地文件 */
    fun downloadAndGet(url: String): File? {
        if (url.startsWith("p2p:")) return null
        getCachedFile(url)?.let { return it }
        return try {
            download(url)
            getCachedFile(url)
        } catch (_: Exception) {
            null
        }
    }

    private fun download(url: String) {
        val target = cacheFileFor(url) ?: return
        val tmp = File(target.absolutePath + ".tmp")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode != 200) {
            conn.disconnect()
            return
        }
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                input.copyTo(output, 8192)
            }
        }
        conn.disconnect()

        if (tmp.exists() && tmp.length() > 0) {
            tmp.renameTo(target)
            totalSize.addAndGet(target.length())
            cleanupIfNeeded()
        }
    }

    /** 超过 1GB 时清理最旧的文件，直到降到 800MB 以下 */
    private fun cleanupIfNeeded() {
        if (totalSize.get() <= MAX_CACHE_BYTES) return
        val dir = cacheDir ?: return
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() } ?: return
        var freed = 0L
        val target = MAX_CACHE_BYTES * 8 / 10 // 降到 800MB
        for (f in files) {
            if (totalSize.get() - freed <= target) break
            val sz = f.length()
            if (f.delete()) {
                freed += sz
                totalSize.addAndGet(-sz)
            }
        }
    }

    /** 手动清空全部缓存 */
    fun clearAll() {
        cacheDir?.listFiles()?.forEach { it.delete() }
        totalSize.set(0)
    }

    fun cacheSizeBytes(): Long = totalSize.get()
}
