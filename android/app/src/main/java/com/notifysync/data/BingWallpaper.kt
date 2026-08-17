package com.notifysync.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Bing 每日壁纸背景：每天拉取 https://dailybing.com/api/v1/today/zh-cn/UHD
 * （该地址 302 重定向到当天 Bing UHD 壁纸直链，由手机网络直连，不经过服务器）。
 *
 * 处理要点（对应用户要求）：
 *  - 手机网络直连 Bing，不经过服务器；
 *  - 按日期缓存到 cacheDir，每天自动刷新一次（跨天拉新图）；刷新失败则保留当前/昨天的图；
 *  - 近 100% 高斯模糊 + 浅色遮罩（保证深色文字可读），再 centerCrop 覆盖全屏、不拉伸挤压；
 *  - 整页（含顶栏）作为统一背景使用。
 */
object BingWallpaper {
    private const val WALLPAPER_URL = "https://dailybing.com/api/v1/today/zh-cn/UHD"
    // 浅色遮罩：让背景整体偏亮，配合深色文字（on_wallpaper）可读性最好，同时仍透出壁纸色调（~60% 白）
    private val MASK = Color.argb(0x99, 0xFF, 0xFF, 0xFF)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // 当日已算好的位图缓存，避免每次 onResume 重新解码+模糊
    @Volatile private var cachedDate: String? = null
    @Volatile private var cachedBmp: Bitmap? = null

    /** 返回已模糊+遮罩+覆盖裁剪的背景位图；下载/解码失败返回 null（调用方保持原背景） */
    suspend fun load(ctx: Context): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            if (date == cachedDate && cachedBmp != null && !cachedBmp!!.isRecycled) {
                return@withContext cachedBmp
            }

            val dir = File(ctx.cacheDir, "bing_wallpaper").apply { mkdirs() }
            val file = File(dir, "$date.jpg")

            // 当天未缓存 → 尝试下载；下载失败则回退到目录里最新的旧图（保留当前背景）
            if (!file.exists() || file.length() < 10_000) {
                if (!download(file)) {
                    findFallback(dir)?.let { file2 -> if (file2.exists()) file2.copyTo(file, overwrite = true) }
                }
            }
            if (!file.exists() || file.length() < 10_000) return@withContext null

            val screen = decodeSampled(file, targetMaxDim(ctx)) ?: return@withContext null
            val blurred = blurAndMask(screen)
            val cover = cover(blurred, ctx)
            cachedDate = date
            cachedBmp = cover
            cover
        } catch (_: Exception) {
            null
        }
    }

    /** 目录中最近一次成功缓存的壁纸（用于刷新失败时的兜底） */
    private fun findFallback(dir: File): File? {
        return dir.listFiles { f -> f.name.endsWith(".jpg") && f.length() >= 10_000 }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun download(file: File): Boolean {
        return try {
            val req = Request.Builder().url(WALLPAPER_URL).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: return false
                    if (bytes.size > 10_000) {
                        file.outputStream().use { it.write(bytes) }
                        true
                    } else false
                } else false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 采样解码：目标最长边 ≈ 屏幕最长边，控制内存又保证模糊后清晰度 */
    private fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, real)
    }

    private fun targetMaxDim(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        return (maxOf(dm.widthPixels, dm.heightPixels) * 1.0f).toInt()
    }

    /** centerCrop：把源图缩放覆盖到屏幕尺寸并居中裁剪，绝不拉伸变形 */
    private fun cover(src: Bitmap, ctx: Context): Bitmap {
        val dm = ctx.resources.displayMetrics
        val tw = dm.widthPixels
        val th = dm.heightPixels
        val scale = maxOf(tw.toFloat() / src.width, th.toFloat() / src.height)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val x = ((w - tw) / 2f).coerceAtLeast(0f).toInt()
        val y = ((h - th) / 2f).coerceAtLeast(0f).toInt()
        val out = Bitmap.createBitmap(scaled, x, y, tw, th)
        if (scaled != src) scaled.recycle()
        return out
    }

    /** 近 100% 高斯模糊（多遍小半径 box blur 等效）+ 浅色遮罩 */
    private fun blurAndMask(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        // 缩小到 1/8 做 blur，半径更大 → 模糊更强（观感接近「完全模糊」）
        val smallW = (w / 8f).toInt().coerceAtLeast(1)
        val smallH = (h / 8f).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val blurredSmall = boxBlur(small, 4, 4)
        // 放大回原尺寸（模糊更均匀、无硬边）
        val blurred = Bitmap.createScaledBitmap(blurredSmall, w, h, true)
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(blurred, 0f, 0f, null)
        canvas.drawColor(MASK)
        return output
    }

    /** 滑动窗口 box blur（多遍 ≈ 高斯模糊观感） */
    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        var pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        repeat(passes) {
            pix = blurPass(pix, w, h, radius)
        }
        return Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun blurPass(pix: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val out = IntArray(pix.size)
        horizontal(pix, out, w, h, radius)
        horizontal(out, pix, h, w, radius)
        return pix
    }

    private fun horizontal(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val div = radius * 2 + 1
        for (y in 0 until height) {
            val rowStart = y * width
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -radius..radius) {
                val idx = rowStart + i.coerceIn(0, width - 1)
                val c = input[idx]
                sumR += (c shr 16) and 0xFF
                sumG += (c shr 8) and 0xFF
                sumB += c and 0xFF
            }
            for (x in 0 until width) {
                val idx = rowStart + x
                output[idx] = (0xFF shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                val addIdx = rowStart + (x + radius + 1).coerceAtMost(width - 1)
                val rmIdx = rowStart + (x - radius).coerceAtLeast(0)
                val a = input[addIdx]
                val r = input[rmIdx]
                sumR += ((a shr 16) and 0xFF) - ((r shr 16) and 0xFF)
                sumG += ((a shr 8) and 0xFF) - ((r shr 8) and 0xFF)
                sumB += (a and 0xFF) - (r and 0xFF)
            }
        }
    }
}
