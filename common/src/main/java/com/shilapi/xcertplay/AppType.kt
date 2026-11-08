package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import java.util.EnumMap

/**
 * Tarmac's typeface: Inter, the open font closest to the iOS system font, in the weights iOS uses.
 * Android 7.x cannot set a variable font's weight, so it gets the system font instead.
 */
internal object AppType {
    enum class Weight(val wght: Int, val fallback: String, val fallbackStyle: Int) {
        LIGHT(300, "sans-serif-light", Typeface.NORMAL),
        REGULAR(400, "sans-serif", Typeface.NORMAL),
        MEDIUM(500, "sans-serif-medium", Typeface.NORMAL),
        SEMIBOLD(600, "sans-serif-medium", Typeface.NORMAL),
        BOLD(700, "sans-serif", Typeface.BOLD),
    }

    private const val FONT = "fonts/Inter.ttf"
    private val cache = EnumMap<Weight, Typeface>(Weight::class.java)

    fun of(context: Context, weight: Weight): Typeface = synchronized(cache) {
        cache.getOrPut(weight) { load(context.applicationContext ?: context, weight) }
    }

    private fun load(context: Context, weight: Weight): Typeface {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                Typeface.Builder(context.assets, FONT).setFontVariationSettings("'wght' ${weight.wght}").build()
            }.getOrNull()?.let { return it }
        }
        return Typeface.create(weight.fallback, weight.fallbackStyle)
    }
}
