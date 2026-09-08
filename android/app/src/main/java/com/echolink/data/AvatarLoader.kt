package com.echolink.data

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
import com.echolink.R
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
 * 支持多个 ImageView 同时请求同一 URL（下载完成后统一更新所有等待者）。
 */
object AvatarLoader {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val cache = object : LruCache<String, Bitmap>(128) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    // 正在下载的 URL -> 等待的 ImageView 列表（解决同一 URL 被多个 ImageView 同时请求时第二个被丢弃的问题）
    private val waiting = mutableMapOf<String, MutableList<ImageView>>()

    private fun diskDir(): File? = try {
        val ctx = com.echolink.App.appContext
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

    fun load(url: String?, iv: ImageView, forceRefresh: Boolean) {
        if (url.isNullOrBlank()) {
            iv.setImageResource(R.drawable.ic_default_avatar)
            return
        }

        // 1) 内存命中
        if (!forceRefresh) {
            cache.get(url)?.let {
                iv.setImageBitmap(it)
                return
            }
        }

        // 2) 磁盘命中
        if (!forceRefresh) {
            val df = diskFile(url)
            if (df != null && df.exists() && df.length() > 100) {
                if (waiting.containsKey(url)) {
                    // 已在下载/解码中，加入等待列表
                    waiting.getOrPut(url) { mutableListOf() }.add(iv)
                    return
                }
                waiting.getOrPut(url) { mutableListOf() }.add(iv)
                scope.launch {
                    try {
                        val bmp = withContext(Dispatchers.IO) { decodeCircle(df) }
                        if (bmp != null) {
                            cache.put(url, bmp)
                            dispatch(url, bmp)
                        } else {
                            dispatchDefault(url)
                        }
                    } catch (_: Exception) {
                        dispatchDefault(url)
                    }
                }
                return
            }
        }

        // 3) 全 miss：显示占位 → 下载
        iv.setImageResource(R.drawable.ic_default_avatar)
        if (waiting.containsKey(url)) {
            waiting.getOrPut(url) { mutableListOf() }.add(iv)
            return
        }
        waiting.getOrPut(url) { mutableListOf() }.add(iv)

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
                        dispatch(url, circle)
                    }
                    is DownloadResult.NotModified -> {
                        val df = diskFile(url)
                        if (df != null && df.exists() && df.length() > 100) {
                            val bmp = withContext(Dispatchers.IO) { decodeCircle(df) }
                            if (bmp != null) {
                                cache.put(url, bmp)
                                dispatch(url, bmp)
                            } else {
                                val bmp2 = withContext(Dispatchers.IO) { download(url, null, null) }
                                if (bmp2 is DownloadResult.Ok) {
                                    val circle = cropCircle(bmp2.bmp)
                                    cache.put(url, circle)
                                    saveDisk(url, circle)
                                    dispatch(url, circle)
                                } else {
                                    dispatchDefault(url)
                                }
                            }
                        } else {
                            dispatchDefault(url)
                        }
                    }
                    else -> dispatchDefault(url)
                }
            } catch (_: Exception) {
                dispatchDefault(url)
            }
        }
    }

    /** 下载完成后更新所有等待的 ImageView */
    private fun dispatch(url: String, bmp: Bitmap) {
        waiting.remove(url)?.forEach { iv ->
            iv.setImageBitmap(bmp)
        }
    }

    private fun dispatchDefault(url: String) {
        waiting.remove(url)?.forEach { iv ->
            iv.setImageResource(R.drawable.ic_default_avatar)
        }
    }

    fun invalidate(url: String?) {
        if (url.isNullOrBlank()) return
        cache.remove(url)
        try { diskFile(url)?.delete() } catch (_: Exception) {}
        try { etagFile(url)?.delete() } catch (_: Exception) {}
        try { lmFile(url)?.delete() } catch (_: Exception) {}
    }

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
