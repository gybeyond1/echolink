package com.echolink.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * 可缩放全屏图片控件：
 * - 双指捏合缩放（1x ~ 5x）
 * - 放大后单指拖动
 * - 双击放大 / 再双击还原
 * - 单击回调 onTap（用于关闭查看器）
 * - 缩放状态回调 onScaleStateChanged：放大时通知外部禁用 ViewPager 翻页
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var onTap: (() -> Unit)? = null
    /** true = 已放大（禁止翻页）；false = 原始大小（允许 ViewPager 左右滑） */
    var onScaleStateChanged: ((Boolean) -> Unit)? = null

    private val matrix = Matrix()
    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = currentScale * detector.scaleFactor
                val bounded = next.coerceIn(minScale, maxScale)
                val factor = bounded / currentScale
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                currentScale = bounded
                fixTranslation()
                imageMatrix = matrix
                onScaleStateChanged?.invoke(currentScale > 1.01f)
                return true
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom(e.x, e.y)
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                // 放大状态下禁止父容器（ViewPager2）拦截，用于拖动图片
                parent?.requestDisallowInterceptTouchEvent(currentScale > 1.01f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentScale > 1.01f && scaleDetector.pointerCount <= 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    matrix.postTranslate(dx, dy)
                    fixTranslation()
                    imageMatrix = matrix
                }
                parent?.requestDisallowInterceptTouchEvent(currentScale > 1.01f)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        if (bm != null) {
            post { resetToFit() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawable != null) resetToFit()
    }

    /** 重置到适配居中（1x） */
    fun reset() {
        currentScale = 1f
        if (drawable != null) resetToFit()
        onScaleStateChanged?.invoke(false)
    }

    private fun resetToFit() {
        val d = drawable ?: return
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return
        matrix.reset()
        val scale = minOf(vw / dw, vh / dh)
        matrix.postScale(scale, scale)
        matrix.postTranslate((vw - dw * scale) / 2f, (vh - dh * scale) / 2f)
        imageMatrix = matrix
    }

    private fun toggleZoom(fx: Float, fy: Float) {
        val target = if (currentScale > 1.01f) 1f else 2.5f
        val factor = target / currentScale
        matrix.postScale(factor, factor, fx, fy)
        currentScale = target
        fixTranslation()
        imageMatrix = matrix
        onScaleStateChanged?.invoke(currentScale > 1.01f)
    }

    /** 限制平移不超出边界（等比小于控件时居中，大于时夹紧） */
    private fun fixTranslation() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= vw) {
            dx = (vw - r.width()) / 2f - r.left
        } else {
            if (r.left > 0) dx = -r.left
            else if (r.right < vw) dx = vw - r.right
        }
        if (r.height() <= vh) {
            dy = (vh - r.height()) / 2f - r.top
        } else {
            if (r.top > 0) dy = -r.top
            else if (r.bottom < vh) dy = vh - r.bottom
        }
        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }
}
