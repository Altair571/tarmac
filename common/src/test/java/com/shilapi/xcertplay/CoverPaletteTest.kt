package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverPaletteTest {
    private fun cover(vararg colors: Int) = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until 60) for (x in 0 until 60) setPixel(x, y, colors[(y * colors.size) / 60])
    }

    private fun hsv(color: Int) = FloatArray(3).also { Color.colorToHSV(color, it) }

    @Test fun aLoudCoverGivesCalmColours() {
        for (night in listOf(true, false)) {
            for (color in CoverPalette.of(cover(Color.rgb(255, 45, 149), Color.rgb(255, 224, 0)), night)) {
                val (_, saturation, value) = hsv(color).toList()
                // 8-bit RGB rounding moves the clamped value by up to about 0.01.
                assertTrue("saturation $saturation", saturation <= 0.63f)
                if (night) assertTrue("value $value", value in 0.39f..0.61f) else assertTrue("value $value", value >= 0.81f)
            }
        }
    }

    @Test fun aGreyOrBlackCoverStaysNeutral() {
        for (color in CoverPalette.of(cover(Color.BLACK, Color.rgb(40, 40, 40)), night = true)) {
            assertTrue(hsv(color)[1] <= 0.1f)
        }
    }

    @Test fun twoHuesGiveTwoDifferentColours() {
        val (first, second) = CoverPalette.of(cover(Color.rgb(200, 30, 30), Color.rgb(30, 60, 200)), night = true)
        val distance = kotlin.math.abs(hsv(first)[0] - hsv(second)[0])
        assertTrue("hue distance $distance", minOf(distance, 360 - distance) >= 35f)
    }
}
