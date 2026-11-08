package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.ScrollView
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/** Draws every setup guide step at head-unit and phone sizes into common/build/reports/setup-guide-ui. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupGuideScreenshotsTest {
    private data class Screen(val name: String, val qualifiers: String, val fontScale: Float = 1f)

    private val app = RuntimeEnvironment.getApplication()

    @After fun tearDown() {
        app.applicationInfo.flags = app.applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL.inv()
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun writesEveryStep() {
        val screens = listOf(
            Screen("head-unit-1920x720", "en-w1280dp-h480dp-land-hdpi"),
            Screen("head-unit-1920x720-font-1.3", "en-w1280dp-h480dp-land-hdpi", 1.3f),
            Screen("head-unit-1024x600", "en-w1024dp-h600dp-land-mdpi"),
            Screen("head-unit-1920x1080-arabic", "ar-ldrtl-w1280dp-h720dp-land-hdpi"),
            Screen("phone-portrait", "en-w400dp-h900dp-port-xhdpi"),
            Screen("phone-portrait-font-1.3", "en-w400dp-h900dp-port-xhdpi", 1.3f),
        )
        for (screen in screens) {
            for (step in SetupGuide.STEP_CAR..SetupGuide.STEP_DONE) capture(screen, step)
            capture(screen, SetupGuide.STEP_DONE, ready = true)
        }
    }

    /** [ready] saves an iPhone and Wi-Fi Direct, so the last step has nothing left to finish. */
    private fun capture(screen: Screen, step: Int, ready: Boolean = false) {
        RuntimeEnvironment.setQualifiers(screen.qualifiers)
        RuntimeEnvironment.setFontScale(screen.fontScale)
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AirPlayPersistence.saveAppAppearance(app, AppAppearance.DARK)
        // The installed apps declare supportsRtl; this library's test manifest does not, so mirror like they do.
        app.applicationInfo.flags = if (screen.name.contains("arabic")) app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            else app.applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL.inv()
        // The car step on a phone is skipped unless a version is confirmed, so confirm one for the head-unit shots.
        DiLinkGeneration.save(app, DiLinkGeneration.DILINK_3)
        if (ready) {
            DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:FF", "Test iPhone")
            AirPlayPersistence.saveWirelessHotspotMode(app, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.WIFI_P2P)
        }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "setup")).setup()
        try {
            val activity = controller.get()
            ReflectionHelpers.setField(activity, "setupError", null)
            ReflectionHelpers.setField(activity, "setupStep", step)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
            val name = "${screen.name}-step-$step${if (ready) "-ready" else ""}"
            draw(activity, name, full = false)
            draw(activity, "$name-full", full = true)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** [full] draws the whole scrolled page; otherwise only what fits on the screen. */
    private fun draw(activity: DiPlayActivity, name: String, full: Boolean) {
        val root = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val config = activity.resources.configuration
        val width = (config.screenWidthDp * metrics.density).toInt()
        val screenHeight = (config.screenHeightDp * metrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, screenHeight)
        val scroll = ReflectionHelpers.getField<ScrollView?>(activity, "rootScroll")
        // The footer stays outside the scroll, so the page grows by what the scroll hides.
        val height = if (full && scroll != null) {
            screenHeight + maxOf(0, scroll.getChildAt(0).height + scroll.paddingTop + scroll.paddingBottom - scroll.height)
        } else screenHeight
        if (height != screenHeight) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val output = reportDirectory().resolve("$name.png")
            Files.createDirectories(output.parent)
            Files.newOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun reportDirectory(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val module = if (workingDirectory.fileName.toString() == "common") workingDirectory else workingDirectory.resolve("common")
        return module.resolve("build/reports/setup-guide-ui")
    }
}
