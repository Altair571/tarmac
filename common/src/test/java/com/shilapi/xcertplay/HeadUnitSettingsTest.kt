package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class HeadUnitSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun teyesCardsLiveWhereDriversLookForThem() {
        installSystemPackages("com.spd.carinfo", "com.spd.setting")
        val screen = openSettings()
        val title = screen.getString(R.string.settings_head_unit_title, "TEYES")
        val signals = screen.getString(R.string.settings_head_unit_signals_title, "TEYES")
        val wheelKeys = screen.getString(R.string.settings_head_unit_wheel_keys)

        val connection = visibleIn(screen, R.string.connection)
        val vehicle = visibleIn(screen, R.string.settings_vehicle)
        val advanced = visibleIn(screen, R.string.settings_advanced)

        assertTrue(title in connection)
        assertFalse(title in vehicle)
        assertTrue(signals in vehicle)
        assertTrue(wheelKeys in vehicle)
        assertFalse(signals in connection)
        assertFalse(title in advanced || signals in advanced)
        assertTrue(screen.getString(R.string.settings_head_unit_starting_app_off, "TEYES") in connection)
    }

    @Test fun fytGetsBothCardsWithoutAKeySwitch() {
        installSystemPackages("com.syu.ms", "com.syu.canbus")
        val screen = openSettings()
        val connection = visibleIn(screen, R.string.connection)
        val vehicle = visibleIn(screen, R.string.settings_vehicle)

        assertTrue(screen.getString(R.string.settings_head_unit_title, "FYT") in connection)
        assertTrue(screen.getString(R.string.settings_head_unit_signals_title, "FYT") in vehicle)
        assertFalse(screen.getString(R.string.settings_head_unit_wheel_keys) in vehicle)
        // FYT opens no app of its own at car start, so there is nothing to explain.
        assertFalse(screen.getString(R.string.settings_head_unit_starting_app_off, "FYT") in connection)
    }

    @Test fun otherDevicesShowNoHeadUnitCards() {
        val screen = openSettings()
        val connection = visibleIn(screen, R.string.connection)
        val vehicle = visibleIn(screen, R.string.settings_vehicle)
        assertFalse(connection.any { it.startsWith("TEYES") })
        assertFalse(screen.getString(R.string.settings_head_unit_wheel_keys) in vehicle)
    }

    private fun installSystemPackages(vararg names: String) {
        val packages = shadowOf(context.packageManager)
        for (name in names) {
            packages.installPackage(PackageInfo().apply {
                packageName = name
                applicationInfo = ApplicationInfo().apply {
                    packageName = name
                    flags = ApplicationInfo.FLAG_SYSTEM
                }
            })
        }
    }

    private fun openSettings(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get()

    private fun visibleIn(screen: DiPlayActivity, category: Int): List<String> {
        descendants(screen.window.decorView).first { candidate ->
            candidate.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(category))
        }.performClick()
        return descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            .also { screen.onBackPressedDispatcher.onBackPressed() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
