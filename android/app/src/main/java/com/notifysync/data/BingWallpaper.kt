package com.notifysync.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
 * （该地址 302 重定向到当天 Bing UHD 壁纸直链），按日期缓存到 cacheDir，
 * 高斯模糊 ~70% + 黑色遮罩后作为消息列表页背景。
 */
object BingWallpaper {
    private const val WALLPAPER_URL = "https://dailybing.com/api/v1/today/zh-cn/UHD"
    private const val MASK_ALPHA = 0x73 // 45% 黑色遮罩，保证前景文字可读

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 返回已模糊+遮罩的背景位图；下载/解码失败返回 null（调用方保持原背景） */
    suspend fun load(ctx: Context): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            val dir = File(ctx.cacheDir, "bing_wallpaper").apply { mkdirs() }
            val file = File(dir, "$date.jpg")

            // 当天已缓存且非空 → 直接用；否则下载（跨天自动拉新图）
            if (!file.exists() || file.length() < 10_000) {
                download(file)
            }
            if (!file.exists()) return@withContext null

            val screen = decodeSampled(file, targetMaxDim(ctx))
                ?: return@withContext null
            blurAndMask(screen)
        } catch (_: Exception) {
            null
        }
    }

    private fun download(file: File) {
        try {
            val req = Request.Builder().url(WALLPAPER_URL).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: return
                    if (bytes.size > 10_000) {
                        file.outputStream().use { it.write(bytes) }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** 采样解码：目标最长边 ≈ 屏幕最长边 × 1.5，控制内存又保证模糊后清晰度 */
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
        return (maxOf(dm.widthPixels, dm.heightPixels) * 1.5f).toInt()
    }

    /** 高斯模糊 ~70% + 黑色遮罩 */
    private fun blurAndMask(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        // 1) 缩小到 1/4 做多次 box blur（等效高斯，半径大、强度 ~70%）
        val smallW = (w / 4f).toInt().coerceAtLeast(1)
        val smallH = (h / 4f).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val blurredSmall = boxBlur(small, 3, 3)
        // 2) 放大回原尺寸（模糊更均匀）
        val blurred = Bitmap.createScaledBitmap(blurredSmall, w, h, true)
        // 3) 叠加黑色遮罩
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(blurred, 0f, 0f, null)
        canvas.drawColor(MASK_ALPHA shl 24)
        return output
    }

    /** 滑动窗口 box blur（3 遍 ≈ 高斯模糊观感） */
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
        // 水平
        horizontal(pix, out, w, h, radius)
        // 垂直（out -> pix 复用）
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
