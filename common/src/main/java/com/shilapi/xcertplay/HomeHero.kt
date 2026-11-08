package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * The large art Home's widgets show in landscape: the Now Playing cover above the song and, in one row, the
 * CarPlay icon above the connection status. Each is a square that grows into the height its widget has spare,
 * so a widget as tall as its row has no empty band in it. Portrait keeps the cover beside the song.
 */
internal object HomeHero {
    /** Never smaller than the cover beside the song on a full-size screen. */
    const val COVER_MIN_DP = 92
    const val COMPACT_COVER_MIN_DP = 72
    /** Past this a cover on a tall screen only pushes the controls away from the song. */
    const val COVER_MAX_DP = 280
    /** The CarPlay icon asks for little, so the card never sets the row's height, and grows up to the maximum. */
    const val ICON_MIN_DP = 56
    const val ICON_MAX_DP = 160
    /** From the Now Playing caption to the cover. */
    const val HEADER_GAP_DP = 16
    const val COMPACT_HEADER_GAP_DP = 12
    /** From the art down to the text under it. */
    const val TEXT_GAP_DP = 12
    const val COMPACT_TEXT_GAP_DP = 8
    /** A stacked cover's corner follows its size, so a large cover is no rounder than the widget around it. */
    const val CORNER_SHARE = .08f
    /** The share of the window's height the cover asks for when its widget sets its own height. */
    private const val HEIGHT_SHARE = .32f

    /**
     * The cover's height before any space is shared out. A widget stretched to its row asks for the
     * minimum, so the widgets beside it set the row's height and the cover grows into what they leave.
     */
    fun coverPreferredDp(screenHeightDp: Int, compact: Boolean, fillsRow: Boolean): Int {
        val min = if (compact) COMPACT_COVER_MIN_DP else COVER_MIN_DP
        return if (fillsRow) min else (screenHeightDp * HEIGHT_SHARE).roundToInt().coerceIn(min, COVER_MAX_DP)
    }

    /**
     * The side of the square: given an exact height it fills it, up to its width and [max]; asked how tall
     * it wants to be it answers [preferred]. With the weight of a column it takes the column's spare height.
     */
    fun side(widthMeasureSpec: Int, heightMeasureSpec: Int, preferred: Int, max: Int): Int {
        val width = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) preferred
            else View.MeasureSpec.getSize(widthMeasureSpec)
        val height = when (View.MeasureSpec.getMode(heightMeasureSpec)) {
            View.MeasureSpec.EXACTLY -> View.MeasureSpec.getSize(heightMeasureSpec)
            View.MeasureSpec.AT_MOST -> minOf(preferred, View.MeasureSpec.getSize(heightMeasureSpec))
            else -> preferred
        }
        return minOf(width, height, max).coerceAtLeast(0)
    }
}

/** Home's Now Playing cover; see [HomeHero.side]. Without a preferred size it takes the size it is given. */
internal class HomeCoverView(context: Context) : ImageView(context) {
    var preferredSize = 0
    var maxSize = Int.MAX_VALUE
    /** The corner radius for a cover of the given side. */
    var corner: (Int) -> Float = { 0f }
    private val clip = Path()

    init {
        outlineProvider = roundOutline { corner(it.width) }
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = HomeHero.side(widthMeasureSpec, heightMeasureSpec, preferredSize, maxSize)
        setMeasuredDimension(side, side)
    }

    override fun draw(canvas: Canvas) = clipRoundOnSoftware(canvas, clip, this, corner(width)) { super.draw(canvas) }
}

/** A widget's rounded surface, whose corners also clip what is drawn inside it, such as a cover's colours. */
internal class HomeRoundedFrame(context: Context, private val radius: Float) : FrameLayout(context) {
    private val clip = Path()

    init {
        outlineProvider = roundOutline { radius }
        clipToOutline = true
    }

    override fun dispatchDraw(canvas: Canvas) = clipRoundOnSoftware(canvas, clip, this, radius) { super.dispatchDraw(canvas) }
}

/**
 * A column of widgets in one row whose last shown widget takes the height the others leave, so its bottom lines
 * up with the row's. When Navigation hides between routes, the widget above it fills the column instead.
 */
internal class HomeFillLastColumn(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val last = (childCount - 1 downTo 0).map(::getChildAt).firstOrNull { it.visibility != GONE }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            (child.layoutParams as LayoutParams).apply {
                height = if (child === last) 0 else LayoutParams.WRAP_CONTENT
                weight = if (child === last) 1f else 0f
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

private fun roundOutline(radius: (View) -> Float) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius(view))
}

/**
 * The outline clips a view's corners on a hardware canvas only. A software canvas, as a bitmap screenshot
 * draws into, ignores it, so there the same corners are clipped with a path.
 */
private inline fun clipRoundOnSoftware(canvas: Canvas, path: Path, view: View, radius: Float, draw: () -> Unit) {
    if (canvas.isHardwareAccelerated || radius <= 0f) return draw()
    val checkpoint = canvas.save()
    path.rewind()
    path.addRoundRect(0f, 0f, view.width.toFloat(), view.height.toFloat(), radius, radius, Path.Direction.CW)
    canvas.clipPath(path)
    draw()
    canvas.restoreToCount(checkpoint)
}

/** Holds [content] as a square sized like [HomeCoverView]. */
internal class HomeHeroFrame(
    context: Context,
    content: View,
    private val preferredSize: Int,
    private val maxSize: Int,
) : FrameLayout(context) {
    init {
        addView(content)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = HomeHero.side(widthMeasureSpec, heightMeasureSpec, preferredSize, maxSize)
        val exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        getChildAt(0).measure(exact, exact)
        setMeasuredDimension(side, side)
    }
}
