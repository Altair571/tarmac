package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import kotlin.math.max
import kotlin.math.min

/**
 * The connecting screen's picture: a car's cabin from the driver's seat at dusk, or by day, with CarPlay's
 * icon on its head unit. It is a still. The cabin is modelled and lit as a 3D scene and rendered ahead of
 * time by `scripts/cabin/render.sh` into `drawable-nodpi/connecting_cabin_*.jpg`; this view only shows
 * the picture: cropped to a car-like card, its corners rounded, and turned round for right-hand drive.
 */
internal class ConnectingDashboardView(context: Context) : View(context) {
    private val picture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconPatch = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val card = RectF()
    private val placement = Matrix()
    private val iconPlacement = Matrix()
    private val iconOutline = Path()
    private val density = resources.displayMetrics.density
    private var bitmap: Bitmap? = null
    private var loadedNight: Boolean? = null
    private var loadedSample = 0

    /** Follows the app theme, as the rest of the connecting screen does. */
    var night = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Shows the cabin with its wheel on the right. CarPlay's icon on the head unit is not mirrored with it. */
    var rightHandDrive = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        // The card keeps a car-like frame, between a tall 6:5 and a wide 17:10, centred in what is left.
        val cardWidth = min(width.toFloat(), height * MAX_ASPECT)
        val cardHeight = min(height.toFloat(), cardWidth / MIN_ASPECT)
        card.set((width - cardWidth) / 2f, (height - cardHeight) / 2f, (width + cardWidth) / 2f, (height + cardHeight) / 2f)
        val source = load(cardWidth) ?: return

        // The picture fills the card and is cropped evenly; right-hand drive turns it round about the card's middle.
        val scale = max(cardWidth / source.width, cardHeight / source.height)
        placement.setScale(scale, scale)
        placement.postTranslate(card.centerX() - source.width * scale / 2f, card.centerY() - source.height * scale / 2f)
        if (rightHandDrive) placement.postScale(-1f, 1f, card.centerX(), card.centerY())
        picture.shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(placement) }
        val corner = CARD_CORNER_DP * density
        canvas.drawRoundRect(card, corner, corner, picture)

        if (rightHandDrive) {
            // Turning the cabin round turned CarPlay's icon round too. The icon lies flat on the head unit's
            // glass, so one perspective map puts each of its corners back on the corner opposite.
            val quad = FloatArray(8) { ICON_QUAD[it] * if (it % 2 == 0) source.width else source.height }
            val shown = FloatArray(8).also { placement.mapPoints(it, quad) }
            val swapped = floatArrayOf(shown[2], shown[3], shown[0], shown[1], shown[6], shown[7], shown[4], shown[5])
            if (iconPlacement.setPolyToPoly(quad, 0, swapped, 0, 4)) {
                iconPatch.shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(iconPlacement) }
                iconOutline.rewind()
                iconOutline.moveTo(shown[0], shown[1])
                for (point in 1..3) iconOutline.lineTo(shown[point * 2], shown[point * 2 + 1])
                iconOutline.close()
                canvas.drawPath(iconOutline, iconPatch)
            }
        }

        // A hairline finishes the card's edge on any page colour.
        edge.strokeWidth = density
        edge.color = if (night) EDGE_NIGHT else EDGE_DAY
        canvas.drawRoundRect(card.left + density / 2, card.top + density / 2, card.right - density / 2, card.bottom - density / 2,
            corner, corner, edge)
    }

    /** The picture for this theme, read at half or quarter size when the card is small enough not to show it. */
    private fun load(cardWidth: Float): Bitmap? {
        var sample = 1
        while (PICTURE_WIDTH / (sample * 2) >= cardWidth) sample *= 2
        bitmap?.takeIf { loadedNight == night && loadedSample == sample }?.let { return it }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val loaded = runCatching {
            BitmapFactory.decodeResource(resources, if (night) R.drawable.connecting_cabin_night else R.drawable.connecting_cabin_day, options)
        }.getOrNull() ?: return bitmap
        bitmap = loaded
        loadedNight = night
        loadedSample = sample
        return loaded
    }

    private companion object {
        const val MIN_ASPECT = 1.2f
        const val MAX_ASPECT = 1.7f
        const val CARD_CORNER_DP = 28f
        /** The rendered picture's width; `scripts/cabin/render.sh` sets it. */
        const val PICTURE_WIDTH = 1680
        const val EDGE_NIGHT = 0x1FFFFFFF
        const val EDGE_DAY = 0x14000000
        /**
         * CarPlay's icon in the picture: x and y of its top-left, top-right, bottom-right and bottom-left
         * corners, as shares of the picture's width and height. `render_cabin` prints them.
         */
        val ICON_QUAD = floatArrayOf(
            0.53327f, 0.47546f,
            0.59844f, 0.47527f,
            0.59934f, 0.56626f,
            0.53372f, 0.56657f,
        )
    }
}

/**
 * The connecting screen's buttons, all one height: as many side by side as fit at their labels' width,
 * up to three, otherwise one per row.
 */
internal class ConnectingActions(context: Context) : ViewGroup(context) {
    var buttonHeight = 0
    var gap = 0
    var minButtonWidth = 0
    var maxButtonWidth = Int.MAX_VALUE
    /** Centres each row; otherwise rows start at the reading edge. */
    var centred = false
    private var columns = 1
    private var buttonWidth = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val visible = visibleButtons()
        // A row must give every label its full width.
        val needed = visible.maxOfOrNull { child ->
            (child as? TextView)?.let { (it.paint.measureText(it.text.toString()) + it.compoundPaddingLeft + it.compoundPaddingRight).toInt() } ?: 0
        } ?: 0
        val least = max(minButtonWidth, needed)
        columns = (visible.size downTo 1).firstOrNull { n -> n == 1 || least * n + gap * (n - 1) <= available } ?: 1
        buttonWidth = min(maxButtonWidth, (available - gap * (columns - 1)) / columns).coerceAtLeast(0)
        for (child in visible) {
            child.measure(MeasureSpec.makeMeasureSpec(buttonWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(buttonHeight, MeasureSpec.EXACTLY))
        }
        val rows = (visible.size + columns - 1) / columns
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) available
        else min(available, buttonWidth * columns + gap * (columns - 1))
        setMeasuredDimension(width, if (rows == 0) 0 else rows * buttonHeight + (rows - 1) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        visibleButtons().chunked(columns).forEachIndexed { row, items ->
            val rowWidth = items.size * buttonWidth + (items.size - 1) * gap
            val start = if (centred) (width - rowWidth) / 2 else 0
            val top = row * (buttonHeight + gap)
            items.forEachIndexed { index, child ->
                val offset = start + index * (buttonWidth + gap)
                val left = if (rtl) width - offset - buttonWidth else offset
                child.layout(left, top, left + buttonWidth, top + buttonHeight)
            }
        }
    }

    private fun visibleButtons() = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
}

/** Sets one of [AppType]'s faces on a span of text, as `TypefaceSpan(Typeface)` does from Android 9. */
internal class AppTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) {
        paint.typeface = typeface
    }

    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = typeface
    }
}
