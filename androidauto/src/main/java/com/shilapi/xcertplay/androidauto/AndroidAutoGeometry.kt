// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto

import com.shilapi.xcertplay.androidauto.aap.AndroidAutoResolution
import kotlin.math.roundToInt

/**
 * How Android Auto's fixed video size maps onto this screen. Android Auto draws into one of three
 * sizes; the margins tell it which part of that picture the screen shows, so its interface keeps
 * the screen's shape. The view scales the picture to fill the screen and crops the margins.
 */
class AndroidAutoGeometry internal constructor(
    val resolution: AndroidAutoResolution,
    /** Video pixels Android Auto leaves outside the screen, split evenly on both sides. */
    val marginWidth: Int,
    val marginHeight: Int,
    val density: Int,
) {
    val contentWidth: Int get() = resolution.width - marginWidth
    val contentHeight: Int get() = resolution.height - marginHeight

    /** A touch at ([x], [y]) on a [viewWidth] × [viewHeight] view, in video pixels. */
    fun toVideo(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Pair<Int, Int> {
        val videoX = marginWidth / 2f + x.coerceIn(0f, viewWidth.toFloat()) * contentWidth / viewWidth.coerceAtLeast(1)
        val videoY = marginHeight / 2f + y.coerceIn(0f, viewHeight.toFloat()) * contentHeight / viewHeight.coerceAtLeast(1)
        return videoX.roundToInt().coerceIn(0, resolution.width - 1) to videoY.roundToInt().coerceIn(0, resolution.height - 1)
    }

    companion object {
        /** Below this a reported density is a placeholder, as on many head units, not a measurement. */
        private const val MIN_TRUSTED_DPI = 80f

        /**
         * [screenWidth] × [screenHeight] in pixels, landscape or portrait as the view is; [xdpi] as
         * the display reports it. [preferred] null picks the largest size the screen can show sharply.
         */
        fun choose(
            screenWidth: Int,
            screenHeight: Int,
            xdpi: Float,
            preferred: AndroidAutoResolution? = null,
            /** The driver's density; null works it out. */
            preferredDensity: Int? = null,
        ): AndroidAutoGeometry {
            val width = screenWidth.coerceAtLeast(1)
            val height = screenHeight.coerceAtLeast(1)
            val long = maxOf(width, height)
            val resolution = preferred ?: when {
                long >= 1920 -> AndroidAutoResolution.P1080
                long >= 1280 -> AndroidAutoResolution.P720
                else -> AndroidAutoResolution.P480
            }
            val videoAspect = resolution.width.toFloat() / resolution.height
            val screenAspect = width.toFloat() / height
            var marginWidth = 0
            var marginHeight = 0
            if (screenAspect > videoAspect) {
                marginHeight = (resolution.height - resolution.width / screenAspect).roundToInt()
            } else {
                marginWidth = (resolution.width - resolution.height * screenAspect).roundToInt()
            }
            marginWidth = marginWidth.coerceIn(0, resolution.width - 1)
            marginHeight = marginHeight.coerceIn(0, resolution.height - 1)
            val contentWidth = resolution.width - marginWidth
            val density = if (preferredDensity != null) preferredDensity else if (xdpi >= MIN_TRUSTED_DPI) {
                // The screen's real density, scaled from its pixels to Android Auto's.
                (xdpi * contentWidth / width).roundToInt()
            } else DEFAULT_DENSITY.getValue(resolution)
            return AndroidAutoGeometry(resolution, marginWidth, marginHeight, density.coerceIn(MIN_DENSITY, MAX_DENSITY))
        }

        private const val MIN_DENSITY = 120
        private const val MAX_DENSITY = 320
        private val DEFAULT_DENSITY = mapOf(
            AndroidAutoResolution.P480 to 140,
            AndroidAutoResolution.P720 to 180,
            AndroidAutoResolution.P1080 to 240,
        )
    }
}
