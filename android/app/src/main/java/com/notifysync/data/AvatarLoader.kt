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
import java.net.HttpURLConnection
import java.net.URL

/**
 * 共享头像加载器：内存缓存 + 异步下载 + 圆形裁剪。
 * 用法：AvatarLoader.load(ApiClient.fullAvatarUrl(path), imageView)
 * 无 URL / 加载失败时自动回退到默认头像占位图。
 */
object AvatarLoader {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 内存缓存：最多 128 张圆头像（约 44dp，每张 20KB 上下）
    private val cache = object : LruCache<String, Bitmap>(128) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val loading = mutableSetOf<String>()

    fun load(url: String?, iv: ImageView) {
        iv.setImageResource(R.drawable.ic_default_avatar)
        if (url.isNullOrBlank()) return

        cache.get(url)?.let {
            iv.setImageBitmap(it)
            return
        }
        if (!loading.add(url)) return // 同一 URL 只发一次请求

        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { download(url) }
                if (bmp != null) {
                    val circle = cropCircle(bmp)
                    cache.put(url, circle)
                    iv.setImageBitmap(circle)
                }
            } catch (_: Exception) {
            } finally {
                loading.remove(url)
            }
        }
    }

    private fun download(urlStr: String): Bitmap? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.connect()
            BitmapFactory.decodeStream(conn.inputStream)
        } catch (e: Exception) {
            null
        }
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
