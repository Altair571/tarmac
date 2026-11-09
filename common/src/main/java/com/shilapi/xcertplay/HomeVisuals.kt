package com.shilapi.xcertplay

import android.animation.ArgbEvaluator
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
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Android's "Remove animations" switch, and animator duration scale 0, turn DiPlay's motion off too. */
internal fun motionEnabled(): Boolean = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

/**
 * Tarmac's motion, in one place. Things arrive quickly and settle slowly, as a car does when it pulls up;
 * they leave faster than they came. Every animation uses one of these durations and curves.
 */
internal object Motion {
    /** A press, a tick appearing. */
    const val QUICK = 120L
    /** A state changing inside a screen: a status line, a selection sliding. */
    const val BASE = 260L
    /** A screen or a card arriving. */
    const val ENTER = 440L
    /** The old screen leaving before the new one arrives. */
    const val EXIT = 110L
    /** The new screen waits this long, so the old one is mostly gone before it shows: one page, then the other. */
    const val HANDOVER = 70L
    /** Between one card and the next when a page opens. */
    const val STAGGER = 45L

    /** Arrivals: out of the gate fast, a long settle. */
    val enter: Interpolator = PathInterpolator(.16f, 1f, .3f, 1f)
    /** Departures. */
    val exit: Interpolator = PathInterpolator(.4f, 0f, 1f, 1f)
    /** Moves from one place to another on the same screen. */
    val move: Interpolator = PathInterpolator(.3f, 0f, 0f, 1f)
}

/**
 * The ambient light behind Home and Settings: two soft glows drifting slowly, tinted by the cover of
 * the song that is playing. Only view properties move, so the render thread animates them without a redraw.
 */
internal class AmbientBackdropView(context: Context, base: Int, private val night: Boolean) : FrameLayout(context) {
    private val glows = List(3) { View(context).apply { background = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT } } }
    private var colors = IntArray(3) { Color.TRANSPARENT }
    private var tintAnimator: ValueAnimator? = null
    private var drift: ValueAnimator? = null

    init {
        setBackgroundColor(base)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        glows.forEach { addView(it) }
    }

    /** Each glow is a square nine tenths of the longer side, whatever the window's shape. */
    private fun glowSize(width: Int, height: Int) = (max(width, height) * 0.9f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val exact = MeasureSpec.makeMeasureSpec(glowSize(width, height), MeasureSpec.EXACTLY)
        glows.forEach { it.measure(exact, exact) }
    }

    /**
     * The glows are placed here and not through layout parameters set while the size changes: those are
     * only read at the next layout pass, and until one came each glow kept the window's own shape, which
     * cut its light off along a straight edge.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val size = glowSize(width, height)
        glows.forEachIndexed { index, glow ->
            // Light from the top start, the end edge and below, as a low sun and a city light a road at dusk.
            val centreX = floatArrayOf(.10f, .95f, .45f)[index] * width
            val centreY = floatArrayOf(0f, .30f, 1.05f)[index] * height
            val glowLeft = (centreX - size / 2f).toInt()
            val glowTop = (centreY - size / 2f).toInt()
            glow.layout(glowLeft, glowTop, glowLeft + size, glowTop + size)
            (glow.background as GradientDrawable).gradientRadius = size / 2f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        paint(colors)
        place(SystemClock.uptimeMillis())
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
        val alpha = if (night) 0x7A else 0x66
        glows.forEachIndexed { index, glow ->
            (glow.background as GradientDrawable).colors = intArrayOf(withAlpha(next[index], alpha), withAlpha(next[index], 0))
        }
    }

    /**
     * Where each glow is at [now]. The drift is a function of the clock, not of when this view was made, so
     * a page that is rebuilt (every setting change rebuilds it) carries on from the same place without a jump.
     */
    private fun place(now: Long) {
        if (width == 0) return
        val seconds = now / 1000.0
        glows.forEachIndexed { index, glow ->
            val reach = width * .06f * if (index % 2 == 0) 1f else -1f
            fun wave(periodSeconds: Int, shift: Double) = sin(seconds * 2 * Math.PI / periodSeconds + shift).toFloat()
            glow.translationX = reach * wave(34 + index * 8, index * 2.1)
            glow.translationY = reach * .6f * wave(26 + index * 6, index * 1.3)
            val scale = 1.06f + .06f * wave(22 + index * 4, index * .7)
            glow.scaleX = scale
            glow.scaleY = scale
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) restartDrift() else stopDrift()
    }

    private fun restartDrift() {
        stopDrift()
        if (!motionEnabled() || width == 0 || !isAttachedToWindow) return
        drift = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { place(SystemClock.uptimeMillis()) }
            start()
        }
    }

    private fun stopDrift() {
        drift?.cancel()
        drift = null
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
 * The CarPlay app icon with its state: a plain icon while it waits, ripples in the icon's own
 * rounded-square shape while it connects, and a corner badge (a tick, or an exclamation mark) that
 * pops in once it is connected or needs attention.
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
    private var badge = 1f
    private var badgeAnimator: ValueAnimator? = null
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
            popBadge()
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The badge springs out of the icon's corner when the state it marks begins. */
    private fun popBadge() {
        badgeAnimator?.cancel()
        badge = 1f
        if (state != PulseState.CONNECTED && state != PulseState.ATTENTION) return
        if (!motionEnabled() || !isAttachedToWindow) return
        badgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.ENTER
            interpolator = OvershootInterpolator(2.6f)
            addUpdateListener { badge = it.animatedValue as Float; invalidate() }
            start()
        }
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
            for (wave in 0 until RIPPLES) {
                val t = (phase + wave / RIPPLES.toFloat()) % 1f
                // A ripple slows as it spreads and thins out before it reaches the edge.
                val grow = (1f - (1f - t) * (1f - t)) * reach
                ripple.color = withAlpha(color, ((1f - t) * (1f - t) * 190).toInt())
                bounds.set(left - grow, top - grow, left + iconSize + grow, top + iconSize + grow)
                canvas.drawRoundRect(bounds, corner + grow * .5f, corner + grow * .5f, ripple)
            }
        }
        icon?.let {
            it.setBounds(left.toInt(), top.toInt(), (left + iconSize).toInt(), (top + iconSize).toInt())
            it.draw(canvas)
        }
        if (state != PulseState.CONNECTED && state != PulseState.ATTENTION) return
        val radius = iconSize * 0.19f * badge
        if (radius <= 0f) return
        val cx = left + iconSize - iconSize * 0.19f * .45f
        val cy = top + iconSize - iconSize * 0.19f * .45f
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
            duration = 2_400
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

    private companion object {
        const val RIPPLES = 3
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

/**
 * A press: the control sinks a little under the finger and springs back on release. Without [depth] the
 * control gives by the same few dp whatever its size, so a full-width row does not shrink more than a chip.
 */
@SuppressLint("ClickableViewAccessibility")
internal fun View.pressFeedback(depth: Float = 0f) {
    setOnTouchListener { view, event ->
        if (motionEnabled()) when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = if (depth > 0f) depth else {
                    val longest = max(view.width, view.height)
                    if (longest == 0) .97f
                    else (1f - PRESS_GIVE_DP * view.resources.displayMetrics.density / longest).coerceIn(.9f, .99f)
                }
                view.animate().scaleX(target).scaleY(target).setDuration(Motion.QUICK).setInterpolator(Motion.move).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                .setDuration(Motion.ENTER).setInterpolator(OvershootInterpolator(2.2f)).start()
        }
        false
    }
}

/** How far a pressed control gives, across its longer side. */
private const val PRESS_GIVE_DP = 10f

/** Cards rise into place one after another when a page opens, after [delay]. */
internal fun staggerIn(views: List<View>, rise: Float, delay: Long = 0L) {
    if (!motionEnabled()) return
    views.forEachIndexed { index, view ->
        view.alpha = 0f
        view.translationY = rise
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(delay + index * Motion.STAGGER).setDuration(Motion.ENTER)
            .setInterpolator(Motion.enter).start()
    }
}

/**
 * A road's centre line as a progress mark: amber dashes in a row, faint and still while nothing happens,
 * bright and streaming toward the end edge while Tarmac works (connecting, checking, downloading).
 * It is Tarmac's mark for "under way", the same dashes as the app icon's.
 */
internal class LaneProgressView(context: Context, private val color: Int = DiPlayPalette.LANE_AMBER) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val density = resources.displayMetrics.density
    private var phase = 0f
    private var energy = 0f
    private var stream: ValueAnimator? = null
    private var energyAnimator: ValueAnimator? = null
    /** The faint line shown at rest; 0 hides the dashes until they are [active]. */
    var restAlpha = .22f
        set(value) { field = value; invalidate() }
    var active = false
        set(value) {
            if (field == value) return
            field = value
            restart()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((DASH_HEIGHT_DP * density).toInt(), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0) return
        // The row fades out toward both ends, so dashes come from nowhere and leave the same way.
        fade.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(0, Color.BLACK, Color.BLACK, 0), floatArrayOf(0f, .12f, .88f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = restAlpha + (1f - restAlpha) * energy
        if (alpha <= 0f || width == 0) return
        val dash = DASH_LENGTH_DP * density
        val pitch = dash + DASH_GAP_DP * density
        val thickness = height.toFloat()
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), thickness, null)
        paint.color = withAlpha(color, (alpha * 255).toInt())
        val direction = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f
        var x = -pitch + (phase * pitch * direction).let { if (it < 0) it + pitch else it }
        while (x < width) {
            canvas.drawRoundRect(x, 0f, x + dash, thickness, thickness / 2, thickness / 2, paint)
            x += pitch
        }
        canvas.drawRect(0f, 0f, width.toFloat(), thickness, fade)
        canvas.restoreToCount(layer)
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
        val target = if (active) 1f else 0f
        if (!motionEnabled() || !isAttachedToWindow) {
            energy = target
            invalidate()
            return
        }
        energyAnimator = ValueAnimator.ofFloat(energy, target).apply {
            duration = Motion.BASE * 2
            interpolator = Motion.move
            addUpdateListener { energy = it.animatedValue as Float; invalidate() }
            start()
        }
        if (!active) return
        stream = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = STREAM_MILLIS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        stream?.cancel()
        stream = null
        energyAnimator?.cancel()
        energyAnimator = null
    }

    private companion object {
        const val DASH_LENGTH_DP = 18f
        const val DASH_GAP_DP = 12f
        const val DASH_HEIGHT_DP = 4f
        /** One dash and its gap pass in this time: an unhurried road, not a spinner. */
        const val STREAM_MILLIS = 620L
    }
}

/**
 * Tarmac's mark drawn to any size: the horizon bar in [bar] over the three centre-line dashes in [dash],
 * the same geometry as the app icon.
 */
internal class TarmacMarkDrawable(private val bar: Int, private val dash: Int = DiPlayPalette.LANE_AMBER) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val box = bounds
        val unit = min(box.width(), box.height()) / MARK_SIZE
        val left = box.left + (box.width() - MARK_SIZE * unit) / 2f
        val top = box.top + (box.height() - MARK_SIZE * unit) / 2f
        fun x(value: Float) = left + (value - MARK_LEFT) * unit
        fun y(value: Float) = top + (value - MARK_TOP) * unit
        paint.color = bar
        rect.set(x(30f), y(29.5f), x(78f), y(38.5f))
        canvas.drawRoundRect(rect, 4.5f * unit, 4.5f * unit, paint)
        paint.color = dash
        // Each dash is longer and wider than the one beyond it, as a centre line is seen from the driver's seat.
        for ((near, far) in DASHES) {
            path.rewind()
            path.moveTo(x(54f - halfWidth(near)), y(near))
            path.lineTo(x(54f + halfWidth(near)), y(near))
            path.lineTo(x(54f + halfWidth(far)), y(far))
            path.lineTo(x(54f - halfWidth(far)), y(far))
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun halfWidth(y: Float) = 2.8f + .11f * (y - 43f)

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /** The mark's box in the app icon's 108-unit grid. */
        const val MARK_LEFT = 28f
        const val MARK_TOP = 28f
        const val MARK_SIZE = 52f
        /** Where each dash starts and ends in that grid, as in ic_tarmac_foreground.xml. */
        val DASHES = listOf(43f to 49f, 53.5f to 62f, 67f to 79f)
    }
}

/**
 * Colours for a background or a card from a song's cover. Each is held to the same saturation and
 * brightness range, so a brown, neon or black cover all give a calm tint, and a grey cover stays neutral.
 */
internal object CoverPalette {
    /** Tarmac's own colours when nothing plays, a road at dusk: deep blue above, indigo at the edge, a low amber glow. */
    fun default(night: Boolean): IntArray = intArrayOf(
        tone(Color.rgb(22, 104, 227), night), tone(Color.rgb(98, 92, 222), night), tone(Color.rgb(214, 138, 64), night),
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
