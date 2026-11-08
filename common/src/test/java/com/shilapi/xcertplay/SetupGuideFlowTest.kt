package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** How the setup guide moves between steps and what its last step asks for. */
@RunWith(RobolectricTestRunner::class)
// 1920 × 720 at 240 dpi: the short BYD panel where Next used to sit below the fold.
@Config(sdk = [29], qualifiers = "en-w1280dp-h480dp-land-hdpi")
class SetupGuideFlowTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<DiPlayActivity>()

    @Before fun setUp() {
        clearSettings()
        DiLinkGeneration.save(context, DiLinkGeneration.DILINK_3)
    }

    @After fun tearDown() {
        opened.forEach { it.finish() }
        AppAppearanceRuntime.resetForTest()
        clearSettings()
    }

    private fun clearSettings() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun guide(step: Int): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "setup"),
    ).setup().get().also {
        opened += it
        ReflectionHelpers.setField(it, "setupError", null)
        ReflectionHelpers.setField(it, "setupStep", step)
        ReflectionHelpers.callInstanceMethod<Unit>(it, "render")
        layOut(it)
    }

    private fun layOut(screen: DiPlayActivity) {
        val root = screen.window.decorView
        val metrics = screen.resources.displayMetrics
        val config = screen.resources.configuration
        root.measure(View.MeasureSpec.makeMeasureSpec((config.screenWidthDp * metrics.density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((config.screenHeightDp * metrics.density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun texts(screen: DiPlayActivity): List<String> =
        descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun button(screen: DiPlayActivity, text: String): Button =
        descendants(screen.window.decorView).filterIsInstance<Button>().single { it.text.toString() == text }

    private fun onScreen(screen: DiPlayActivity, view: View): Boolean {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val height = screen.window.decorView.height
        return view.isShown && view.height > 0 && location[1] >= 0 && location[1] + view.height <= height
    }

    @Test fun nextIsOnScreenAtEveryStepWithoutScrolling() {
        for (step in SetupGuide.STEP_CAR until SetupGuide.STEP_DONE) {
            val screen = guide(step)
            assertTrue("step $step", onScreen(screen, button(screen, screen.getString(R.string.setup_next))))
            assertTrue("step $step", onScreen(screen, button(screen, screen.getString(R.string.setup_skip))))
        }
        val last = guide(SetupGuide.STEP_DONE)
        assertTrue(onScreen(last, button(last, last.getString(R.string.connect_phone))))
        assertTrue(onScreen(last, button(last, last.getString(R.string.done))))
    }

    @Test fun backAppearsFromTheSecondStepAndReturnsOneStep() {
        val first = guide(SetupGuide.STEP_CAR)
        // Only the header's Back, which leaves the guide from its first step.
        assertEquals(1, texts(first).count { it == first.getString(R.string.back) })
        val screen = guide(SetupGuide.STEP_IPHONE)
        val backs = descendants(screen.window.decorView).filterIsInstance<Button>()
            .filter { it.text.toString() == screen.getString(R.string.back) }.toList()
        assertEquals(2, backs.size)
        backs.last().performClick()
        assertEquals(SetupGuide.STEP_CONNECTION, ReflectionHelpers.getField<Int>(screen, "setupStep"))
    }

    @Test fun nextOpensTheFollowingStepAtItsTop() {
        val screen = guide(SetupGuide.STEP_CONNECTION)
        ReflectionHelpers.getField<android.widget.ScrollView>(screen, "rootScroll").scrollTo(0, 400)
        button(screen, screen.getString(R.string.setup_next)).performClick()
        layOut(screen)
        assertEquals(SetupGuide.STEP_IPHONE, ReflectionHelpers.getField<Int>(screen, "setupStep"))
        assertEquals(0, ReflectionHelpers.getField<android.widget.ScrollView>(screen, "rootScroll").scrollY)
    }

    @Test fun stepsAreNamedUnderTheProgressBar() {
        val shown = texts(guide(SetupGuide.STEP_CONNECTION))
        listOf(R.string.setup_step_car, R.string.setup_connection_title, R.string.setup_step_iphone,
            R.string.setup_features_title, R.string.setup_step_finish).forEach {
            assertTrue(shown.toString(), context.getString(it) in shown)
        }
    }

    @Config(qualifiers = "en-w400dp-h900dp-port-xhdpi")
    @Test fun aPhoneCountsTheStepsInsteadOfNamingThem() {
        val screen = guide(SetupGuide.STEP_CONNECTION)
        val shown = texts(screen)
        assertTrue(shown.toString(), screen.getString(R.string.setup_step_of, 2, 5) in shown)
        assertFalse(screen.getString(R.string.setup_step_finish) in shown)
        assertTrue(onScreen(screen, button(screen, screen.getString(R.string.setup_next))))
    }

    // "Connect phone" wrapped and was clipped in a half-width button, and its row pushed it below Back.
    @Config(qualifiers = "en-w400dp-h900dp-port-xhdpi", fontScale = 1.3f)
    @Test fun aPhoneAtALargeFontKeepsTheFooterOnOneLineAndLevel() {
        val screen = guide(SetupGuide.STEP_DONE)
        val connect = button(screen, screen.getString(R.string.connect_phone))
        val back = descendants(screen.window.decorView).filterIsInstance<Button>()
            .last { it.text.toString() == screen.getString(R.string.back) }
        assertEquals(1, connect.lineCount)
        val connectAt = IntArray(2).also(connect::getLocationInWindow)
        val backAt = IntArray(2).also(back::getLocationInWindow)
        assertEquals(backAt[1], connectAt[1])
        assertEquals(back.height, connect.height)
    }

    @Test fun theConnectionStepLeavesTheExperimentalHotspotRepairToSettings() {
        val screen = guide(SetupGuide.STEP_CONNECTION)
        assertFalse(texts(screen).any { it.startsWith(screen.getString(R.string.hotspot_join_title)) })
        assertFalse(texts(screen).contains(screen.getString(R.string.hotspot_join_check)))
    }

    @Test fun featuresAreGroupedByHowProvenTheyAre() {
        val screen = guide(SetupGuide.STEP_FEATURES)
        val shown = texts(screen)
        assertEquals(1, shown.count { it == screen.getString(R.string.setup_badge_tested) })
        assertEquals(1, shown.count { it == screen.getString(R.string.setup_badge_experimental) })
        // Tested features come first; DiLink 3's call features follow under Experimental.
        assertTrue(shown.indexOf(screen.getString(R.string.setup_badge_tested)) <
            shown.indexOf(screen.getString(R.string.setup_badge_experimental)))
        assertTrue(shown.indexOf(screen.getString(R.string.setup_badge_experimental)) <
            shown.indexOf(screen.getString(R.string.carplay_calls_on_dashboard)))
    }

    @Test fun withoutAnIphoneTheLastStepIsAlmostReadyAndSaysWhy() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        val screen = guide(SetupGuide.STEP_DONE)
        val shown = texts(screen)
        assertTrue(shown.toString(), screen.getString(R.string.setup_almost_ready_title) in shown)
        assertFalse(screen.getString(R.string.setup_done_title) in shown)
        assertTrue(screen.getString(R.string.settings_choose_iphone_description) in shown)
        val iphone = button(screen, screen.getString(R.string.setup_step_iphone) + " · " + screen.getString(R.string.setup_iphone_none))
        iphone.performClick()
        assertEquals(SetupGuide.STEP_IPHONE, ReflectionHelpers.getField<Int>(screen, "setupStep"))
    }

    @Test fun theCarHotspotWithoutDetailsIsNotReady() {
        DiPlayPreferences.savePhone(context, "AA:BB:CC:DD:EE:FF", "Test iPhone")
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        val shown = texts(guide(SetupGuide.STEP_DONE))
        assertTrue(shown.toString(), context.getString(R.string.setup_almost_ready_title) in shown)
        assertTrue(context.getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings) in shown)
    }

    @Test fun withAnIphoneAndWifiDirectTheLastStepIsReady() {
        DiPlayPreferences.savePhone(context, "AA:BB:CC:DD:EE:FF", "Test iPhone")
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        val screen = guide(SetupGuide.STEP_DONE)
        val shown = texts(screen)
        assertTrue(shown.toString(), screen.getString(R.string.setup_done_title) in shown)
        assertTrue(screen.getString(R.string.setup_step_car) + " · " + screen.getString(R.string.dilink_3) in shown)
        assertTrue(screen.getString(R.string.setup_connection_title) + " · " + screen.getString(R.string.wifi_direct) in shown)
        assertTrue(screen.getString(R.string.setup_step_iphone) + " · Test iPhone" in shown)
        assertFalse(screen.getString(R.string.settings_choose_iphone_description) in shown)
    }

    @Test fun nextIsSecondaryUntilAnIphoneIsChosen() {
        val screen = guide(SetupGuide.STEP_IPHONE)
        val next = button(screen, screen.getString(R.string.setup_next))
        val choose = button(screen, screen.getString(R.string.choose_iphone))
        assertTrue(next.currentTextColor != choose.currentTextColor)
    }
}
