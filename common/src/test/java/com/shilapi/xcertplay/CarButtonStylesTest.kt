package com.shilapi.xcertplay

import android.content.Context
import android.graphics.BitmapFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CarButtonStylesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After fun tearDown() = CarButtonStyles.apply(context, CarButtonStyle.BYD)

    @Test fun bydIsTheDefault() {
        assertEquals(CarButtonStyle.BYD, CarButtonStyles.current(context))
    }

    @Test fun aChosenStyleIsSavedAsTheCarButtonImage() {
        for (style in CarButtonStyle.entries.filter { it != CarButtonStyle.BYD }) {
            CarButtonStyles.apply(context, style)
            assertEquals(style, CarButtonStyles.current(context))
            val image = BitmapFactory.decodeFile(AirPlayPersistence.loadCustomAirPlayIconFile(context)!!.absolutePath)
            assertEquals(256, image.width)
            assertEquals(256, image.height)
        }
    }

    @Test fun anImageFromElsewhereReadsAsTheDriversOwn() {
        CarButtonStyles.apply(context, CarButtonStyle.CAR)
        AirPlayPersistence.saveCustomAirPlayIcon(context, byteArrayOf(1, 2, 3))
        assertNull(CarButtonStyles.current(context))
    }

    @Test fun choosingBydGoesBackToTheShippedLogo() {
        CarButtonStyles.apply(context, CarButtonStyle.WHEEL)
        CarButtonStyles.apply(context, CarButtonStyle.BYD)
        assertNull(AirPlayPersistence.loadCustomAirPlayIconFile(context))
        assertEquals(CarButtonStyle.BYD, CarButtonStyles.current(context))
    }
}
