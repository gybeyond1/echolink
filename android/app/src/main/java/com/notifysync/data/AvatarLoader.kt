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
import java.security.MessageDigest

/**
 * 共享头像加载器：内存缓存 + 磁盘缓存 + 异步下载 + 圆形裁剪。
 *
 * 关键点（解决「切换页面头像变白/重新加载」）：
 *  1. 内存缓存（进程内，同会话切换瞬时命中，不重拉）；
 *  2. 磁盘缓存（cacheDir/avatars/<md5>.png，进程被杀/冷启动也能瞬时命中，不重拉）；
 *  3. 只有当内存和磁盘都没有时，才显示默认占位图并发起网络请求——
 *     因此视图重绑（切页/重进聊天）时若本地已有缓存，不会闪白、也不会重新下载。
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
        val md5 = md5(url)
        return File(dir, "$md5.png")
    }

    fun load(url: String?, iv: ImageView) {
        if (url.isNullOrBlank()) {
            iv.setImageResource(R.drawable.ic_default_avatar)
            return
        }

        // 1) 内存命中：瞬时设置，绝不闪白
        cache.get(url)?.let {
            iv.setImageBitmap(it)
            return
        }

        // 2) 磁盘命中：后台解码后设置（不显示占位，避免闪白）
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

        // 3) 全 miss：显示占位 → 下载 → 存磁盘 + 内存
        iv.setImageResource(R.drawable.ic_default_avatar)
        if (!loading.add(url)) return

        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { download(url) }
                if (bmp != null) {
                    val circle = cropCircle(bmp)
                    cache.put(url, circle)
                    saveDisk(url, circle)
                    iv.setImageBitmap(circle)
                }
            } catch (_: Exception) {
            } finally {
                loading.remove(url)
            }
        }
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

    private fun download(urlStr: String): Bitmap? {
        return try {
            val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.connect()
            BitmapFactory.decodeStream(conn.inputStream)
        } catch (e: Exception) {
            null
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
