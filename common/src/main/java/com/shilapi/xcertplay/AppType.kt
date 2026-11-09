package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.widget.TextView

/**
 * Tarmac's typeface: Inter, set the way a dashboard is lettered. Titles use the display cut (the font's
 * optical-size axis), which is tighter and sharper at large sizes; figures use open digits, so a 6, a 9
 * and a 4 are told apart at a glance from the driver's seat.
 * Android 7.x cannot set a variable font's axes, so it gets the system font instead.
 */
internal object AppType {
    enum class Weight(val wght: Int, val fallback: String, val fallbackStyle: Int) {
        LIGHT(300, "sans-serif-light", Typeface.NORMAL),
        REGULAR(400, "sans-serif", Typeface.NORMAL),
        MEDIUM(500, "sans-serif-medium", Typeface.NORMAL),
        SEMIBOLD(600, "sans-serif-medium", Typeface.NORMAL),
        BOLD(700, "sans-serif", Typeface.BOLD),
    }

    /** Text from this size up is a title and takes the display cut. */
    const val DISPLAY_FROM_SP = 20f

    /** Open digits everywhere. */
    const val FEATURES = "'ss01'"
    /** Open digits that keep their width, so a clock or a distance does not shift as it counts. */
    const val TABULAR_FEATURES = "'ss01', 'tnum'"

    private const val FONT = "fonts/Inter.ttf"
    private const val DISPLAY_OPTICAL_SIZE = 32
    private val cache = HashMap<Pair<Weight, Boolean>, Typeface>()

    fun of(context: Context, weight: Weight, display: Boolean = false): Typeface = synchronized(cache) {
        cache.getOrPut(weight to display) { load(context.applicationContext ?: context, weight, display) }
    }

    /** Sets [view]'s typeface for its current text size, and Tarmac's figures. Call after `textSize`. */
    fun apply(view: TextView, weight: Weight, tabular: Boolean = false) {
        val sp = view.textSize / view.resources.displayMetrics.scaledDensity
        view.typeface = of(view.context, weight, display = sp >= DISPLAY_FROM_SP)
        view.fontFeatureSettings = if (tabular) TABULAR_FEATURES else FEATURES
    }

    private fun load(context: Context, weight: Weight, display: Boolean): Typeface {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val axes = "'wght' ${weight.wght}" + if (display) ", 'opsz' $DISPLAY_OPTICAL_SIZE" else ""
            runCatching {
                Typeface.Builder(context.assets, FONT).setFontVariationSettings(axes).build()
            }.getOrNull()?.let { return it }
        }
        return Typeface.create(weight.fallback, weight.fallbackStyle)
    }
}
