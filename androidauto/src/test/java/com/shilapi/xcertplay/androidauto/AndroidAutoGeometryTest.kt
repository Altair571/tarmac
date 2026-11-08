package com.shilapi.xcertplay.androidauto

import com.shilapi.xcertplay.androidauto.aap.AndroidAutoResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAutoGeometryTest {
    @Test fun wideScreenKeepsAndroidAutoInsideAStrip() {
        // A 1920 × 720 head unit: Android Auto draws 1920 × 1080 and keeps its interface in 1920 × 720.
        val geometry = AndroidAutoGeometry.choose(1920, 720, xdpi = 40f)

        assertEquals(AndroidAutoResolution.P1080, geometry.resolution)
        assertEquals(0, geometry.marginWidth)
        assertEquals(360, geometry.marginHeight)
        assertEquals(240, geometry.density)
    }

    @Test fun narrowerScreenLosesWidthInstead() {
        // A 4:3 panel is narrower than Android Auto's 5:3 at 800 × 480: the margin goes to the sides.
        val geometry = AndroidAutoGeometry.choose(1024, 768, xdpi = 160f)

        assertEquals(AndroidAutoResolution.P480, geometry.resolution)
        assertEquals(160, geometry.marginWidth)
        assertEquals(0, geometry.marginHeight)
        // The panel's own density, scaled from its 1024 pixels to Android Auto's 640.
        assertEquals(120, geometry.density)
    }

    @Test fun matchingAspectHasNoMargins() {
        val geometry = AndroidAutoGeometry.choose(1280, 720, xdpi = 0f)

        assertEquals(AndroidAutoResolution.P720, geometry.resolution)
        assertEquals(0, geometry.marginWidth)
        assertEquals(0, geometry.marginHeight)
        assertEquals(180, geometry.density)
    }

    @Test fun driverChoiceWins() {
        assertEquals(AndroidAutoResolution.P480, AndroidAutoGeometry.choose(2560, 1440, 200f, AndroidAutoResolution.P480).resolution)
        assertEquals(280, AndroidAutoGeometry.choose(1280, 720, 160f, preferredDensity = 280).density)
    }

    @Test fun touchesLandInsideTheMargins() {
        val geometry = AndroidAutoGeometry.choose(1920, 720, xdpi = 40f)

        assertEquals(0 to 180, geometry.toVideo(0f, 0f, 1920, 720))
        assertEquals(960 to 540, geometry.toVideo(960f, 360f, 1920, 720))
        assertEquals(1919 to 900, geometry.toVideo(1920f, 720f, 1920, 720))
        // A view at half the size maps to the same video points.
        assertEquals(960 to 540, geometry.toVideo(480f, 180f, 960, 360))
    }
}
