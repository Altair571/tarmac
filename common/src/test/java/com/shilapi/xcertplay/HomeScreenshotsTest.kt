package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * Draws Home at head-unit and phone sizes into common/build/reports/home-ui, and checks that the
 * CarPlay and USB controls are on screen without scrolling at each size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenshotsTest {
    /** With [route] the Navigation widget shows, as it does while a route runs. */
    private data class Screen(val name: String, val qualifiers: String, val fontScale: Float = 1f, val route: Boolean = false)

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
        RuntimeEnvironment.getApplication().getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ClusterMusicState.publish(CarPlayNowPlaying(), null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun writesHomeAtHeadUnitAndPhoneSizes() {
        val screens = listOf(
            Screen("head-unit-1920x720", "en-w1280dp-h480dp-land-hdpi"),
            Screen("head-unit-1920x720-font-1.3", "en-w1280dp-h480dp-land-hdpi", 1.3f),
            Screen("head-unit-1920x720-arabic", "ar-ldrtl-w1280dp-h480dp-land-hdpi"),
            Screen("head-unit-1024x600", "en-w1024dp-h600dp-land-mdpi"),
            Screen("head-unit-1024x600-font-1.3", "en-w1024dp-h600dp-land-mdpi", 1.3f),
            Screen("head-unit-1024x600-route", "en-w1024dp-h600dp-land-mdpi", route = true),
            Screen("head-unit-1920x720-route", "en-w1280dp-h480dp-land-hdpi", route = true),
            Screen("head-unit-1920x1080", "en-w1280dp-h720dp-land-hdpi"),
            Screen("head-unit-1920x1080-font-1.3", "en-w1280dp-h720dp-land-hdpi", 1.3f),
            // 1280 × 720 at 240 dpi and 800 × 480 at 160 dpi: short panels whose row of three is narrow too.
            Screen("head-unit-1280x720-hdpi", "en-w853dp-h480dp-land-hdpi"),
            Screen("head-unit-800x480", "en-w800dp-h480dp-land-mdpi"),
            Screen("head-unit-1920x1080-arabic", "ar-ldrtl-w1280dp-h720dp-land-hdpi"),
            Screen("tablet-portrait", "en-w800dp-h1280dp-port-mdpi"),
            Screen("phone-landscape", "en-w900dp-h400dp-land-xhdpi"),
            Screen("phone-landscape-font-1.3", "en-w900dp-h400dp-land-xhdpi", 1.3f),
            Screen("phone-landscape-420dpi", "en-w868dp-h411dp-land-420dpi"),
            Screen("phone-portrait", "en-w400dp-h900dp-port-xhdpi"),
            Screen("phone-portrait-font-1.3", "en-w400dp-h900dp-port-xhdpi", 1.3f),
            Screen("head-unit-1920x720-light", "en-w1280dp-h480dp-land-notnight-hdpi"),
            Screen("head-unit-1920x1080-accent-pink", "en-w1280dp-h720dp-land-hdpi"),
        )
        for (screen in screens) {
            for (playing in listOf(false, true)) capture(screen, playing)
        }
    }

    @Test fun writesSettingsAtHeadUnitAndPhoneSizes() {
        val screens = listOf(
            Screen("settings-head-unit-1920x1080", "en-w1280dp-h720dp-land-hdpi"),
            Screen("settings-head-unit-1920x720", "en-w1280dp-h480dp-land-hdpi"),
            Screen("settings-phone-portrait", "en-w400dp-h900dp-port-xhdpi"),
        )
        for (screen in screens) {
            for (category in listOf(SettingsCategory.OVERVIEW, SettingsCategory.DISPLAY)) {
                RuntimeEnvironment.setQualifiers(screen.qualifiers)
                AirPlayPersistence.saveAppAppearance(RuntimeEnvironment.getApplication(), AppAppearance.DARK)
                val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                    android.content.Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java).putExtra("page", "settings")).setup()
                try {
                    val activity = controller.get()
                    org.robolectric.util.ReflectionHelpers.setField(activity, "page", "settings")
                    org.robolectric.util.ReflectionHelpers.setField(activity, "settingsCategory", category)
                    org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
                    draw(activity, "${screen.name}-${category.name.lowercase()}")
                    if (category == SettingsCategory.DISPLAY) {
                        // The Home screen card sits below Display and performance: scroll it to the top.
                        val title = descendants(activity.window.decorView).filterIsInstance<android.widget.TextView>()
                            .first { it.text == activity.getString(R.string.settings_home_screen) }
                        val scroll = org.robolectric.util.ReflectionHelpers.getField<android.widget.ScrollView>(activity, "rootScroll")
                        var top = 0
                        var view: View = title
                        while (view !== scroll && view.parent is View) { top += view.top; view = view.parent as View }
                        scroll.scrollTo(0, top - (40 * activity.resources.displayMetrics.density).toInt())
                        draw(activity, "${screen.name}-home-screen-card")
                    }
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        }
    }

    /** Home as a driver sees it once CarPlay is ready, for the README: no test-only setup warnings. */
    @Test fun writesReadmeImages() {
        val app = RuntimeEnvironment.getApplication()
        for ((name, qualifiers) in listOf("readme-head-unit" to "en-w1280dp-h720dp-land-hdpi", "readme-phone" to "en-w400dp-h880dp-port-xhdpi")) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            AirPlayPersistence.saveAppAppearance(app, AppAppearance.DARK)
            AirPlayPersistence.saveWirelessHotspotMode(app, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.WIFI_P2P)
            ClusterMusicState.publish(CarPlayNowPlaying(title = "Night Drive", artist = "Tarmac", album = "Demo", playing = true), calmTestCover())
            shadowOf(Looper.getMainLooper()).idle()
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
            try {
                val activity = controller.get()
                org.robolectric.util.ReflectionHelpers.setField(activity, "setupError", null)
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
                draw(activity, name)
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    private fun calmTestCover() = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until 64) for (x in 0 until 64) {
            setPixel(x, y, android.graphics.Color.rgb(30 + x, 90 + y, 160 + (x + y) / 2))
        }
    }

    @Test fun writesTheCarButtonStyles() {
        val context = RuntimeEnvironment.getApplication()
        for (style in CarButtonStyle.entries.filter { it != CarButtonStyle.BYD }) {
            val output = reportDirectory().resolve("car-button-${style.key}.png")
            Files.createDirectories(output.parent)
            Files.write(output, CarButtonStyles.render(context, style))
        }
    }

    private fun draw(activity: DiPlayActivity, name: String) {
        val root = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val config = activity.resources.configuration
        val width = (config.screenWidthDp * metrics.density).toInt()
        val height = (config.screenHeightDp * metrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            descendants(root).forEach(View::invalidate)
            root.draw(Canvas(bitmap))
            val output = reportDirectory().resolve("$name.png")
            Files.createDirectories(output.parent)
            Files.newOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun capture(screen: Screen, playing: Boolean) {
        RuntimeEnvironment.setQualifiers(screen.qualifiers)
        RuntimeEnvironment.setFontScale(screen.fontScale)
        AirPlayPersistence.saveAppAppearance(RuntimeEnvironment.getApplication(),
            if (screen.name.endsWith("-light")) AppAppearance.LIGHT else AppAppearance.DARK)
        AirPlayPersistence.saveAccentTint(RuntimeEnvironment.getApplication(),
            if (screen.name.endsWith("-pink")) AccentTint.PINK else AccentTint.BLUE)
        ClusterMusicState.publish(
            if (playing) CarPlayNowPlaying(title = "Midnight City", artist = "M83", album = "Hurry Up, We're Dreaming", playing = true)
            else CarPlayNowPlaying(),
            if (playing) loudTestCover() else null,
        )
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            val activity = controller.get()
            // A unit-test build has no CarPlay authentication; an installed build shows no setup error under Home.
            org.robolectric.util.ReflectionHelpers.setField(activity, "setupError", null)
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            // Let the cards finish rising into place; the rings keep rippling.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
            if (screen.route) {
                // What a turn shows: distance, road and arrival.
                org.robolectric.util.ReflectionHelpers.getField<View>(activity, "homeNavWidget").visibility = View.VISIBLE
                org.robolectric.util.ReflectionHelpers.getField<TextView>(activity, "homeNavDistance").text = "300 m"
                org.robolectric.util.ReflectionHelpers.getField<TextView>(activity, "homeNavRoad").apply { text = "Queen Street"; visibility = View.VISIBLE }
                org.robolectric.util.ReflectionHelpers.getField<TextView>(activity, "homeNavEta").apply { text = "Arrive 11:42 · 12 min"; visibility = View.VISIBLE }
            }
            val root = activity.window.decorView
            val metrics = activity.resources.displayMetrics
            val config = activity.resources.configuration
            val width = (config.screenWidthDp * metrics.density).toInt()
            val height = (config.screenHeightDp * metrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            fun onScreen(text: Int) {
                val view = descendants(root).filterIsInstance<TextView>().single { it.text == activity.getString(text) }
                val visible = Rect()
                assertTrue("${screen.name}: ${activity.getString(text)} must be visible",
                    view.getGlobalVisibleRect(visible) && visible.height() == view.height)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                descendants(root).forEach(View::invalidate)
                root.draw(Canvas(bitmap))
                val output = reportDirectory().resolve("${screen.name}${if (playing) "-playing" else ""}.png")
                Files.createDirectories(output.parent)
                Files.newOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            // Checked after the picture is saved, so a failure leaves the screen to look at.
            onScreen(R.string.connect_phone)
            onScreen(R.string.connect_with_usb)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** A deliberately loud cover, hot pink over yellow, to show the background stays calm. */
    private fun loudTestCover() = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until 64) for (x in 0 until 64) setPixel(x, y, if (y < 32) android.graphics.Color.rgb(255, 45, 149) else android.graphics.Color.rgb(255, 224, 0))
    }

    private fun reportDirectory(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val module = if (workingDirectory.fileName.toString() == "common") workingDirectory else workingDirectory.resolve("common")
        return module.resolve("build/reports/home-ui")
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
