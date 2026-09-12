package com.knowyourcase.app.ui.scan

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator

class ScannerOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C8932A")
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        alpha = 180
    }

    private val dp = resources.displayMetrics.density
    private val cornerLen = 36 * dp
    private val cornerRadius = 12 * dp

    // Animated scan line
    private var scanLineY = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            scanLineY = it.animatedFraction
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Box dimensions — 70% of screen width, centered vertically at 42%
        val boxSize = w * 0.72f
        val left   = (w - boxSize) / 2f
        val top    = h * 0.22f
        val right  = left + boxSize
        val bottom = top + boxSize

        // Draw corner brackets
        cornerPaint.alpha = 255

        // Top-left
        canvas.drawLine(left, top + cornerLen, left, top + cornerRadius, cornerPaint)
        canvas.drawLine(left + cornerRadius, top, left + cornerLen, top, cornerPaint)

        // Top-right
        canvas.drawLine(right - cornerLen, top, right - cornerRadius, top, cornerPaint)
        canvas.drawLine(right, top + cornerRadius, right, top + cornerLen, cornerPaint)

        // Bottom-left
        canvas.drawLine(left, bottom - cornerLen, left, bottom - cornerRadius, cornerPaint)
        canvas.drawLine(left + cornerRadius, bottom, left + cornerLen, bottom, cornerPaint)

        // Bottom-right
        canvas.drawLine(right - cornerLen, bottom, right - cornerRadius, bottom, cornerPaint)
        canvas.drawLine(right, bottom - cornerLen, right, bottom - cornerRadius, cornerPaint)

        // Animated scan line inside box
        val lineY = top + (bottom - top) * scanLineY
        val gradient = LinearGradient(
            left, lineY, right, lineY,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#C8932A"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        linePaint.shader = gradient
        canvas.drawLine(left + 8 * dp, lineY, right - 8 * dp, lineY, linePaint)
    }
}
