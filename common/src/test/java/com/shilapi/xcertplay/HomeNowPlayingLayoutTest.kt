package com.shilapi.xcertplay

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import kotlin.math.abs
import kotlin.math.roundToInt

/** Home's Now Playing in landscape: a large cover above the song, and every widget on screen without scrolling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeNowPlayingLayoutTest {
    private class Placement(
        val cover: Rect,
        val title: Rect,
        val artist: Rect,
        val widget: Rect,
        val density: Float,
        val visible: Rect,
        val onScreen: Map<Int, Rect?>,
        /** The Navigation widget, while a route runs. */
        val navigation: Rect?,
        /** The shortcuts list with any hint under it. */
        val shortcuts: Rect,
        val scrolls: Boolean,
    )

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        ClusterMusicState.publish(CarPlayNowPlaying(), null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun landscapeStandsALargeCoverAboveTheSong() {
        val screens = listOf(
            "en-w1280dp-h480dp-land-hdpi" to 1f,
            "en-w1280dp-h480dp-land-hdpi" to 1.3f,
            "en-w853dp-h480dp-land-hdpi" to 1f,
            "en-w1024dp-h600dp-land-mdpi" to 1.3f,
            "en-w1280dp-h720dp-land-hdpi" to 1f,
            "en-w868dp-h411dp-land-420dpi" to 1f,
            // Narrower than one row: the compact column, with the cover above the song.
            "en-w683dp-h400dp-land-hdpi" to 1f,
            "ar-ldrtl-w1280dp-h480dp-land-hdpi" to 1f,
        )
        for ((qualifiers, fontScale) in screens) {
            val name = "$qualifiers at font $fontScale"
            with(place(qualifiers, fontScale)) {
                assertEquals("$name: the cover is square", cover.width(), cover.height())
                assertTrue("$name: the cover (${cover.width() / density} dp) is no smaller than beside the song",
                    cover.width() >= (HomeHero.COMPACT_COVER_MIN_DP * density).roundToInt())
                assertTrue("$name: the title is under the cover", title.top >= cover.bottom)
                assertTrue("$name: the artist is under the title", artist.top >= title.bottom)
                assertTrue("$name: the cover stays inside its widget", widget.contains(cover))
                assertTrue("$name: the song stays inside its widget", widget.contains(artist))
                assertTrue("$name: the cover is centred", abs(cover.centerX() - widget.centerX()) <= density.roundToInt() + 1)
            }
        }
    }

    @Test fun headUnitCoverIsLarge() {
        for ((qualifiers, minimumDp) in listOf(
            "en-w1280dp-h480dp-land-hdpi" to HomeHero.COVER_MIN_DP,
            "en-w1024dp-h600dp-land-mdpi" to 160,
            "en-w1280dp-h720dp-land-hdpi" to 220,
        )) {
            with(place(qualifiers, 1f)) {
                assertTrue("$qualifiers: the cover is ${cover.width() / density} dp", cover.width() >= minimumDp * density)
            }
        }
    }

    @Test fun landscapeHomeFitsWithoutScrolling() {
        for (route in listOf(false, true)) for (qualifiers in listOf(
            "en-w1280dp-h480dp-land-hdpi",
            "en-w853dp-h480dp-land-hdpi",
            "en-w800dp-h480dp-land-mdpi",
            "en-w1024dp-h600dp-land-mdpi",
            "en-w1280dp-h720dp-land-hdpi",
            "en-w1333dp-h800dp-land-hdpi",
            "en-w900dp-h400dp-land-xhdpi",
            "en-w868dp-h411dp-land-420dpi",
            "ar-ldrtl-w1280dp-h480dp-land-hdpi",
        )) {
            with(place(qualifiers, 1f, route)) {
                // Between routes nothing scrolls. While one runs, a short, narrow panel keeps every widget on screen
                // but may leave the version line under the row below the fold.
                if (!route) assertTrue("$qualifiers: Home scrolls", !scrolls)
                assertTrue("$qualifiers: the shortcuts are on screen at $shortcuts in $visible", visible.contains(shortcuts))
                for (text in CONTROLS) {
                    val bounds = onScreen[text]
                    assertTrue("$qualifiers: ${RuntimeEnvironment.getApplication().getString(text)} is on screen at $bounds in $visible",
                        bounds != null && visible.contains(bounds))
                }
                assertTrue("$qualifiers: Now Playing is on screen", visible.contains(widget))
                if (route) assertTrue("$qualifiers: Navigation is on screen at $navigation in $visible",
                    navigation != null && visible.contains(navigation))
            }
        }
    }

    @Test fun theThirdColumnEndsWithTheRow() {
        for (qualifiers in listOf("en-w1280dp-h480dp-land-hdpi", "en-w1024dp-h600dp-land-mdpi",
            "en-w1280dp-h720dp-land-hdpi", "en-w868dp-h411dp-land-420dpi")) {
            with(place(qualifiers, 1f)) {
                assertEquals("$qualifiers: between routes Navigation hides", null, navigation)
                assertEquals("$qualifiers: the shortcuts fill the column", widget.bottom, shortcuts.bottom)
            }
            with(place(qualifiers, 1f, route = true)) {
                val navigation = requireNotNull(navigation) { "$qualifiers: Navigation shows during a route" }
                assertEquals("$qualifiers: Navigation fills the column", widget.bottom, navigation.bottom)
                assertTrue("$qualifiers: the shortcuts keep their own height", shortcuts.bottom < navigation.top)
            }
        }
    }

    @Test fun portraitKeepsTheCoverBesideTheSong() {
        for ((qualifiers, coverDp) in listOf("en-w400dp-h900dp-port-xhdpi" to 60, "en-w800dp-h1280dp-port-mdpi" to 92)) {
            with(place(qualifiers, 1f)) {
                assertEquals(qualifiers, (coverDp * density).roundToInt(), cover.width())
                assertEquals(qualifiers, cover.width(), cover.height())
                assertTrue("$qualifiers: the title is beside the cover", title.left >= cover.right)
                assertTrue("$qualifiers: the title is level with the cover", title.top < cover.bottom)
            }
        }
    }

    @Test fun preferredCoverFollowsTheWindowWithinBounds() {
        assertEquals(HomeHero.COVER_MIN_DP, HomeHero.coverPreferredDp(480, compact = false, fillsRow = true))
        assertEquals(HomeHero.COMPACT_COVER_MIN_DP, HomeHero.coverPreferredDp(400, compact = true, fillsRow = true))
        assertEquals(230, HomeHero.coverPreferredDp(720, compact = false, fillsRow = false))
        assertEquals(HomeHero.COVER_MAX_DP, HomeHero.coverPreferredDp(1333, compact = false, fillsRow = false))
        assertEquals(HomeHero.COVER_MIN_DP, HomeHero.coverPreferredDp(200, compact = false, fillsRow = false))
    }

    /** Lays Home out; with [route] the Navigation widget shows as it does while a route runs. */
    private fun place(qualifiers: String, fontScale: Float, route: Boolean = false): Placement {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        ClusterMusicState.publish(
            CarPlayNowPlaying(title = "Midnight City", artist = "M83", album = "Hurry Up, We're Dreaming", playing = true),
            null,
        )
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            val activity = controller.get()
            // A unit-test build has no CarPlay authentication; an installed build shows no setup error under Home.
            ReflectionHelpers.setField(activity, "setupError", null)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
            val navigationWidget = ReflectionHelpers.getField<View>(activity, "homeNavWidget")
            if (route) {
                // What a turn shows: distance, road and arrival.
                navigationWidget.visibility = View.VISIBLE
                ReflectionHelpers.getField<TextView>(activity, "homeNavDistance").text = "300 m"
                ReflectionHelpers.getField<TextView>(activity, "homeNavRoad").apply { text = "Queen Street"; visibility = View.VISIBLE }
                ReflectionHelpers.getField<TextView>(activity, "homeNavEta").apply { text = "Arrive 11:42 · 12 min"; visibility = View.VISIBLE }
            }
            val root = activity.window.decorView
            val metrics = activity.resources.displayMetrics
            val config = activity.resources.configuration
            val width = (config.screenWidthDp * metrics.density).toInt()
            val height = (config.screenHeightDp * metrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            fun bounds(view: View) = IntArray(2).also(view::getLocationInWindow)
                .let { (x, y) -> Rect(x, y, x + view.width, y + view.height) }
            val cover = ReflectionHelpers.getField<ImageView>(activity, "homeMediaArtwork")
            val title = ReflectionHelpers.getField<TextView>(activity, "homeMediaTitle")
            val artist = ReflectionHelpers.getField<TextView>(activity, "homeMediaArtist")
            // The widget is the rounded surface the backdrop fills.
            val widget = ReflectionHelpers.getField<ImageView>(activity, "homeMediaBackdrop").parent as View
            val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
            val page = scroll.getChildAt(0)
            val visible = bounds(scroll).apply { top += scroll.paddingTop; bottom -= scroll.paddingBottom }
            fun find(view: View, text: String): View? = when {
                view is TextView && view.text == text -> view
                view.contentDescription == text -> view
                view is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it), text) }
                else -> null
            }
            return Placement(
                bounds(cover), bounds(title), bounds(artist), bounds(widget), metrics.density, visible,
                onScreen = CONTROLS.associateWith { id -> find(root, activity.getString(id))?.let(::bounds) },
                navigation = navigationWidget.takeIf { it.isShown }?.let(::bounds),
                // The Settings row sits in the list, and the list in the shortcuts column.
                shortcuts = bounds(requireNotNull(find(root, activity.getString(R.string.settings))).parent.parent.parent as View),
                scrolls = page.height > scroll.height - scroll.paddingTop - scroll.paddingBottom,
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private companion object {
        val CONTROLS = listOf(R.string.connect_phone, R.string.connect_with_usb, R.string.settings,
            R.string.home_media_play_pause)
    }
}
