package com.notifysync.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import android.widget.ImageView
import com.notifysync.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 共享头像加载器：内存缓存 + 磁盘缓存 + 异步下载 + 圆形裁剪。
 *
 * 关键点（解决「切换页面头像变白/重新加载」+「换了头像不更新」）：
 *  1. 内存缓存（进程内，同会话切换瞬时命中，不重拉）；
 *  2. 磁盘缓存（cacheDir/avatars/<md5>.png，进程被杀/冷启动也能瞬时命中，不重拉）；
 *  3. 只有当内存和磁盘都没有时，才显示默认占位图并发起网络请求；
 *  4. 条件 GET（If-None-Match / If-Modified-Since）：服务端头像变化（哪怕同一 URL）
 *     也能被探知——304 复用缓存、200 更新缓存，绝不会长期展示过期头像；
 *  5. invalidate(url) / refresh(url, iv)：换头像后主动让旧缓存失效并立即拉新图。
 *
 * 注意：头像只在 cacheDir 磁盘缓存，绝不会写进 APK。换头像后服务端文件名带时间戳，
 * URL 会变，新 URL 自然命中新缓存；本类的条件 GET 与 invalidate 进一步兜底同 URL 的更新。
 *
 * 用法：AvatarLoader.load(ApiClient.fullAvatarUrl(path), imageView)
 */
object AvatarLoader {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 内存缓存：最多 128 张圆头像（约 44dp，每张 20KB 上下）
    private val cache = object : LruCache<String, Bitmap>(128) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val loading = mutableSetOf<String>()

    /** 磁盘缓存根目录 */
    private fun diskDir(): File? = try {
        val ctx = com.notifysync.App.appContext
        File(ctx.cacheDir, "avatars").apply { mkdirs() }
    } catch (_: Exception) { null }

    private fun diskFile(url: String): File? {
        val dir = diskDir() ?: return null
        return File(dir, "${md5(url)}.png")
    }

    private fun etagFile(url: String): File? {
        val dir = diskDir() ?: return null
        return File(dir, "${md5(url)}.etag")
    }

    private fun lmFile(url: String): File? {
        val dir = diskDir() ?: return null
        return File(dir, "${md5(url)}.lm")
    }

    fun load(url: String?, iv: ImageView) {
        load(url, iv, false)
    }

    /**
     * @param forceRefresh true 时忽略内存/磁盘缓存，强制重新下载（用于换头像后立即生效）
     */
    fun load(url: String?, iv: ImageView, forceRefresh: Boolean) {
        if (url.isNullOrBlank()) {
            iv.setImageResource(R.drawable.ic_default_avatar)
            return
        }

        // 1) 内存命中：瞬时设置，绝不闪白（除非强制刷新）
        if (!forceRefresh) {
            cache.get(url)?.let {
                iv.setImageBitmap(it)
                return
            }
        }

        // 2) 磁盘命中：后台解码后设置（不显示占位，避免闪白）
        if (!forceRefresh) {
            val df = diskFile(url)
            if (df != null && df.exists() && df.length() > 100) {
                if (!loading.add(url)) return
                scope.launch {
                    try {
                        val bmp = withContext(Dispatchers.IO) { decodeCircle(df) }
                        if (bmp != null) {
                            cache.put(url, bmp)
                            iv.setImageBitmap(bmp)
                        } else {
                            iv.setImageResource(R.drawable.ic_default_avatar)
                        }
                    } catch (_: Exception) {
                        iv.setImageResource(R.drawable.ic_default_avatar)
                    } finally {
                        loading.remove(url)
                    }
                }
                return
            }
        }

        // 3) 全 miss（或强制刷新）：显示占位 → 条件 GET 下载 → 存磁盘 + 内存
        iv.setImageResource(R.drawable.ic_default_avatar)
        if (!loading.add(url)) return

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val etag = if (forceRefresh) null else readSidecar(etagFile(url))
                    val lm = if (forceRefresh) null else readSidecar(lmFile(url))
                    download(url, etag, lm)
                }
                when (result) {
                    is DownloadResult.Ok -> {
                        val circle = cropCircle(result.bmp)
                        cache.put(url, circle)
                        saveDisk(url, circle)
                        writeSidecar(etagFile(url), result.etag)
                        writeSidecar(lmFile(url), result.lm)
                        iv.setImageBitmap(circle)
                    }
                    is DownloadResult.NotModified -> {
                        // 304：服务端头像没变，沿用磁盘缓存
                        val df = diskFile(url)
                        if (df != null && df.exists() && df.length() > 100) {
                            val bmp = withContext(Dispatchers.IO) { decodeCircle(df) }
                            if (bmp != null) {
                                cache.put(url, bmp)
                                iv.setImageBitmap(bmp)
                            } else {
                                iv.setImageResource(R.drawable.ic_default_avatar)
                            }
                        } else {
                            // 磁盘缓存意外丢失：降级为普通下载
                            val bmp = withContext(Dispatchers.IO) { download(url, null, null) }
                            if (bmp is DownloadResult.Ok) {
                                val circle = cropCircle(bmp.bmp)
                                cache.put(url, circle)
                                saveDisk(url, circle)
                                iv.setImageBitmap(circle)
                            } else {
                                iv.setImageResource(R.drawable.ic_default_avatar)
                            }
                        }
                    }
                    else -> iv.setImageResource(R.drawable.ic_default_avatar)
                }
            } catch (_: Exception) {
                iv.setImageResource(R.drawable.ic_default_avatar)
            } finally {
                loading.remove(url)
            }
        }
    }

    /** 主动让某个 URL 的缓存失效（换头像后调用，避免旧图残留） */
    fun invalidate(url: String?) {
        if (url.isNullOrBlank()) return
        cache.remove(url)
        try { diskFile(url)?.delete() } catch (_: Exception) {}
        try { etagFile(url)?.delete() } catch (_: Exception) {}
        try { lmFile(url)?.delete() } catch (_: Exception) {}
    }

    /** 失效并立即重新下载显示（换头像后立即生效的最直接入口） */
    fun refresh(url: String?, iv: ImageView) {
        invalidate(url)
        load(url, iv, true)
    }

    private fun readSidecar(f: File?): String? {
        if (f == null || !f.exists()) return null
        return try { f.readText().trim().ifEmpty { null } } catch (_: Exception) { null }
    }

    private fun writeSidecar(f: File?, value: String?) {
        if (f == null) return
        try {
            if (value.isNullOrBlank()) f.delete() else f.writeText(value)
        } catch (_: Exception) {}
    }

    private fun decodeCircle(file: File): Bitmap? {
        val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return cropCircle(raw)
    }

    private fun saveDisk(url: String, bmp: Bitmap) {
        try {
            val df = diskFile(url) ?: return
            df.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (_: Exception) {
        }
    }

    private sealed class DownloadResult {
        class Ok(val bmp: Bitmap, val etag: String?, val lm: String?) : DownloadResult()
        object NotModified : DownloadResult()
        object Error : DownloadResult()
    }

    private fun download(urlStr: String, etag: String?, lm: String?): DownloadResult {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            if (!etag.isNullOrBlank()) conn.setRequestProperty("If-None-Match", etag)
            if (!lm.isNullOrBlank()) conn.setRequestProperty("If-Modified-Since", lm)
            conn.connect()
            when (conn.responseCode) {
                304 -> DownloadResult.NotModified
                200 -> {
                    val bmp = BitmapFactory.decodeStream(conn.inputStream) ?: return DownloadResult.Error
                    val newEtag = conn.getHeaderField("ETag")
                    val newLm = conn.getHeaderField("Last-Modified")
                    DownloadResult.Ok(bmp, newEtag, newLm)
                }
                else -> DownloadResult.Error
            }
        } catch (e: Exception) {
            DownloadResult.Error
        }
    }

    private fun md5(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun cropCircle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val x = (src.width - size) / 2
        val y = (src.height - size) / 2
        val squared = Bitmap.createBitmap(src, x, y, size, size)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(squared, rect, rect, paint)
        if (squared != src) squared.recycle()
        return output
    }
}
