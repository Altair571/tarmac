package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout

/**
 * The Settings rail: its destinations in a column and, behind them, the selection. The selection is one
 * shape that belongs to the rail and not to a row, so when another category opens it slides there instead
 * of vanishing from one row and appearing on the next. A short bar in the tint rides in the gutter beside it.
 */
internal class SettingsRailLayout(context: Context) : LinearLayout(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = RectF()
    private val density = resources.displayMetrics.density
    private var slideFromIndex = -1
    private var progress = 1f
    private var slide: ValueAnimator? = null

    /** The selection's fill, the tint of the bar beside it, and the selection's corner. */
    var selectionColor = 0
        set(value) { field = value; fill.color = value; invalidate() }
    var markerColor = 0
        set(value) { field = value; marker.color = value; invalidate() }
    var selectionRadius = 0f

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    /** Starts the selection at the row at [index], the category that was open, and slides it to the selected row. */
    fun slideFrom(index: Int) {
        slide?.cancel()
        slideFromIndex = index
        if (index < 0 || !motionEnabled()) {
            progress = 1f
            return
        }
        progress = 0f
        slide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.BASE + Motion.QUICK
            interpolator = Motion.move
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val selected = (0 until childCount).map(::getChildAt).firstOrNull { it.isSelected }
        if (selected != null && selected.visibility == View.VISIBLE) {
            val from = getChildAt(slideFromIndex)?.takeIf { progress < 1f } ?: selected
            val top = from.top + (selected.top - from.top) * progress
            shape.set(selected.left.toFloat(), top, selected.right.toFloat(), top + selected.height)
            canvas.drawRoundRect(shape, selectionRadius, selectionRadius, fill)
            // The bar sits in the rail's start padding, centred on the selection.
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val barWidth = MARKER_WIDTH_DP * density
            val barHeight = selected.height * MARKER_HEIGHT_SHARE
            val barLeft = if (rtl) width - (paddingRight + barWidth) / 2f - barWidth / 2f else (paddingLeft - barWidth) / 2f
            val barTop = top + (selected.height - barHeight) / 2f
            shape.set(barLeft, barTop, barLeft + barWidth, barTop + barHeight)
            canvas.drawRoundRect(shape, barWidth / 2f, barWidth / 2f, marker)
        }
        super.dispatchDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        slide?.cancel()
        progress = 1f
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MARKER_WIDTH_DP = 4f
        const val MARKER_HEIGHT_SHARE = .42f
    }
}
