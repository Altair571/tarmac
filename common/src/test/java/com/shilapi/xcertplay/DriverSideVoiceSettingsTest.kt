package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.DriverSideAudio
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Settings → Advanced → Video and audio: driver-side guidance and calls, off until the driver turns it on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class DriverSideVoiceSettingsTest {
    private val app = RuntimeEnvironment.getApplication()

    @After fun tearDown() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun switches(view: View): List<Switch> = when (view) {
        is Switch -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { switches(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun sections(only: Set<SettingsSection>): List<Switch> {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(activity, "settingsSectionFilter", only)
        val content = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
        return switches(content)
    }

    private val title get() = app.getString(R.string.settings_driver_side_voice)

    @Test fun itIsAnAdvancedExperimentOffByDefault() {
        assertTrue(title.endsWith(" (experimental)"))
        val row = sections(setOf(SettingsSection.ADVANCED_MEDIA)).single { it.contentDescription == title }
        assertFalse(row.isChecked)
        assertNull(DriverSideAudio.side(app))
        row.isChecked = true
        assertEquals(true, app.getSharedPreferences("xcertplay_airplay", 0).getBoolean("driver_side_voice", false))
        assertEquals(DriverSideAudio.Side.LEFT, DriverSideAudio.side(app))
    }

    @Test fun itAppearsInNoOtherSection() {
        val others = SettingsSection.entries.toSet() - SettingsSection.ADVANCED_MEDIA
        assertTrue(sections(others).none { it.contentDescription == title })
    }

    @Test fun theDriversSideFollowsTheRightHandDriveSetting() {
        DriverSideAudio.setEnabled(app, true)
        AirPlayPersistence.saveRightHandDrive(app, true)
        assertEquals(DriverSideAudio.Side.RIGHT, DriverSideAudio.side(app))
        AirPlayPersistence.saveRightHandDrive(app, false)
        assertEquals(DriverSideAudio.Side.LEFT, DriverSideAudio.side(app))
    }

    @Test fun theDescriptionSaysWhenItApplies() {
        assertTrue(app.getString(R.string.settings_driver_side_voice_description).endsWith("Applies at the next connection."))
    }
}
