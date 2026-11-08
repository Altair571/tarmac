package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class SecondDisplayMapTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var added: Int? = null
    private var activity: DiPlayActivity? = null

    @Before fun reset() = clear()

    @After fun tearDown() {
        activity?.finish()
        added?.let(ShadowDisplayManager::removeDisplay)
        clear()
    }

    private fun clear() {
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_second_display_map", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun onlyAnotherPublicScreenLargeEnoughForAMapCanBeChosen() {
        val cluster = SecondDisplayMap.Candidate(2, "HDMI", 1920, 720, Display.FLAG_PRESENTATION)
        assertTrue(SecondDisplayMap.usable(cluster))
        assertFalse(SecondDisplayMap.usable(cluster.copy(id = Display.DEFAULT_DISPLAY)))
        assertFalse(SecondDisplayMap.usable(cluster.copy(flags = Display.FLAG_PRIVATE)))
        assertFalse(SecondDisplayMap.usable(cluster.copy(valid = false)))
        assertFalse(SecondDisplayMap.usable(cluster.copy(height = SecondDisplayMap.MIN_EDGE_PIXELS - 1)))
        // A screen that reconnected under a new id is the same choice.
        assertEquals(listOf(cluster), SecondDisplayMap.candidates(listOf(cluster, cluster.copy(id = 7))))
        assertEquals(9, SecondDisplayMap.match(listOf(cluster.copy(id = 9)), cluster.key)?.id)
        assertNull(SecondDisplayMap.match(listOf(cluster.copy(width = 1280)), cluster.key))
    }

    @Test fun theNameInAKeyMayContainTheSeparator() {
        assertEquals("Rear | screen", SecondDisplayMap.nameOf(SecondDisplayMap.key("Rear | screen", 1280, 720)))
    }

    @Test fun anExtraScreenIsNeverUsedUntilTheDriverChoosesIt() {
        val display = addScreen("Instrument cluster", "w1280dp-h480dp-mdpi")
        assertNull(ClusterMapPresentation.findDisplay(app))

        SecondDisplayMap.saveChoice(app, SecondDisplayMap.candidate(display).key)
        assertEquals(display.displayId, ClusterMapPresentation.findDisplay(app)?.displayId)
        assertTrue(SecondDisplayMap.isChosen(app, display))
        assertFalse(SecondDisplayMap.usesActivity(app, display))
        SecondDisplayMap.saveRoute(app, SecondDisplayMap.Route.ACTIVITY)
        assertTrue(SecondDisplayMap.usesActivity(app, display))
    }

    @Test fun bydRoutesKeepTheClusterWhenTheyAreOn() {
        val display = addScreen("Instrument cluster", "w1280dp-h480dp-mdpi")
        SecondDisplayMap.saveChoice(app, SecondDisplayMap.candidate(display).key)
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        assertTrue(SecondDisplayMap.blocked(app))
        assertNull(SecondDisplayMap.find(app))
        AirPlayPersistence.saveAdbClusterEnabled(app, false)
        AirPlayPersistence.saveLegacyClusterEnabled(app, true)
        assertNull(SecondDisplayMap.find(app))
    }

    @Test fun theStreamIsSizedForTheChosenScreenInsteadOfTheVirtualCard() {
        val display = addScreen("Instrument cluster", "w1280dp-h480dp-mdpi")
        SecondDisplayMap.saveChoice(app, SecondDisplayMap.candidate(display).key)
        AirPlayPersistence.saveClusterMapEnabled(app, true)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val config = CarPlayHostActivity::class.java.getDeclaredMethod("clusterDisplayConfig")
            .apply { isAccessible = true }.invoke(host) as AirPlayDisplayConfig
        // 83 % of the 1280x480 panel, keeping its 8:3 aspect, not the 1280x720 virtual stream.
        assertEquals(400, config.heightPixels)
        assertEquals(1064, config.widthPixels)
        assertEquals(CarPlayClusterDisplay.MAP_URL, config.initialUrl)
    }

    @Test fun advancedSettingsOfferTheDetectedScreen() {
        addScreen("Instrument cluster", "w1280dp-h480dp-mdpi")
        val screen = openAdvanced()
        val title = screen.getString(R.string.settings_dashboard_display)
        val picker = texts(screen).single { it.text.startsWith("$title · ") }
        assertTrue(picker.text.endsWith(screen.getString(R.string.settings_dashboard_display_none)))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_dashboard_display_description) })
        // The route only matters once a screen is chosen.
        assertFalse(texts(screen).any { it.text.startsWith(screen.getString(R.string.settings_dashboard_display_route)) })
    }

    @Test fun advancedSettingsHideThePickerWithoutASecondScreen() {
        val screen = openAdvanced()
        assertFalse(texts(screen).any { it.text.startsWith(screen.getString(R.string.settings_dashboard_display)) })
    }

    private fun addScreen(name: String, qualifiers: String): Display {
        val id = ShadowDisplayManager.addDisplay(qualifiers).also { added = it }
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).apply {
            setName(name)
            setFlags(Display.FLAG_PRESENTATION)
        }
        return display
    }

    private fun openAdvanced(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { screen ->
        activity = screen
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.ADVANCED))
    }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
