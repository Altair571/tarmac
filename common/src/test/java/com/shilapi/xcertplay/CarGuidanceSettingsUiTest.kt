package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.CarGuidanceSettings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class CarGuidanceSettingsUiTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val screens = mutableListOf<DiPlayActivity>()

    @After fun tearDown() {
        screens.forEach { it.finish() }
        for (name in listOf("diplay_car_guidance", "diplay", "xcertplay_airplay")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun theSwitchLivesUnderNavigationAndIsOffByDefault() {
        val navigation = texts(open(SettingsCategory.NAVIGATION))
        assertTrue(navigation.any { it == app.getString(R.string.settings_car_guidance_switch) })
        assertTrue(navigation.any { it == app.getString(R.string.settings_car_guidance_description) })
        assertFalse(CarGuidanceSettings.enabled(app))
        assertFalse(texts(open(SettingsCategory.ADVANCED)).any { it == app.getString(R.string.settings_car_guidance_switch) })
        assertFalse(texts(open(SettingsCategory.VEHICLE)).any { it == app.getString(R.string.settings_car_guidance_switch) })
    }

    private fun open(category: SettingsCategory): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { screen ->
        screens += screen
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
    }

    private fun texts(screen: DiPlayActivity) =
        descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
