package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The connecting screen's picture: a car's cabin from the driver's seat, as a camera focused on the centre
 * screen would see it, with the CarPlay icon rippling on that screen. Beyond the windscreen the road is out
 * of focus: at night a city glows and street lights pass as soft discs; by day the sky is blue.
 *
 * The cabin and the view beyond it are drawn once into bitmaps, so they can be blurred on any Android
 * version; only the passing lights redraw while the road moves.
 */
internal class ConnectingDashboardView(context: Context, icon: Drawable?) : ViewGroup(context) {
    private val outside = CachedLayer(context, { it.outsideTop }) { canvas, scene -> scene.drawOutside(canvas, colors) }
    private val traffic = TrafficView(context)
    private val cabin = CachedLayer(context, { 0f }) { canvas, scene -> scene.drawCabin(canvas, colors) }
    /** The CarPlay icon on the centre screen. */
    val pulse = ConnectionPulseView(context, icon)
    private var scene: Scene? = null
    private var colors = SceneColors.of(night = true)

    /** Follows the app theme, as the rest of the connecting screen does. */
    var night = true
        set(value) {
            if (field == value) return
            field = value
            colors = SceneColors.of(value)
            repaint()
        }

    /** Puts the wheel on the right and the road's centre line beside it, as a right-hand-drive driver sees. */
    var rightHandDrive = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            repaint()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(outside)
        addView(traffic)
        addView(cabin)
        addView(pulse)
        repaint()
    }

    private fun repaint() {
        pulse.color = IosColors.blue(night)
        pulse.surface = colors.glass
        outside.invalidate()
        traffic.invalidate()
        cabin.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        // The picture keeps a car-like frame, between a tall 6:5 and a wide 17:10, centred in what is left.
        val cardWidth = min(width.toFloat(), height * MAX_ASPECT)
        val cardHeight = min(height.toFloat(), cardWidth / MIN_ASPECT)
        val previous = scene
        val next = if (previous != null && previous.w == cardWidth && previous.h == cardHeight) previous
        else Scene(cardWidth, cardHeight, resources.displayMetrics.density)
        scene = next
        fun exactly(size: Float) = MeasureSpec.makeMeasureSpec(size.roundToInt().coerceAtLeast(0), MeasureSpec.EXACTLY)
        outside.measure(exactly(next.w), exactly(next.outsideBottom - next.outsideTop))
        traffic.measure(exactly(next.w), exactly(next.outsideBottom - next.outsideTop))
        cabin.measure(exactly(next.w), exactly(next.h))
        pulse.measure(exactly(next.pulseSize), exactly(next.pulseSize))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = scene ?: return
        val left = ((r - l - s.w) / 2f).roundToInt()
        val top = ((b - t - s.h) / 2f).roundToInt()
        cabin.layout(left, top, left + cabin.measuredWidth, top + cabin.measuredHeight)
        val outsideTop = top + s.outsideTop.roundToInt()
        outside.layout(left, outsideTop, left + outside.measuredWidth, outsideTop + outside.measuredHeight)
        traffic.layout(left, outsideTop, left + traffic.measuredWidth, outsideTop + traffic.measuredHeight)
        // The icon itself is never mirrored: only its place on the cabin's screen.
        val centreX = left + if (rightHandDrive) s.w - s.screen.centerX() else s.screen.centerX()
        val pulseLeft = (centreX - pulse.measuredWidth / 2f).roundToInt()
        val pulseTop = (top + s.screen.centerY() - pulse.measuredHeight / 2f).roundToInt()
        pulse.layout(pulseLeft, pulseTop, pulseLeft + pulse.measuredWidth, pulseTop + pulse.measuredHeight)
    }

    /** A still part of the picture, drawn once in software (where blurs work everywhere) and kept as a bitmap. */
    private inner class CachedLayer(
        context: Context,
        private val offset: (Scene) -> Float,
        private val painter: (Canvas, Scene) -> Unit,
    ) : View(context) {
        private var cache: Bitmap? = null
        private var cachedFor: Any? = null

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val s = scene ?: return
            val bitmap = render(s)
            if (bitmap != null) canvas.drawBitmap(bitmap, 0f, 0f, null) else paint(canvas, s)
        }

        private fun render(s: Scene): Bitmap? {
            if (width <= 0 || height <= 0) return null
            val key = listOf(s, colors, rightHandDrive, width, height)
            cache?.let { if (cachedFor == key) return it }
            val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
            paint(Canvas(bitmap), s)
            cache = bitmap
            cachedFor = key
            return bitmap
        }

        private fun paint(canvas: Canvas, s: Scene) {
            canvas.save()
            canvas.translate(0f, -offset(s))
            if (rightHandDrive) canvas.scale(-1f, 1f, s.w / 2f, 0f)
            painter(canvas, s)
            canvas.restore()
        }

        override fun onDetachedFromWindow() {
            cache = null
            cachedFor = null
            super.onDetachedFromWindow()
        }
    }

    /** The street lights and the centre line passing: the only part that moves. */
    private inner class TrafficView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val quad = Path()
        // One soft disc of light, drawn at each lamp's place and size: an out-of-focus light, as a camera sees it.
        private val warmBokeh = bokeh(Color.rgb(255, 178, 76))
        private val whiteBokeh = bokeh(Color.rgb(255, 228, 186))
        private var phase = .35f
        private var animator: ValueAnimator? = null

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        private fun bokeh(color: Int) = RadialGradient(0f, 0f, 1f,
            intArrayOf(withAlpha(color, 0xF0), withAlpha(color, 0xD8), withAlpha(color, 0x70), withAlpha(color, 0)),
            floatArrayOf(0f, .5f, .78f, 1f), Shader.TileMode.CLAMP)

        override fun onDraw(canvas: Canvas) {
            val s = scene ?: return
            canvas.save()
            canvas.translate(0f, -s.outsideTop)
            if (rightHandDrive) canvas.scale(-1f, 1f, s.w / 2f, 0f)
            drawCentreLine(canvas, s)
            if (night) drawStreetLights(canvas, s) else drawPoles(canvas, s)
            canvas.restore()
        }

        // Depth runs from 1 at the windscreen's lower edge to the horizon; a point at depth z sits reach / z below it.
        private fun depth(index: Int, count: Int, spacing: Float, nearest: Float): Float {
            val loop = count * spacing
            return nearest + ((index * spacing - phase * LIGHT_SPACING) % loop + loop) % loop
        }

        private fun drawCentreLine(canvas: Canvas, s: Scene) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            for (index in 0 until DASHES) {
                val near = depth(index, DASHES, DASH_SPACING, NEAREST_DEPTH)
                val k1 = s.reach / near
                val k2 = s.reach / (near + DASH_LENGTH)
                if (k1 < s.density * .5f) continue
                // Out of focus: a faint wide stroke under the line itself.
                for ((spread, alpha) in listOf(2.6f to 0x30, 1f to 0xB8)) {
                    val half = LINE_HALF_WIDTH * spread
                    quad.reset()
                    quad.moveTo(s.vanishX + (CENTRE_LINE - half) * k1, s.horizon + k1)
                    quad.lineTo(s.vanishX + (CENTRE_LINE + half) * k1, s.horizon + k1)
                    quad.lineTo(s.vanishX + (CENTRE_LINE + half) * k2, s.horizon + k2)
                    quad.lineTo(s.vanishX + (CENTRE_LINE - half) * k2, s.horizon + k2)
                    quad.close()
                    paint.color = withAlpha(colors.centreLine, (alpha * smoothstep(0f, s.h * .02f, k1)).toInt())
                    canvas.drawPath(quad, paint)
                }
            }
        }

        private fun drawStreetLights(canvas: Canvas, s: Scene) {
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            for ((side, lateral) in LAMP_SIDES.withIndex()) {
                paint.shader = if (side == 0) warmBokeh else whiteBokeh
                for (index in 0 until LAMPS) {
                    val k = s.reach / depth(index, LAMPS, LIGHT_SPACING, NEAREST_LAMP)
                    val fade = smoothstep(s.h * .003f, s.h * .03f, k)
                    if (fade <= 0f) continue
                    val x = s.vanishX + (lateral - Math.signum(lateral) * .8f) * k
                    val y = s.horizon - (LAMP_HEIGHT - 1f) * k
                    // Far lights blur to about the same disc; near ones grow a little.
                    val radius = s.h * (.01f + .013f * smoothstep(0f, s.reach * .4f, k))
                    paint.alpha = (255 * fade * (.55f + .45f * smoothstep(0f, s.reach * .3f, k))).toInt()
                    canvas.save()
                    canvas.translate(x, y)
                    canvas.scale(radius, radius)
                    canvas.drawCircle(0f, 0f, 1f, paint)
                    canvas.restore()
                }
            }
            paint.shader = null
            paint.alpha = 255
        }

        private fun drawPoles(canvas: Canvas, s: Scene) {
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            for (lateral in LAMP_SIDES) {
                for (index in 0 until LAMPS) {
                    val k = s.reach / depth(index, LAMPS, LIGHT_SPACING, NEAREST_LAMP)
                    val fade = smoothstep(s.h * .004f, s.h * .04f, k)
                    if (fade <= 0f) continue
                    val x = s.vanishX + lateral * k
                    val arm = x - Math.signum(lateral) * .8f * k
                    val head = s.horizon - (LAMP_HEIGHT - 1f) * k
                    for ((spread, alpha) in listOf(2.8f to 0x2C, 1f to 0xA0)) {
                        paint.strokeWidth = max(s.density, .07f * k) * spread
                        paint.color = withAlpha(colors.pole, (alpha * fade).toInt())
                        canvas.drawLine(x, s.horizon + k, x, head, paint)
                        canvas.drawLine(x, head, arm, head + .1f * k, paint)
                    }
                }
            }
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            if (isVisible) start() else stop()
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        private fun start() {
            stop()
            if (!motionEnabled() || !isAttachedToWindow) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = LIGHT_PERIOD_MS
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

    /** Where everything sits on a card of [w] × [h] pixels, for a left-hand-drive car, and how to draw it. */
    private class Scene(val w: Float, val h: Float, val density: Float) {
        val corner = min(28f * density, h * .07f)
        val card = Path().apply { addRoundRect(RectF(0f, 0f, w, h), corner, corner, Path.Direction.CW) }
        val horizon = h * .36f
        /** The windscreen's lower edge at its middle, where the dashboard meets the glass. */
        val dashLine = h * .565f
        /** How far below the horizon the road is at the windscreen's lower edge: depth 1. */
        val reach = dashLine - horizon
        /** Below the card's rounded corners, so the sky never shows past them. */
        val outsideTop = corner
        val outsideBottom = h * .63f
        val wheelX = w * .25f
        val wheelY = h * .93f
        val wheelRadius = h * .37f
        val wheelThickness = h * .068f
        /** The road runs to the horizon straight ahead of the driver. */
        val vanishX = wheelX
        val screen = RectF()
        val bezel = RectF()
        val screenCorner = h * .024f
        val bezelCorner = h * .036f
        val pulseSize: Float
        val glass = Path()
        private val frame = Path()
        private val glassTop = FloatArray(4)
        private val glassLeft = FloatArray(4)
        private val glassRight = FloatArray(4)
        private val mirror = RectF()
        private val cluster = RectF()
        private val hoodHalfWidth = h * .3f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            // The windscreen: a wide left pillar close to the driver, a slim right one across the car.
            glassLeft[0] = w * .115f; glassLeft[1] = h * .145f; glassLeft[2] = -w * .06f; glassLeft[3] = h * .68f
            glassRight[0] = w * .935f; glassRight[1] = h * .115f; glassRight[2] = w * 1.06f; glassRight[3] = h * .5f
            val radius = h * .05f
            fun toward(fromX: Float, fromY: Float, toX: Float, toY: Float): Pair<Float, Float> {
                val length = hypot(toX - fromX, toY - fromY)
                return (fromX + (toX - fromX) / length * radius) to (fromY + (toY - fromY) / length * radius)
            }
            val (a1x, a1y) = toward(glassLeft[0], glassLeft[1], glassLeft[2], glassLeft[3])
            val (a2x, a2y) = toward(glassLeft[0], glassLeft[1], w * .3f, h * .07f)
            val (b1x, b1y) = toward(glassRight[0], glassRight[1], w * .75f, h * .06f)
            val (b2x, b2y) = toward(glassRight[0], glassRight[1], glassRight[2], glassRight[3])
            glassTop[0] = a2x; glassTop[1] = a2y; glassTop[2] = b1x; glassTop[3] = b1y
            glass.moveTo(glassLeft[2], glassLeft[3])
            glass.lineTo(a1x, a1y)
            glass.quadTo(glassLeft[0], glassLeft[1], a2x, a2y)
            glass.cubicTo(w * .32f, h * .07f, w * .7f, h * .065f, b1x, b1y)
            glass.quadTo(glassRight[0], glassRight[1], b2x, b2y)
            glass.lineTo(glassRight[2], glassRight[3])
            glass.lineTo(w * 1.06f, h * .75f)
            glass.lineTo(-w * .06f, h * .75f)
            glass.close()
            frame.op(card, glass, Path.Op.DIFFERENCE)

            mirror.set(w * .585f - h * .115f, h * .1f, w * .585f + h * .115f, h * .158f)
            cluster.set(wheelX - h * .235f, h * .552f, wheelX + h * .235f, h * .75f)

            // The centre screen floats above the dashboard, to the driver's right.
            val screenWidth = min(w * .44f, h * .6f)
            val screenHeight = screenWidth / 1.62f
            val screenCentre = w * .68f
            val screenBottom = h * .625f
            screen.set(screenCentre - screenWidth / 2f, screenBottom - screenHeight, screenCentre + screenWidth / 2f, screenBottom)
            bezel.set(screen.left - h * .012f, screen.top - h * .012f, screen.right + h * .012f, screen.bottom + h * .012f)
            pulseSize = screenHeight * .74f
        }

        /** The windscreen's lower edge, a little lower towards the sides. */
        private fun farEdge(x: Float) = dashLine + h * .02f * sq((x - w * .6f) / (w * .6f))

        /** The dashboard's near edge, above the ambient light. */
        private fun lip(x: Float) = h * .655f + h * .012f * sq((x - w * .7f) / (w * .5f))

        private fun hoodBack(x: Float): Float {
            val t = abs(x - wheelX) / hoodHalfWidth
            return if (t >= 1f) farEdge(x) else h * .5f + (farEdge(x) - h * .5f) * smoothstep(.62f, 1f, t)
        }

        private fun hoodLip(x: Float): Float {
            val t = abs(x - wheelX) / hoodHalfWidth
            return if (t >= 1f) farEdge(x) else h * .549f + (farEdge(x) - h * .549f) * smoothstep(.55f, 1f, t)
        }

        private fun sq(v: Float) = v * v

        private fun curve(from: Float, to: Float, y: (Float) -> Float, path: Path = Path(), start: Boolean = true): Path {
            val steps = 32
            for (step in 0..steps) {
                val x = from + (to - from) * step / steps
                if (step == 0 && start) path.moveTo(x, y(x)) else path.lineTo(x, y(x))
            }
            return path
        }

        private fun fill(color: Int) = paint.apply {
            reset()
            isAntiAlias = true
            style = Paint.Style.FILL
            this.color = color
        }

        private fun stroke(color: Int, width: Float) = paint.apply {
            reset()
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }

        private fun blur(radius: Float) = BlurMaskFilter(max(.5f, radius), BlurMaskFilter.Blur.NORMAL)

        private fun vertical(top: Float, bottom: Float, vararg colors: Int) =
            LinearGradient(0f, top, 0f, bottom, colors, null, Shader.TileMode.CLAMP)

        private fun glow(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, alpha: Int) {
            fill(Color.WHITE).shader = RadialGradient(0f, 0f, 1f, intArrayOf(withAlpha(color, alpha), withAlpha(color, alpha / 3),
                withAlpha(color, 0)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(rx, ry); postTranslate(cx, cy) })
            }
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
        }

        private inline fun random(seed: Int, block: (() -> Float) -> Unit) {
            var state = seed
            block {
                state = (state * 1103515245 + 12345) and 0x7FFFFFFF
                state / 2147483647f
            }
        }

        // ---- Beyond the windscreen ----

        fun drawOutside(canvas: Canvas, c: SceneColors) {
            fill(Color.WHITE).shader = vertical(outsideTop, horizon, c.skyTop, c.skyMiddle, c.skyHorizon)
            canvas.drawRect(0f, outsideTop, w, horizon + 1f, paint)
            glow(canvas, w * .55f, horizon, w * .75f, h * .2f, c.horizonGlow, c.horizonGlowAlpha)
            glow(canvas, w * .2f, horizon, w * .35f, h * .12f, c.horizonGlow, c.horizonGlowAlpha / 2)
            if (c.night) drawStars(canvas) else drawClouds(canvas, c)
            drawSkyline(canvas, c)
            fill(c.ground).shader = vertical(horizon, outsideBottom, c.groundFar, c.ground)
            canvas.drawRect(0f, horizon, w, outsideBottom, paint)
            drawRoad(canvas, c)
            drawCarAhead(canvas, c)
        }

        private fun drawStars(canvas: Canvas) = random(11) { next ->
            fill(Color.WHITE).maskFilter = blur(density * .4f)
            repeat(10) {
                val x = w * (.15f + next() * .8f)
                val y = h * (.08f + next() * .13f)
                paint.alpha = (60 + next() * 110).toInt()
                canvas.drawCircle(x, y, density * (.6f + next() * .7f), paint)
            }
        }

        private fun drawClouds(canvas: Canvas, c: SceneColors) = random(5) { next ->
            fill(c.cloud).maskFilter = blur(h * .03f)
            repeat(6) {
                val cx = w * (.1f + next() * .85f)
                val cy = h * (.12f + next() * .14f)
                val rx = w * (.06f + next() * .08f)
                canvas.drawOval(cx - rx, cy - rx * .28f, cx + rx, cy + rx * .28f, paint)
            }
        }

        private fun drawSkyline(canvas: Canvas, c: SceneColors) {
            // Low hills across the horizon, and the distant town the road runs into.
            val hills = Path().apply {
                moveTo(0f, horizon + 1f)
                curve(0f, w, { x -> horizon - h * (.018f + .014f * sin(x / w * 9f) + .01f * sin(x / w * 23f + 1f)) }, this, start = false)
                lineTo(w, horizon + 1f)
                close()
            }
            fill(c.hills).maskFilter = blur(h * .004f)
            canvas.drawPath(hills, paint)
            random(3) { next ->
                var x = vanishX - w * .22f
                val lights = mutableListOf<FloatArray>()
                while (x < vanishX + w * .24f) {
                    val width = w * (.012f + next() * .026f)
                    val centre = 1f - min(1f, abs(x - vanishX - w * .03f) / (w * .26f))
                    val height = h * (.008f + (.012f + next() * .045f) * centre)
                    fill(c.skyline).maskFilter = blur(h * if (c.night) .0025f else .005f)
                    canvas.drawRect(x, horizon - height, x + width, horizon + h * .004f, paint)
                    if (c.night) repeat((height / (h * .008f)).toInt()) {
                        lights += floatArrayOf(x + width * (.15f + next() * .7f), horizon - height * (.1f + next() * .8f), next())
                    }
                    x += width * (.8f + next() * .5f)
                }
                if (c.night) {
                    fill(Color.WHITE).maskFilter = blur(h * .0015f)
                    for ((lx, ly, tone) in lights) {
                        paint.color = withAlpha(if (tone > .8f) Color.rgb(200, 220, 255) else Color.rgb(255, 205, 130), 0xB0)
                        canvas.drawCircle(lx, ly, h * .0022f, paint)
                    }
                }
                // Lights along the horizon, soft as a camera focused on the cabin sees them.
                fill(Color.WHITE).maskFilter = blur(h * .004f)
                if (c.night) repeat(56) {
                    val lx = w * next()
                    val tone = next()
                    paint.color = withAlpha(when {
                        tone > .9f -> Color.rgb(255, 90, 80)
                        tone > .7f -> Color.rgb(220, 232, 255)
                        else -> Color.rgb(255, 196, 110)
                    }, (110 + next() * 120).toInt())
                    canvas.drawCircle(lx, horizon - h * (.002f + next() * .008f), h * (.0025f + next() * .004f), paint)
                }
            }
        }

        private fun drawRoad(canvas: Canvas, c: SceneColors) {
            val far = outsideBottom - horizon
            fun x(lateral: Float, k: Float) = vanishX + lateral * k
            val road = Path().apply {
                moveTo(vanishX, horizon)
                lineTo(x(RIGHT_EDGE + .5f, far), outsideBottom)
                lineTo(x(LEFT_EDGE - .5f, far), outsideBottom)
                close()
            }
            fill(c.road).shader = vertical(horizon, outsideBottom, c.roadFar, c.road)
            canvas.drawPath(road, paint)
            if (c.night) {
                // The wet road holds the town's glow.
                canvas.save()
                canvas.clipPath(road)
                glow(canvas, vanishX, horizon + reach * .25f, reach * 1.2f, reach * .45f, c.horizonGlow, 0x50)
                canvas.restore()
            }
            for (lateral in floatArrayOf(RIGHT_EDGE, LEFT_EDGE)) {
                val line = Path().apply {
                    moveTo(vanishX, horizon)
                    lineTo(x(lateral + LINE_HALF_WIDTH, far), outsideBottom)
                    lineTo(x(lateral - LINE_HALF_WIDTH, far), outsideBottom)
                    close()
                }
                fill(c.edgeLine).maskFilter = blur(h * .002f)
                canvas.drawPath(line, paint)
            }
        }

        private fun drawCarAhead(canvas: Canvas, c: SceneColors) {
            val k = reach / CAR_DEPTH
            val ground = horizon + k
            val cx = vanishX + CAR_LATERAL * k
            // Its shadow, then the body, the cabin and the rear window.
            glow(canvas, cx, ground, k * .95f, k * .07f, Color.BLACK, if (c.night) 0xD0 else 0x90)
            val body = Path().apply {
                addRoundRect(RectF(cx - k * .8f, ground - k * .68f, cx + k * .8f, ground - k * .1f), k * .14f, k * .14f, Path.Direction.CW)
            }
            val roof = Path().apply {
                moveTo(cx - k * .7f, ground - k * .62f)
                lineTo(cx - k * .5f, ground - k * 1.12f)
                quadTo(cx, ground - k * 1.2f, cx + k * .5f, ground - k * 1.12f)
                lineTo(cx + k * .7f, ground - k * .62f)
                close()
            }
            for (tyre in floatArrayOf(-.62f, .62f)) {
                fill(c.tyre).maskFilter = blur(k * .02f)
                canvas.drawRoundRect(cx + tyre * k - k * .13f, ground - k * .2f, cx + tyre * k + k * .13f, ground, k * .04f, k * .04f, paint)
            }
            fill(c.carBody).shader = vertical(ground - k * 1.2f, ground, c.carHighlight, c.carBody)
            paint.maskFilter = blur(k * .02f)
            canvas.drawPath(roof, paint)
            canvas.drawPath(body, paint)
            val window = Path().apply {
                moveTo(cx - k * .6f, ground - k * .66f)
                lineTo(cx - k * .44f, ground - k * 1.05f)
                quadTo(cx, ground - k * 1.12f, cx + k * .44f, ground - k * 1.05f)
                lineTo(cx + k * .6f, ground - k * .66f)
                close()
            }
            fill(c.carWindow).shader = vertical(ground - k * 1.1f, ground - k * .66f, c.carWindowTop, c.carWindow)
            paint.maskFilter = blur(k * .02f)
            canvas.drawPath(window, paint)
            // Tail lights joined by a light bar, glowing on the road below at night.
            val lightY = ground - k * .5f
            for (side in floatArrayOf(-1f, 1f)) {
                val inner = cx + side * k * .44f
                val outer = cx + side * k * .76f
                if (c.night) {
                    glow(canvas, (inner + outer) / 2f, lightY, k * .5f, k * .3f, c.tailLight, 0x90)
                    fill(c.tailLight).shader = vertical(lightY, ground + k * .9f, withAlpha(c.tailLight, 0x60), withAlpha(c.tailLight, 0))
                    paint.maskFilter = blur(k * .05f)
                    canvas.drawRect(min(inner, outer) + k * .08f, ground, max(inner, outer) - k * .08f, ground + k * .9f, paint)
                }
                fill(c.tailLight).maskFilter = blur(k * .015f)
                canvas.drawRoundRect(min(inner, outer), lightY - k * .045f, max(inner, outer), lightY + k * .045f, k * .04f, k * .04f, paint)
                if (c.night) {
                    fill(Color.rgb(255, 196, 190)).maskFilter = blur(k * .01f)
                    canvas.drawRoundRect(min(inner, outer) + k * .04f, lightY - k * .015f, max(inner, outer) - k * .04f,
                        lightY + k * .015f, k * .02f, k * .02f, paint)
                }
            }
            fill(withAlpha(c.tailLight, if (c.night) 0xA0 else 0x70)).maskFilter = blur(k * .01f)
            canvas.drawRect(cx - k * .44f, lightY - k * .012f, cx + k * .44f, lightY + k * .012f, paint)
        }

        // ---- The cabin ----

        fun drawCabin(canvas: Canvas, c: SceneColors) {
            canvas.save()
            canvas.clipPath(card)
            drawGlassEffects(canvas, c)
            drawRoofAndPillars(canvas, c)
            drawMirror(canvas, c)
            drawDashboard(canvas, c)
            drawCluster(canvas, c)
            drawVents(canvas, c)
            drawScreen(canvas, c)
            drawWheel(canvas, c)
            // A camera's darker corners.
            fill(Color.WHITE).shader = RadialGradient(w * .55f, h * .45f, hypot(w, h) * .62f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(Color.BLACK, c.vignette)), floatArrayOf(0f, .6f, 1f),
                Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            canvas.restore()
            stroke(c.outline, density).strokeCap = Paint.Cap.BUTT
            canvas.drawRoundRect(density / 2f, density / 2f, w - density / 2f, h - density / 2f, corner, corner, paint)
        }

        private fun drawGlassEffects(canvas: Canvas, c: SceneColors) {
            canvas.save()
            canvas.clipPath(glass)
            // The dashboard reflects in the bottom of the glass, its ambient light as a faint line.
            fill(Color.WHITE).shader = vertical(dashLine - h * .09f, dashLine, withAlpha(c.dashReflection, 0), c.dashReflection)
            canvas.drawRect(0f, dashLine - h * .09f, w, h, paint)
            stroke(withAlpha(c.ambient, c.ambientReflection), h * .004f).maskFilter = blur(h * .004f)
            canvas.drawPath(curve(wheelX + hoodHalfWidth, w, { x -> farEdge(x) - h * .035f }), paint)
            // A soft streak of light across the glass.
            val streak = Path().apply {
                moveTo(w * .3f, 0f)
                lineTo(w * .5f, 0f)
                lineTo(w * .2f, h * .7f)
                lineTo(0f, h * .7f)
                close()
            }
            fill(Color.WHITE).shader = LinearGradient(w * .1f, h * .35f, w * .35f, h * .35f,
                intArrayOf(withAlpha(Color.WHITE, 0), withAlpha(Color.WHITE, c.glassStreak), withAlpha(Color.WHITE, 0)),
                null, Shader.TileMode.CLAMP)
            canvas.drawPath(streak, paint)
            // The black frit band round the glass, fading inwards.
            for ((width, alpha) in listOf(h * .07f to 0x38, h * .045f to 0x60, h * .022f to 0xC0)) {
                stroke(withAlpha(Color.BLACK, alpha), width).maskFilter = blur(h * .006f)
                canvas.drawPath(glass, paint)
            }
            canvas.restore()
        }

        private fun drawRoofAndPillars(canvas: Canvas, c: SceneColors) {
            fill(c.headliner).shader = vertical(0f, h * .6f, c.headliner, c.headlinerEdge, c.pillar)
            canvas.drawPath(frame, paint)
            // Each pillar's trim is lit along the glass and falls into shadow towards the door.
            for ((edge, reach) in listOf(glassLeft to -w * .045f, glassRight to w * .025f)) {
                val dx = edge[2] - edge[0]
                val dy = edge[3] - edge[1]
                val length = hypot(dx, dy)
                val nx = -dy / length * abs(reach) * Math.signum(reach)
                val ny = dx / length * abs(reach) * Math.signum(reach)
                val band = Path().apply {
                    moveTo(edge[0], edge[1])
                    lineTo(edge[2], edge[3])
                    lineTo(edge[2] - nx, edge[3] - ny)
                    lineTo(edge[0] - nx, edge[1] - ny)
                    close()
                }
                val midX = (edge[0] + edge[2]) / 2f
                val midY = (edge[1] + edge[3]) / 2f
                canvas.save()
                canvas.clipPath(frame)
                fill(Color.WHITE).shader = LinearGradient(midX, midY, midX - nx, midY - ny, c.pillarLit, withAlpha(c.pillarLit, 0),
                    Shader.TileMode.CLAMP)
                canvas.drawPath(band, paint)
                canvas.restore()
            }
            // The headliner's edge catches the light above the glass.
            stroke(c.trimHighlight, max(density, h * .003f)).maskFilter = blur(density * .6f)
            canvas.drawPath(Path().apply {
                moveTo(glassTop[0], glassTop[1] - h * .004f)
                cubicTo(w * .32f, h * .066f, w * .7f, h * .061f, glassTop[2], glassTop[3] - h * .004f)
            }, paint)
        }

        private fun drawMirror(canvas: Canvas, c: SceneColors) {
            val cx = mirror.centerX()
            // The sensor housing on the glass, the arm, then the mirror itself.
            fill(c.trim).shader = vertical(h * .03f, h * .085f, c.trimLit, c.trim)
            canvas.drawRoundRect(cx - h * .04f, h * .03f, cx + h * .04f, h * .085f, h * .015f, h * .015f, paint)
            fill(c.trim)
            canvas.drawRoundRect(cx - h * .011f, h * .07f, cx + h * .011f, mirror.centerY(), h * .008f, h * .008f, paint)
            fill(withAlpha(Color.BLACK, 0x80)).maskFilter = blur(h * .008f)
            canvas.drawRoundRect(mirror.left, mirror.top + h * .008f, mirror.right, mirror.bottom + h * .01f, h * .028f, h * .028f, paint)
            fill(c.trim).shader = vertical(mirror.top, mirror.bottom, c.trimLit, c.trim)
            canvas.drawRoundRect(mirror, h * .028f, h * .028f, paint)
            val inset = h * .005f
            fill(c.mirrorGlass).shader = vertical(mirror.top, mirror.bottom, c.mirrorGlassTop, c.mirrorGlass)
            canvas.drawRoundRect(mirror.left + inset, mirror.top + inset, mirror.right - inset, mirror.bottom - inset,
                h * .024f, h * .024f, paint)
            stroke(c.trimHighlight, max(density * .8f, h * .002f))
            canvas.drawLine(mirror.left + h * .03f, mirror.top + density, mirror.right - h * .03f, mirror.top + density, paint)
        }

        private fun drawDashboard(canvas: Canvas, c: SceneColors) {
            // The face under the dashboard's edge, all the way down behind the wheel.
            val face = curve(-1f, w + 1f, this::lip).apply {
                lineTo(w + 1f, h + 1f)
                lineTo(-1f, h + 1f)
                close()
            }
            fill(c.dashFace).shader = vertical(h * .66f, h, c.dashFaceTop, c.dashFace, c.dashFaceBottom)
            canvas.drawPath(face, paint)
            // The top, lit by the sky along the glass and darker towards the driver.
            val top = curve(-1f, w + 1f, this::farEdge).apply {
                curve(w + 1f, -1f, this@Scene::lip, this, start = false)
                close()
            }
            fill(c.dashTop).shader = vertical(dashLine, h * .66f, c.dashTopFar, c.dashTop)
            canvas.drawPath(top, paint)
            // The screen's light on the dashboard below it.
            glow(canvas, screen.centerX(), h * .645f, screen.width() * .62f, h * .05f, c.screenLight, c.spillAlpha)
            // Stitching along the edge, the edge's highlight, and the ambient light just under it.
            val edgeFrom = wheelX + hoodHalfWidth * .85f
            stroke(c.stitch, max(density * .8f, h * .0022f)).pathEffect = DashPathEffect(floatArrayOf(h * .01f, h * .007f), 0f)
            canvas.drawPath(curve(edgeFrom, w, { x -> lip(x) - h * .012f }), paint)
            stroke(c.edgeHighlight, max(density, h * .003f)).maskFilter = blur(density * .5f)
            canvas.drawPath(curve(edgeFrom, w, this::lip), paint)
            val strip = curve(edgeFrom, w + 1f, { x -> lip(x) + h * .011f })
            // Its light falls on the face below.
            canvas.save()
            canvas.clipPath(face)
            fill(Color.WHITE).shader = vertical(h * .66f, h * .74f, withAlpha(c.ambient, c.ambientSpill), withAlpha(c.ambient, 0))
            canvas.drawRect(edgeFrom, h * .66f, w, h * .74f, paint)
            canvas.restore()
            stroke(withAlpha(c.ambient, c.ambientGlow), h * .02f).maskFilter = blur(h * .01f)
            canvas.drawPath(strip, paint)
            stroke(withAlpha(c.ambient, 0xF0), max(density * 1.2f, h * .0045f))
            canvas.drawPath(strip, paint)
            stroke(withAlpha(Color.WHITE, 0x90), max(density * .6f, h * .0015f))
            canvas.drawPath(strip, paint)
            // The cowl over the instruments in front of the driver.
            val hood = curve(wheelX - hoodHalfWidth, wheelX + hoodHalfWidth, this::hoodBack).apply {
                curve(wheelX + hoodHalfWidth, wheelX - hoodHalfWidth, this@Scene::hoodLip, this, start = false)
                close()
            }
            fill(c.dashTop).shader = vertical(h * .5f, h * .56f, c.dashTopFar, c.dashTop)
            canvas.drawPath(hood, paint)
            stroke(c.edgeHighlight, max(density, h * .003f)).maskFilter = blur(density * .5f)
            canvas.drawPath(curve(wheelX - hoodHalfWidth * .8f, wheelX + hoodHalfWidth * .8f, this::hoodLip), paint)
        }

        private fun drawCluster(canvas: Canvas, c: SceneColors) {
            fill(c.clusterGlass).shader = vertical(cluster.top, cluster.bottom, c.clusterGlassTop, c.clusterGlass)
            canvas.drawRoundRect(cluster, h * .03f, h * .03f, paint)
            // Two quiet gauges with their needles at rest, as a cluster shows before the car moves off.
            val radius = h * .052f
            for ((index, fraction) in listOf(.3f, .7f).withIndex()) {
                val cx = cluster.left + cluster.width() * fraction
                val cy = h * .67f
                val tint = if (index == 0) c.gaugeLeft else c.gaugeRight
                stroke(c.gaugeTrack, h * .006f)
                canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 135f, 270f, false, paint)
                stroke(withAlpha(tint, 0x60), h * .016f).maskFilter = blur(h * .008f)
                canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 135f, 50f, false, paint)
                stroke(tint, h * .006f)
                canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 135f, 50f, false, paint)
                val angle = Math.toRadians(185.0)
                stroke(withAlpha(Color.WHITE, 0xC0), h * .004f)
                canvas.drawLine(cx, cy, cx + (cos(angle) * radius * .8f).toFloat(), cy + (sin(angle) * radius * .8f).toFloat(), paint)
            }
            // The cowl's shadow on the glass under it.
            fill(Color.WHITE).shader = vertical(cluster.top, cluster.top + h * .05f, withAlpha(Color.BLACK, 0xE0), withAlpha(Color.BLACK, 0))
            canvas.drawRoundRect(cluster, h * .03f, h * .03f, paint)
        }

        private fun drawVents(canvas: Canvas, c: SceneColors) {
            val ventWidth = screen.width() * .36f
            val ventHeight = h * .04f
            val top = h * .695f
            for (side in floatArrayOf(-1f, 1f)) {
                val cx = screen.centerX() + side * screen.width() * .23f
                val vent = RectF(cx - ventWidth / 2f, top, cx + ventWidth / 2f, top + ventHeight)
                val round = ventHeight / 2f
                fill(c.vent).shader = vertical(vent.top, vent.bottom, Color.BLACK, c.vent)
                canvas.drawRoundRect(vent, round, round, paint)
                for (slat in 1..3) {
                    val y = vent.top + ventHeight * slat / 4f
                    stroke(c.slat, h * .0045f).strokeCap = Paint.Cap.BUTT
                    canvas.drawLine(vent.left + round * .6f, y, vent.right - round * .6f, y, paint)
                    stroke(c.slatHighlight, max(density * .6f, h * .0012f)).strokeCap = Paint.Cap.BUTT
                    canvas.drawLine(vent.left + round * .6f, y - h * .002f, vent.right - round * .6f, y - h * .002f, paint)
                }
                stroke(Color.WHITE, max(density, h * .004f)).shader = vertical(vent.top, vent.bottom, c.chromeTop, c.chromeBottom)
                canvas.drawRoundRect(vent, round, round, paint)
            }
            // A gloss-black strip of climate controls below, softly backlit.
            val panel = RectF(screen.left + screen.width() * .08f, h * .775f, screen.right - screen.width() * .08f, h * .835f)
            fill(c.bezel).shader = vertical(panel.top, panel.bottom, c.trimLit, c.bezel)
            canvas.drawRoundRect(panel, h * .014f, h * .014f, paint)
            fill(Color.WHITE).shader = LinearGradient(panel.left, panel.top, panel.left + panel.width() * .5f, panel.bottom,
                withAlpha(Color.WHITE, c.screenSheen), withAlpha(Color.WHITE, 0), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(panel, h * .014f, h * .014f, paint)
            stroke(c.trimHighlight, max(density * .8f, h * .002f))
            canvas.drawLine(panel.left + h * .014f, panel.top + density * .5f, panel.right - h * .014f, panel.top + density * .5f, paint)
            for (index in 0 until 5) {
                val x = panel.left + panel.width() * (index + .5f) / 5f
                val y = panel.centerY()
                stroke(withAlpha(Color.WHITE, c.controlGlow), max(density, h * .004f)).maskFilter = blur(h * .002f)
                canvas.drawLine(x - h * .01f, y, x + h * .01f, y, paint)
                if (index == 1) {
                    fill(withAlpha(c.ambient, 0xD0)).maskFilter = blur(h * .002f)
                    canvas.drawCircle(x, y + h * .013f, h * .0025f, paint)
                }
            }
        }

        private fun drawScreen(canvas: Canvas, c: SceneColors) {
            val cx = screen.centerX()
            // Its shadow on the dashboard, the stand, and a faint bloom of light round it.
            fill(withAlpha(Color.BLACK, 0xB0)).maskFilter = blur(h * .012f)
            canvas.drawOval(cx - screen.width() * .42f, h * .636f, cx + screen.width() * .42f, h * .66f, paint)
            fill(c.trim).shader = vertical(screen.bottom, h * .65f, c.trimLit, c.trim)
            canvas.drawPath(Path().apply {
                moveTo(cx - h * .05f, screen.bottom)
                lineTo(cx + h * .05f, screen.bottom)
                lineTo(cx + h * .07f, h * .648f)
                lineTo(cx - h * .07f, h * .648f)
                close()
            }, paint)
            fill(withAlpha(c.screenLight, c.bloomAlpha)).maskFilter = blur(h * .03f)
            canvas.drawRoundRect(bezel, bezelCorner, bezelCorner, paint)
            fill(c.bezel)
            canvas.drawRoundRect(bezel, bezelCorner, bezelCorner, paint)
            fill(c.glass).shader = vertical(screen.top, screen.bottom, c.glassTop, c.glass)
            canvas.drawRoundRect(screen, screenCorner, screenCorner, paint)
            fill(Color.WHITE).shader = RadialGradient(cx, screen.centerY(), screen.width() * .6f,
                intArrayOf(withAlpha(c.screenLight, c.screenGlowAlpha), withAlpha(c.screenLight, c.screenGlowAlpha / 4),
                    withAlpha(c.screenLight, 0)), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(screen, screenCorner, screenCorner, paint)
            // A soft reflection from the top corner, and the bezel's edge catching the light.
            fill(Color.WHITE).shader = LinearGradient(screen.left, screen.top, screen.left + screen.width() * .45f,
                screen.top + screen.height() * .8f, withAlpha(Color.WHITE, c.screenSheen), withAlpha(Color.WHITE, 0), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(screen, screenCorner, screenCorner, paint)
            stroke(Color.WHITE, max(density, h * .0028f)).shader = vertical(bezel.top, bezel.bottom, c.chromeTop, c.chromeBottom)
            canvas.drawRoundRect(bezel, bezelCorner, bezelCorner, paint)
        }

        private fun drawWheel(canvas: Canvas, c: SceneColors) {
            val inner = wheelRadius - wheelThickness / 2f
            fun rimPoint(angle: Double, radius: Float) =
                (wheelX + cos(Math.toRadians(angle)) * radius).toFloat() to (wheelY + sin(Math.toRadians(angle)) * radius).toFloat()
            // Two spokes from the hub to the rim, a little below level, with a bright trim along their tops.
            for (side in listOf(1, -1)) {
                val (topX, topY) = rimPoint(if (side < 0) 196.0 else 344.0, inner + wheelThickness * .2f)
                val (bottomX, bottomY) = rimPoint(if (side < 0) 166.0 else 14.0, inner + wheelThickness * .2f)
                val hubEdge = wheelX + side * h * .11f
                val spoke = Path().apply {
                    moveTo(hubEdge, wheelY - h * .045f)
                    cubicTo(hubEdge + side * h * .08f, wheelY - h * .05f, topX - side * h * .06f, topY + h * .005f, topX, topY)
                    lineTo(bottomX, bottomY)
                    cubicTo(bottomX - side * h * .08f, bottomY, hubEdge + side * h * .06f, wheelY + h * .06f, hubEdge, wheelY + h * .06f)
                    close()
                }
                fill(c.wheel).shader = vertical(wheelY - h * .06f, wheelY + h * .07f, c.spokeTop, c.wheel)
                canvas.drawPath(spoke, paint)
                stroke(Color.WHITE, max(density, h * .0035f)).shader = LinearGradient(hubEdge, 0f, topX, 0f,
                    withAlpha(c.chromeTop, 0x40), c.chromeTop, Shader.TileMode.CLAMP)
                canvas.drawPath(Path().apply {
                    moveTo(hubEdge + side * h * .01f, wheelY - h * .035f)
                    cubicTo(hubEdge + side * h * .08f, wheelY - h * .04f, topX - side * h * .06f, topY + h * .014f, topX - side * h * .012f,
                        topY + h * .012f)
                }, paint)
                // Steering-wheel buttons.
                for (button in 0..1) {
                    val bx = hubEdge + side * (h * .045f + button * h * .055f)
                    val by = wheelY - h * .005f
                    fill(c.button).shader = vertical(by - h * .016f, by + h * .016f, c.buttonTop, c.button)
                    canvas.drawRoundRect(bx - h * .021f, by - h * .015f, bx + h * .021f, by + h * .015f, h * .01f, h * .01f, paint)
                }
            }
            // The hub.
            val hub = RectF(wheelX - h * .13f, wheelY - h * .075f, wheelX + h * .13f, wheelY + h * .12f)
            fill(withAlpha(Color.BLACK, 0x90)).maskFilter = blur(h * .012f)
            canvas.drawRoundRect(hub.left - h * .006f, hub.top + h * .006f, hub.right + h * .006f, hub.bottom, h * .07f, h * .07f, paint)
            fill(c.wheel).shader = vertical(hub.top, hub.bottom, c.hubTop, c.wheel)
            canvas.drawRoundRect(hub, h * .07f, h * .07f, paint)
            // The airbag cover's seam.
            stroke(withAlpha(Color.BLACK, 0xA0), max(density, h * .0025f))
            canvas.drawRoundRect(hub.left + h * .025f, hub.top + h * .022f, hub.right - h * .025f, hub.bottom, h * .05f, h * .05f, paint)
            stroke(c.trimHighlight, max(density, h * .0025f)).maskFilter = blur(density * .5f)
            canvas.drawArc(hub.left + h * .02f, hub.top + h * .004f, hub.right - h * .02f, hub.top + h * .14f, 205f, 130f, false, paint)
            // The rim: leather, lit from the windscreen above.
            stroke(c.wheel, wheelThickness).shader = vertical(wheelY - wheelRadius - wheelThickness, wheelY,
                c.rimTop, c.wheel, c.wheel)
            canvas.drawCircle(wheelX, wheelY, wheelRadius, paint)
            val outer = wheelRadius + wheelThickness * .28f
            stroke(c.rimHighlight, wheelThickness * .16f).maskFilter = blur(wheelThickness * .1f)
            canvas.drawArc(wheelX - outer, wheelY - outer, wheelX + outer, wheelY + outer, 222f, 96f, false, paint)
            val lit = wheelRadius - wheelThickness * .36f
            stroke(withAlpha(c.gaugeLeft, 0x30), wheelThickness * .1f).maskFilter = blur(wheelThickness * .08f)
            canvas.drawArc(wheelX - lit, wheelY - lit, wheelX + lit, wheelY + lit, 235f, 70f, false, paint)
        }
    }

    private companion object {
        const val MIN_ASPECT = 1.2f
        const val MAX_ASPECT = 1.7f
        // Places across the road, in eye heights from the driver: the driver keeps to the right lane.
        const val RIGHT_EDGE = 1.6f
        const val CENTRE_LINE = -1.4f
        const val LEFT_EDGE = -4.6f
        const val LINE_HALF_WIDTH = .06f
        val LAMP_SIDES = floatArrayOf(3f, -6.2f)
        const val LAMP_HEIGHT = 5f
        const val CAR_DEPTH = 2.6f
        const val CAR_LATERAL = .1f
        const val NEAREST_DEPTH = .7f
        const val NEAREST_LAMP = .9f
        const val DASH_LENGTH = .9f
        const val DASH_SPACING = 2f
        const val DASHES = 32
        const val LIGHT_SPACING = 8f
        const val LAMPS = 8
        const val LIGHT_PERIOD_MS = 1_900L
    }
}

/** The picture's colours: a dark cabin in both, under a night sky with a town's glow or a day sky. */
internal data class SceneColors(
    val night: Boolean,
    val skyTop: Int,
    val skyMiddle: Int,
    val skyHorizon: Int,
    val horizonGlow: Int,
    val horizonGlowAlpha: Int,
    val cloud: Int,
    val hills: Int,
    val skyline: Int,
    val groundFar: Int,
    val ground: Int,
    val roadFar: Int,
    val road: Int,
    val edgeLine: Int,
    val centreLine: Int,
    val pole: Int,
    val tyre: Int,
    val carBody: Int,
    val carHighlight: Int,
    val carWindowTop: Int,
    val carWindow: Int,
    val tailLight: Int,
    val headliner: Int,
    val headlinerEdge: Int,
    val pillar: Int,
    val pillarLit: Int,
    val trim: Int,
    val trimLit: Int,
    val trimHighlight: Int,
    val mirrorGlassTop: Int,
    val mirrorGlass: Int,
    val dashReflection: Int,
    val glassStreak: Int,
    val dashTopFar: Int,
    val dashTop: Int,
    val dashFaceTop: Int,
    val dashFace: Int,
    val dashFaceBottom: Int,
    val edgeHighlight: Int,
    val stitch: Int,
    val ambient: Int,
    val ambientGlow: Int,
    val ambientSpill: Int,
    val ambientReflection: Int,
    val controlGlow: Int,
    val clusterGlassTop: Int,
    val clusterGlass: Int,
    val gaugeTrack: Int,
    val gaugeLeft: Int,
    val gaugeRight: Int,
    val vent: Int,
    val slat: Int,
    val slatHighlight: Int,
    val chromeTop: Int,
    val chromeBottom: Int,
    val bezel: Int,
    val glassTop: Int,
    val glass: Int,
    val screenLight: Int,
    val screenGlowAlpha: Int,
    val screenSheen: Int,
    val spillAlpha: Int,
    val bloomAlpha: Int,
    val wheel: Int,
    val rimTop: Int,
    val rimHighlight: Int,
    val spokeTop: Int,
    val hubTop: Int,
    val buttonTop: Int,
    val button: Int,
    val vignette: Int,
    val outline: Int,
) {
    companion object {
        fun of(night: Boolean) = if (night) NIGHT else DAY

        private val NIGHT = SceneColors(
            night = true,
            skyTop = Color.rgb(3, 5, 14),
            skyMiddle = Color.rgb(12, 16, 40),
            skyHorizon = Color.rgb(46, 34, 66),
            horizonGlow = Color.rgb(255, 128, 64),
            horizonGlowAlpha = 0x48,
            cloud = Color.TRANSPARENT,
            hills = Color.rgb(14, 13, 26),
            skyline = Color.rgb(8, 8, 16),
            groundFar = Color.rgb(12, 11, 18),
            ground = Color.rgb(5, 5, 8),
            roadFar = Color.rgb(30, 24, 36),
            road = Color.rgb(10, 10, 14),
            edgeLine = withAlpha(Color.WHITE, 0x60),
            centreLine = Color.rgb(255, 204, 0),
            pole = Color.TRANSPARENT,
            tyre = Color.rgb(2, 2, 3),
            carBody = Color.rgb(6, 6, 9),
            carHighlight = Color.rgb(40, 34, 50),
            carWindowTop = Color.rgb(34, 30, 48),
            carWindow = Color.rgb(8, 8, 12),
            tailLight = Color.rgb(255, 40, 40),
            headliner = Color.rgb(13, 13, 16),
            headlinerEdge = Color.rgb(24, 24, 29),
            pillar = Color.rgb(11, 11, 14),
            pillarLit = Color.rgb(30, 30, 36),
            trim = Color.rgb(10, 10, 12),
            trimLit = Color.rgb(34, 34, 40),
            trimHighlight = withAlpha(Color.WHITE, 0x30),
            mirrorGlassTop = Color.rgb(36, 40, 62),
            mirrorGlass = Color.rgb(9, 10, 18),
            dashReflection = withAlpha(Color.rgb(40, 44, 60), 0x40),
            glassStreak = 0x0E,
            dashTopFar = Color.rgb(36, 36, 44),
            dashTop = Color.rgb(13, 13, 16),
            dashFaceTop = Color.rgb(24, 24, 29),
            dashFace = Color.rgb(13, 13, 16),
            dashFaceBottom = Color.rgb(4, 4, 5),
            edgeHighlight = withAlpha(Color.WHITE, 0x3C),
            stitch = withAlpha(Color.rgb(120, 120, 132), 0x90),
            ambient = Color.rgb(64, 156, 255),
            ambientGlow = 0x70,
            ambientSpill = 0x48,
            ambientReflection = 0x50,
            controlGlow = 0x60,
            clusterGlassTop = Color.rgb(2, 3, 6),
            clusterGlass = Color.rgb(7, 9, 16),
            gaugeTrack = withAlpha(Color.WHITE, 0x30),
            gaugeLeft = IosColors.teal(true),
            gaugeRight = IosColors.blue(true),
            vent = Color.rgb(10, 10, 12),
            slat = Color.rgb(30, 30, 35),
            slatHighlight = withAlpha(Color.WHITE, 0x28),
            chromeTop = Color.rgb(150, 150, 160),
            chromeBottom = Color.rgb(36, 36, 42),
            bezel = Color.rgb(2, 2, 3),
            glassTop = Color.rgb(12, 16, 28),
            glass = Color.rgb(3, 5, 10),
            screenLight = IosColors.blue(true),
            screenGlowAlpha = 0x70,
            screenSheen = 0x1C,
            spillAlpha = 0x60,
            bloomAlpha = 0x38,
            wheel = Color.rgb(6, 6, 8),
            rimTop = Color.rgb(48, 48, 54),
            rimHighlight = withAlpha(Color.WHITE, 0x40),
            spokeTop = Color.rgb(36, 36, 42),
            hubTop = Color.rgb(30, 30, 35),
            buttonTop = Color.rgb(40, 40, 46),
            button = Color.rgb(12, 12, 15),
            vignette = 0x70,
            outline = withAlpha(Color.WHITE, 0x18),
        )

        private val DAY = SceneColors(
            night = false,
            skyTop = Color.rgb(46, 128, 222),
            skyMiddle = Color.rgb(118, 178, 238),
            skyHorizon = Color.rgb(214, 232, 246),
            horizonGlow = Color.WHITE,
            horizonGlowAlpha = 0x80,
            cloud = withAlpha(Color.WHITE, 0xB0),
            hills = Color.rgb(132, 158, 184),
            skyline = Color.rgb(140, 160, 182),
            groundFar = Color.rgb(160, 168, 150),
            ground = Color.rgb(104, 112, 96),
            roadFar = Color.rgb(150, 154, 162),
            road = Color.rgb(84, 88, 96),
            edgeLine = withAlpha(Color.WHITE, 0xD0),
            centreLine = Color.rgb(255, 204, 0),
            pole = Color.rgb(96, 100, 110),
            tyre = Color.rgb(20, 20, 22),
            carBody = Color.rgb(54, 60, 72),
            carHighlight = Color.rgb(150, 160, 176),
            carWindowTop = Color.rgb(120, 150, 186),
            carWindow = Color.rgb(28, 32, 42),
            tailLight = Color.rgb(196, 30, 40),
            headliner = Color.rgb(40, 40, 46),
            headlinerEdge = Color.rgb(64, 64, 72),
            pillar = Color.rgb(30, 30, 35),
            pillarLit = Color.rgb(70, 70, 78),
            trim = Color.rgb(22, 22, 26),
            trimLit = Color.rgb(70, 70, 78),
            trimHighlight = withAlpha(Color.WHITE, 0x50),
            mirrorGlassTop = Color.rgb(150, 176, 206),
            mirrorGlass = Color.rgb(40, 48, 62),
            dashReflection = withAlpha(Color.rgb(200, 206, 216), 0x60),
            glassStreak = 0x26,
            dashTopFar = Color.rgb(78, 78, 88),
            dashTop = Color.rgb(28, 28, 33),
            dashFaceTop = Color.rgb(44, 44, 51),
            dashFace = Color.rgb(26, 26, 30),
            dashFaceBottom = Color.rgb(10, 10, 12),
            edgeHighlight = withAlpha(Color.WHITE, 0x60),
            stitch = withAlpha(Color.rgb(150, 150, 160), 0xA0),
            ambient = Color.rgb(64, 156, 255),
            ambientGlow = 0x40,
            ambientSpill = 0x26,
            ambientReflection = 0x20,
            controlGlow = 0x50,
            clusterGlassTop = Color.rgb(4, 5, 8),
            clusterGlass = Color.rgb(14, 16, 24),
            gaugeTrack = withAlpha(Color.WHITE, 0x38),
            gaugeLeft = IosColors.teal(true),
            gaugeRight = IosColors.blue(true),
            vent = Color.rgb(14, 14, 16),
            slat = Color.rgb(40, 40, 46),
            slatHighlight = withAlpha(Color.WHITE, 0x40),
            chromeTop = Color.rgb(200, 200, 208),
            chromeBottom = Color.rgb(60, 60, 68),
            bezel = Color.rgb(4, 4, 6),
            glassTop = Color.rgb(16, 20, 32),
            glass = Color.rgb(5, 7, 12),
            screenLight = IosColors.blue(true),
            screenGlowAlpha = 0x58,
            screenSheen = 0x2C,
            spillAlpha = 0x30,
            bloomAlpha = 0x18,
            wheel = Color.rgb(10, 10, 12),
            rimTop = Color.rgb(70, 70, 78),
            rimHighlight = withAlpha(Color.WHITE, 0x60),
            spokeTop = Color.rgb(54, 54, 62),
            hubTop = Color.rgb(46, 46, 54),
            buttonTop = Color.rgb(60, 60, 68),
            button = Color.rgb(18, 18, 22),
            vignette = 0x50,
            outline = withAlpha(Color.BLACK, 0x14),
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
