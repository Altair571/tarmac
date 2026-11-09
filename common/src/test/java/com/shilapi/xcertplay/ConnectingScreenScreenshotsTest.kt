package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/**
 * Draws the CarPlay connecting screen at head-unit and phone sizes into common/build/reports/connecting-screen,
 * and checks that it fits without shrinking, with buttons a driver can hit, at each size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectingScreenScreenshotsTest {
    private data class Screen(
        val name: String,
        val qualifiers: String,
        val fontScale: Float = 1f,
        val night: Boolean = true,
        val rightHandDrive: Boolean = false,
    )

    private enum class State { CONNECTING, RETRY, WIFI_RESET }

    private val activities = mutableListOf<CarPlayHostActivity>()
    private val problems = mutableListOf<String>()

    @After fun tearDown() {
        for (activity in activities) {
            ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
            ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        }
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.getApplication().getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @Test fun writesTheConnectingScreenAtHeadUnitAndPhoneSizes() {
        val screens = listOf(
            Screen("head-unit-1920x720", "en-w1280dp-h480dp-land-hdpi"),
            Screen("head-unit-1920x720-font-1.3", "en-w1280dp-h480dp-land-hdpi", 1.3f),
            Screen("head-unit-1920x720-light", "en-w1280dp-h480dp-land-hdpi", night = false),
            Screen("head-unit-1920x720-right-hand-drive", "en-w1280dp-h480dp-land-hdpi", rightHandDrive = true),
            Screen("head-unit-1280x720", "en-w853dp-h480dp-land-hdpi"),
            Screen("head-unit-1280x720-font-1.3", "en-w853dp-h480dp-land-hdpi", 1.3f),
            Screen("head-unit-1024x600", "en-w1024dp-h600dp-land-mdpi"),
            Screen("head-unit-1024x600-font-1.3", "en-w1024dp-h600dp-land-mdpi", 1.3f),
            Screen("head-unit-1920x1080", "en-w1280dp-h720dp-land-hdpi"),
            Screen("head-unit-1920x1080-arabic", "ar-ldrtl-w1280dp-h720dp-land-hdpi"),
            Screen("head-unit-1920x720-arabic-font-1.3", "ar-ldrtl-w1280dp-h480dp-land-hdpi", 1.3f),
            Screen("tablet-portrait", "en-w800dp-h1280dp-port-mdpi"),
            Screen("phone-landscape", "en-w869dp-h412dp-land-xxhdpi"),
            Screen("phone-portrait", "en-w412dp-h869dp-port-xxhdpi"),
            Screen("phone-portrait-font-1.3", "en-w412dp-h869dp-port-xxhdpi", 1.3f),
            Screen("phone-portrait-light", "en-w412dp-h869dp-port-xxhdpi", night = false),
        )
        for (screen in screens) for (state in State.entries) capture(screen, state)
        // Every screen is drawn before any check fails, so all of them are there to look at.
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** The cabin picture on its own and large, at night, by day, and for a right-hand-drive car. */
    @Test fun writesTheCabinPicture() {
        RuntimeEnvironment.setQualifiers("en-w1280dp-h720dp-land-hdpi")
        val context = RuntimeEnvironment.getApplication()
        for ((name, night, rightHandDrive) in listOf(Triple("night", true, false), Triple("day", false, false),
            Triple("night-right-hand-drive", true, true))) {
            val picture = ConnectingDashboardView(context).apply {
                this.night = night
                this.rightHandDrive = rightHandDrive
            }
            val width = 1400
            val height = 1000
            picture.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            picture.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply { drawColor(if (night) android.graphics.Color.BLACK else android.graphics.Color.rgb(242, 242, 247)) }
                picture.draw(Canvas(bitmap))
                val output = reportDirectory().resolve("picture-$name.png")
                Files.createDirectories(output.parent)
                Files.newOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun capture(screen: Screen, state: State) {
        val app = RuntimeEnvironment.getApplication()
        RuntimeEnvironment.setQualifiers(screen.qualifiers)
        RuntimeEnvironment.setFontScale(screen.fontScale)
        AirPlayPersistence.saveRightHandDrive(app, screen.rightHandDrive)
        CarPlayBackgroundSession.clear()
        // Attach without onCreate: avoid starting sensors, networking or CarPlay sessions.
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get().also(activities::add)
        ReflectionHelpers.setField(activity, "appNight", screen.night)
        // The apps declare right-to-left support; this module's test manifest does not.
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val root = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
            .apply { isAccessible = true }.invoke(activity) as ViewGroup
        // A window would give the views the locale's direction; this detached root takes it from the configuration.
        root.layoutDirection = activity.resources.configuration.layoutDirection
        fun view(name: String) = ReflectionHelpers.getField<View>(activity, name)
        fun stage(text: String) = ReflectionHelpers.callInstanceMethod<Unit>(activity, "setConnectionStage",
            ReflectionHelpers.ClassParameter.from(String::class.java, text), ReflectionHelpers.ClassParameter.from(String::class.java, text))
        when (state) {
            State.CONNECTING -> stage(activity.getString(R.string.connecting_to_your_iphone))
            State.RETRY, State.WIFI_RESET -> {
                stage(activity.getString(R.string.car_hotspot_is_off) + "\n" + activity.getString(R.string.wireless_startup_retries_exhausted))
                view("connectionInstructionsView").visibility = View.GONE
                (view("connectionHelpView") as TextView).apply {
                    text = activity.getString(if (state == State.RETRY) R.string.connection_help_general else R.string.connection_help_reset_wifi)
                    visibility = View.VISIBLE
                }
                view("startupRetryButton").visibility = View.VISIBLE
                if (state == State.WIFI_RESET) view("wifiRecoveryButton").visibility = View.VISIBLE
            }
        }
        val metrics = activity.resources.displayMetrics
        val config = activity.resources.configuration
        val width = (config.screenWidthDp * metrics.density).toInt()
        val height = (config.screenHeightDp * metrics.density).toInt()
        repeat(3) {
            // This detached view has no ViewRoot to schedule the next traversal.
            descendants(root).forEach(View::forceLayout)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
        val name = "${screen.name}${if (state == State.CONNECTING) "" else "-" + state.name.lowercase().replace('_', '-')}"
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
        fun check(ok: Boolean, problem: () -> String) {
            if (!ok) problems += "$name: ${problem()}"
        }
        val viewport = ReflectionHelpers.getField<FrameLayout>(activity, "connectionPanel")
        val panel = viewport.getChildAt(0)
        val top = panel.top + panel.height * (1f - panel.scaleY) / 2f
        check(top >= -1f) { "the panel starts above the screen" }
        check(top + panel.height * panel.scaleY <= viewport.height + 1f) { "the panel ends below the screen" }
        // Connecting fits at full size. A failure's longer text may shrink a little, never its buttons below 56 dp.
        if (state == State.CONNECTING) check(panel.scaleY >= .999f) { "the panel had to shrink to ${panel.scaleY} to fit" }
        // A failure's longer text may take the picture's place; while connecting, the picture stays.
        if (state == State.CONNECTING) {
            val dashboard = ReflectionHelpers.getField<View>(activity, "connectingDashboard")
            check(dashboard.visibility == View.VISIBLE) { "the cabin picture is hidden" }
        }
        for (button in listOf("startupRetryButton", "wifiRecoveryButton").map(::view) + backButton(root, activity)) {
            if (button.visibility != View.VISIBLE) continue
            check(button.height * panel.scaleY >= 56 * metrics.density - 1) {
                "${(button as TextView).text} is ${button.height * panel.scaleY / metrics.density} dp tall"
            }
        }
    }

    private fun backButton(root: View, activity: CarPlayHostActivity) =
        descendants(root).filterIsInstance<TextView>().single { it.text == activity.getString(R.string.back_to_diplay) }

    private fun reportDirectory(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val module = if (workingDirectory.fileName.toString() == "common") workingDirectory else workingDirectory.resolve("common")
        return module.resolve("build/reports/connecting-screen")
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
