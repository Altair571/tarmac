package com.shilapi.xcertplay

import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Android's "Remove animations" switch, and animator duration scale 0, turn DiPlay's motion off too. */
internal fun motionEnabled(): Boolean = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

/**
 * The ambient light behind Home and Settings: two soft glows drifting slowly, tinted by the cover of
 * the song that is playing. Only view properties move, so the render thread animates them without a redraw.
 */
internal class AmbientBackdropView(context: Context, base: Int, private val night: Boolean) : FrameLayout(context) {
    private val glows = List(3) { View(context).apply { background = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT } } }
    private val drift = mutableListOf<Animator>()
    private var colors = IntArray(3) { Color.TRANSPARENT }
    private var tintAnimator: ValueAnimator? = null

    init {
        setBackgroundColor(base)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        glows.forEach { addView(it) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val size = (max(w, h) * 0.9f).toInt()
        glows.forEachIndexed { index, glow ->
            glow.layoutParams = LayoutParams(size, size)
            // Light from the top start, the right edge and below, like the soft colour fields of an iOS wallpaper.
            glow.x = floatArrayOf(.10f, .95f, .45f)[index] * w - size / 2f
            glow.y = floatArrayOf(0f, .30f, 1.05f)[index] * h - size / 2f
            (glow.background as GradientDrawable).gradientRadius = size / 2f
        }
        paint(colors)
        restartDrift()
    }

    /** Fades the three glows to [palette] over a second, or at once without motion. */
    fun tint(palette: IntArray) {
        val target = IntArray(3) { palette[it % palette.size] }
        if (target.contentEquals(colors)) return
        tintAnimator?.cancel()
        if (!motionEnabled() || colors.all { it == Color.TRANSPARENT }) {
            paint(target)
            return
        }
        val from = colors.copyOf()
        val evaluator = ArgbEvaluator()
        tintAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                paint(IntArray(3) { evaluator.evaluate(f, from[it], target[it]) as Int })
            }
            start()
        }
    }

    private fun paint(next: IntArray) {
        colors = next
        // The palette is already toned down, so the glows can be fuller without a cover turning garish.
        val alpha = if (night) 0x8C else 0x70
        glows.forEachIndexed { index, glow ->
            (glow.background as GradientDrawable).colors = intArrayOf(withAlpha(next[index], alpha), withAlpha(next[index], 0))
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) restartDrift() else stopDrift()
    }

    private fun restartDrift() {
        stopDrift()
        if (!motionEnabled() || width == 0 || !isAttachedToWindow) return
        glows.forEachIndexed { index, glow ->
            val reach = width * 0.06f * if (index % 2 == 0) 1f else -1f
            fun loop(property: String, vararg values: Float, millis: Long) =
                ObjectAnimator.ofFloat(glow, property, *values).apply {
                    duration = millis
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            drift += loop("translationX", 0f, reach, millis = 17_000L + index * 4_000L)
            drift += loop("translationY", 0f, reach * 0.6f, millis = 13_000L + index * 3_000L)
            drift += loop("scaleX", 1f, 1.12f, millis = 11_000L + index * 2_000L)
            drift += loop("scaleY", 1f, 1.12f, millis = 11_000L + index * 2_000L)
        }
    }

    private fun stopDrift() {
        drift.forEach(Animator::cancel)
        drift.clear()
    }

    override fun onDetachedFromWindow() {
        stopDrift()
        tintAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}

/** What the CarPlay icon says: waiting, connecting, connected or needing attention. */
internal enum class PulseState { READY, CONNECTING, CONNECTED, ATTENTION }

/**
 * The CarPlay app icon with its state, drawn as iOS draws an app's state: a plain icon while it waits,
 * ripples in the icon's own rounded-square shape while it connects, and a corner badge (a tick, or an
 * exclamation mark) once it is connected or needs attention.
 */
internal class ConnectionPulseView(context: Context, private val icon: Drawable?) : View(context) {
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val bounds = RectF()
    private val tick = Path()
    private val density = resources.displayMetrics.density
    private var phase = 0f
    private var animator: ValueAnimator? = null
    /** The state's colour: the ripples and the badge. */
    var color: Int = Color.WHITE
        set(value) { field = value; invalidate() }
    /** The card behind the icon, which outlines the badge so it reads as cut into the icon's corner. */
    var surface: Int = Color.BLACK
        set(value) { field = value; invalidate() }
    var state: PulseState = PulseState.READY
        set(value) {
            if (field == value) return
            field = value
            restart()
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val iconSize = size * 0.74f
        val left = (width - iconSize) / 2
        val top = (height - iconSize) / 2
        val corner = iconSize * 0.225f
        if (state == PulseState.CONNECTING && motionEnabled()) {
            ripple.strokeWidth = max(1.5f * density, size * 0.018f)
            val reach = (size - iconSize) / 2 - ripple.strokeWidth
            for (wave in 0 until 2) {
                val t = (phase + wave / 2f) % 1f
                val grow = t * reach
                ripple.color = withAlpha(color, ((1f - t) * 170).toInt())
                bounds.set(left - grow, top - grow, left + iconSize + grow, top + iconSize + grow)
                canvas.drawRoundRect(bounds, corner + grow * .5f, corner + grow * .5f, ripple)
            }
        }
        icon?.let {
            it.setBounds(left.toInt(), top.toInt(), (left + iconSize).toInt(), (top + iconSize).toInt())
            it.draw(canvas)
        }
        if (state != PulseState.CONNECTED && state != PulseState.ATTENTION) return
        val radius = iconSize * 0.19f
        val cx = left + iconSize - radius * .45f
        val cy = top + iconSize - radius * .45f
        fill.color = surface
        canvas.drawCircle(cx, cy, radius + max(2f * density, radius * .24f), fill)
        fill.color = color
        canvas.drawCircle(cx, cy, radius, fill)
        glyph.strokeWidth = radius * .3f
        if (state == PulseState.CONNECTED) {
            tick.reset()
            tick.moveTo(cx - radius * .46f, cy + radius * .02f)
            tick.lineTo(cx - radius * .12f, cy + radius * .36f)
            tick.lineTo(cx + radius * .46f, cy - radius * .32f)
            canvas.drawPath(tick, glyph)
        } else {
            canvas.drawLine(cx, cy - radius * .5f, cx, cy + radius * .1f, glyph)
            fill.color = Color.WHITE
            canvas.drawCircle(cx, cy + radius * .46f, glyph.strokeWidth * .55f, fill)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) restart() else stop()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun restart() {
        stop()
        if (state != PulseState.CONNECTING || !motionEnabled() || !isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }
}

/** Four bars that dance while music plays and rest low when it pauses. */
internal class EqualizerView(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var time = 0f
    private var animator: ValueAnimator? = null
    var playing = false
        set(value) {
            if (field == value) return
            field = value
            restart()
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val bars = 4
        val gap = width / (bars * 3f - 1f)
        val barWidth = gap * 2f
        paint.color = color
        for (i in 0 until bars) {
            val level = if (playing && motionEnabled()) {
                0.35f + 0.65f * ((sin(time * (2.1f + i * 0.7f) + i * 1.3f) + 1f) / 2f)
            } else if (playing) 0.7f else 0.22f
            val left = i * (barWidth + gap)
            val top = height * (1f - level)
            canvas.drawRoundRect(left, top, left + barWidth, height.toFloat(), barWidth / 2, barWidth / 2, paint)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) restart() else stop()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun restart() {
        stop()
        if (!playing || !motionEnabled() || !isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, 1_000f).apply {
            duration = 1_000_000
            interpolator = LinearInterpolator()
            addUpdateListener { time = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }
}

/** A gentle press: the control sinks a little under the finger and springs back on release. */
@SuppressLint("ClickableViewAccessibility")
internal fun View.pressFeedback(depth: Float = 0.965f) {
    setOnTouchListener { view, event ->
        if (motionEnabled()) when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.animate().scaleX(depth).scaleY(depth)
                .setDuration(110).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                .setDuration(320).setInterpolator(OvershootInterpolator(2.4f)).start()
        }
        false
    }
}

/** Cards rise into place one after another when a page opens. */
internal fun staggerIn(views: List<View>, rise: Float) {
    if (!motionEnabled()) return
    views.forEachIndexed { index, view ->
        view.alpha = 0f
        view.translationY = rise
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(index * 70L).setDuration(460)
            .setInterpolator(DecelerateInterpolator(2.2f)).start()
    }
}

/**
 * Colours for a background or a card from a song's cover. Each is held to the same saturation and
 * brightness range, so a brown, neon or black cover all give a calm tint, and a grey cover stays neutral.
 */
internal object CoverPalette {
    /** Tarmac's own colours when nothing plays: deep blue, teal and indigo. */
    fun default(night: Boolean): IntArray = intArrayOf(
        tone(Color.rgb(10, 116, 226), night), tone(Color.rgb(48, 176, 199), night), tone(Color.rgb(94, 92, 230), night),
    )

    fun of(cover: Bitmap, night: Boolean): IntArray {
        val sample = Bitmap.createScaledBitmap(cover, 6, 6, true)
        val pixels = IntArray(36)
        try { sample.getPixels(pixels, 0, 6, 0, 0, 6, 6) } finally { if (sample !== cover) sample.recycle() }
        val hsv = pixels.map { FloatArray(3).also { out -> Color.colorToHSV(it, out) } }
        // The most vivid colour, then the most vivid one at least 35 degrees of hue away from it.
        val first = hsv.maxByOrNull { it[1] * it[2] }!!
        val second = hsv.filter { hueDistance(it[0], first[0]) >= 35f }.maxByOrNull { it[1] * it[2] }
            ?: floatArrayOf((first[0] + 30f) % 360f, first[1], first[2])
        val average = Color.rgb(pixels.sumOf { Color.red(it) } / 36, pixels.sumOf { Color.green(it) } / 36, pixels.sumOf { Color.blue(it) } / 36)
        return intArrayOf(tone(Color.HSVToColor(first), night), tone(Color.HSVToColor(second), night), tone(average, night))
    }

    private fun hueDistance(a: Float, b: Float): Float = min(kotlin.math.abs(a - b), 360f - kotlin.math.abs(a - b))

    private fun tone(color: Int, night: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = if (hsv[1] < 0.12f) 0.08f else hsv[1].coerceIn(0.30f, if (night) 0.62f else 0.45f)
        hsv[2] = if (night) hsv[2].coerceIn(0.40f, 0.60f) else hsv[2].coerceIn(0.82f, 0.94f)
        return Color.HSVToColor(hsv)
    }
}
