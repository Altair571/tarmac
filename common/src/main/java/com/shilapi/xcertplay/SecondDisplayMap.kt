package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.lang.ref.WeakReference

/**
 * The dashboard map on a screen the driver picks, for head units without BYD's or Geely's own cluster route.
 *
 * Some head units drive a second screen from Android: a digital instrument cluster, a passenger or rear screen,
 * or an HDMI output. `DisplayManager` lists it as another display. DiPlay never picks one by itself, because a
 * passenger screen and a cluster look alike to Android; the driver chooses it in Settings. The map then shows
 * there as an Android `Presentation` (as on BYD and Geely) or, for screens that show no presentation window,
 * as an app window started with `ActivityOptions.setLaunchDisplayId`. See docs/SECOND_DISPLAY_MAP.md.
 *
 * BYD's own routes keep the cluster: with ADB task routing, the platform-21 route or the DiLink 5.1 layout the
 * choice is not used, and a display that the built-in detection already recognizes is never offered.
 */
internal object SecondDisplayMap {
    private const val PREFS = "diplay_second_display_map"
    private const val KEY_DISPLAY = "display"
    private const val KEY_ROUTE = "route"

    /** A display smaller than this on its short edge is a status strip or a test display, not a map screen. */
    internal const val MIN_EDGE_PIXELS = 200

    enum class Route { PRESENTATION, ACTIVITY }

    /** A display as `DisplayManager` reports it, without Android types so the choice is unit-tested. */
    data class Candidate(
        val id: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val flags: Int,
        val valid: Boolean = true,
    ) {
        /** Display ids change when a screen reconnects or the head unit boots; name and size do not. */
        val key: String get() = key(name, width, height)
    }

    fun key(name: String, width: Int, height: Int): String = "$name|${width}x$height"

    /** The display name inside [key]; a name may itself contain the separator, the size never does. */
    fun nameOf(key: String): String = key.substringBeforeLast('|')

    /** Not the main screen, not private to another app, and large enough to hold a map. */
    fun usable(candidate: Candidate): Boolean = candidate.id != Display.DEFAULT_DISPLAY && candidate.valid &&
        (candidate.flags and Display.FLAG_PRIVATE) == 0 &&
        minOf(candidate.width, candidate.height) >= MIN_EDGE_PIXELS

    /** The screens the driver can choose, once each. */
    fun candidates(all: List<Candidate>): List<Candidate> = all.filter(::usable).distinctBy { it.key }

    fun match(all: List<Candidate>, key: String?): Candidate? =
        key?.let { wanted -> candidates(all).firstOrNull { it.key == wanted } }

    fun chosenKey(context: Context): String? = prefs(context).getString(KEY_DISPLAY, null)

    /** Applies at the next connection: the stream is sized for the chosen screen during the handshake. */
    fun saveChoice(context: Context, key: String?) {
        prefs(context).edit().apply { if (key == null) remove(KEY_DISPLAY) else putString(KEY_DISPLAY, key) }.apply()
    }

    /** The app-window route needs `setLaunchDisplayId`, which Android 8.0 added. */
    fun activityRouteAvailable(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.O

    fun route(context: Context): Route {
        val saved = Route.entries.firstOrNull { it.name == prefs(context).getString(KEY_ROUTE, null) }
            ?: Route.PRESENTATION
        return if (saved == Route.ACTIVITY && !activityRouteAvailable()) Route.PRESENTATION else saved
    }

    fun saveRoute(context: Context, route: Route) {
        prefs(context).edit().putString(KEY_ROUTE, route.name).apply()
    }

    /** BYD's routes own the cluster there. Reads the settings directly: [AdbClusterRouter] asks [find]. */
    fun blocked(context: Context): Boolean = DiLink51ClusterLayout.supported() ||
        AirPlayPersistence.loadAdbClusterEnabled(context) || AirPlayPersistence.loadLegacyClusterEnabled(context)

    fun candidate(display: Display): Candidate {
        val size = ClusterMapPresentation.sizeOf(display)
        return Candidate(display.displayId, display.name, size.x, size.y, display.flags, display.isValid)
    }

    fun detected(context: Context): List<Candidate> = candidates(displays(context).map(::candidate))

    /** The chosen screen, when it is connected now and no BYD route owns the cluster. */
    fun find(context: Context): Display? {
        if (blocked(context)) return null
        val key = chosenKey(context) ?: return null
        return displays(context).firstOrNull { display -> candidate(display).let { usable(it) && it.key == key } }
    }

    fun isChosen(context: Context, display: Display): Boolean =
        !blocked(context) && candidate(display).key == chosenKey(context)

    fun usesActivity(context: Context, display: Display): Boolean =
        route(context) == Route.ACTIVITY && isChosen(context, display)

    fun report(context: Context): String = buildString {
        append("choice=${chosenKey(context) ?: "none"} route=${route(context)} blocked=${blocked(context)}")
        append(" connected=${find(context)?.displayId ?: "none"}")
        append(" activityWindow=${SecondDisplayMapOutput.report()}")
    }

    private fun displays(context: Context): List<Display> =
        context.getSystemService(DisplayManager::class.java)?.displays?.toList().orEmpty()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Connects [SecondDisplayMapActivity] to the CarPlay screen, as [ClusterActivityOutput] does for BYD's ADB route.
 * Main thread only.
 */
internal object SecondDisplayMapOutput {
    private const val TAG = "DiPlay-SecondDisplay"
    internal const val EXTRA_DISPLAY = "second_display_id"

    /** A start that has not shown its window yet is not repeated sooner than this. */
    private const val LAUNCH_RETRY_MILLIS = 3_000L

    private var activity = WeakReference<SecondDisplayMapActivity>(null)
    private var listener: ((Surface?) -> Unit)? = null
    private var launchedAtMillis = 0L
    private var launchedDisplay = Display.INVALID_DISPLAY
    internal var surface: Surface? = null
        private set
    internal var streamActive = false
        private set
    internal var turnCard: TurnCard = TurnCard()
        private set

    data class TurnCard(
        val guidance: ClusterTurnGuidance? = null,
        val xPercent: Int = 50,
        val yPercent: Int = 40,
        val sizePercent: Int = 100,
        val opacityPercent: Int = 100,
        val night: Boolean = true,
    )

    /** [onSurface] gets the map surface whenever it appears or goes away. */
    fun bind(onSurface: (Surface?) -> Unit) {
        listener = onSurface
        surface?.let(onSurface)
    }

    /** Starts the window on [displayId] unless it is already there; false when the system refused. */
    fun ensure(context: Context, displayId: Int, now: Long = SystemClock.uptimeMillis()): Boolean {
        val open = activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (open != null && open.intendedDisplay == displayId) return true
        if (launchedDisplay == displayId && now - launchedAtMillis < LAUNCH_RETRY_MILLIS) return true
        if (!SecondDisplayMap.activityRouteAvailable()) return false
        open?.finish()
        val intent = Intent(context, SecondDisplayMapActivity::class.java)
            .putExtra(EXTRA_DISPLAY, displayId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return try {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
            launchedAtMillis = now
            launchedDisplay = displayId
            Log.i(TAG, "map window requested on display $displayId")
            true
        } catch (error: RuntimeException) {
            // SecurityException for a display this app may not use, or no such display any more.
            Log.w(TAG, "map window refused on display $displayId", error)
            false
        }
    }

    fun stop() {
        listener = null
        launchedDisplay = Display.INVALID_DISPLAY
        val open = activity.get()
        activity.clear()
        surface = null
        streamActive = false
        open?.finish()
    }

    fun setStreamActive(active: Boolean) {
        if (streamActive == active) return
        streamActive = active
        activity.get()?.update()
    }

    fun setTurnCard(card: TurnCard) {
        if (turnCard == card) return
        turnCard = card
        activity.get()?.update()
    }

    /** Whether [candidate] is the surface of a window that is on the chosen screen now. */
    fun presents(candidate: Surface?): Boolean = candidate != null && candidate === surface &&
        activity.get()?.let { !it.isFinishing && !it.isDestroyed } == true

    fun showing(): Boolean = activity.get()?.let { !it.isFinishing && !it.isDestroyed } == true

    internal fun accepts(window: SecondDisplayMapActivity): Boolean = listener != null &&
        window.intendedDisplay != Display.INVALID_DISPLAY

    internal fun attached(window: SecondDisplayMapActivity) {
        activity.get()?.takeIf { it !== window }?.finish()
        activity = WeakReference(window)
        launchedDisplay = Display.INVALID_DISPLAY
    }

    internal fun onSurface(window: SecondDisplayMapActivity, next: Surface?) {
        if (activity.get() !== window) return
        if (surface === next) return
        surface = next
        listener?.invoke(next)
    }

    internal fun detached(window: SecondDisplayMapActivity) {
        if (activity.get() !== window) return
        activity.clear()
        if (surface != null) {
            surface = null
            listener?.invoke(null)
        }
    }

    fun report(): String = activity.get()?.let { "display=${it.intendedDisplay} surface=${surface != null}" } ?: "none"
}

/**
 * The dashboard map as an ordinary app window on the chosen screen. It never takes input focus, so the wheel
 * keys and the touch screen stay with CarPlay on the main display. It closes itself when Android moves it to
 * another screen, as it does when its screen disconnects, so the map never lands on the centre screen.
 */
class SecondDisplayMapActivity : Activity() {
    internal var intendedDisplay = Display.INVALID_DISPLAY
        private set
    private var waiting: View? = null
    private var turnCard: ClusterTurnCardView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intendedDisplay = intent.getIntExtra(SecondDisplayMapOutput.EXTRA_DISPLAY, Display.INVALID_DISPLAY)
        if (!onIntendedDisplay() || !SecondDisplayMapOutput.accepts(this)) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val dark = resolveAppNightNow()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = SurfaceView(this)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = SecondDisplayMapOutput.onSurface(this@SecondDisplayMapActivity, holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            // SurfaceHolder owns this surface; it is not released here.
            override fun surfaceDestroyed(holder: SurfaceHolder) = SecondDisplayMapOutput.onSurface(this@SecondDisplayMapActivity, null)
        })
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        val density = resources.displayMetrics.density
        waiting = FrameLayout(this).apply {
            setBackgroundColor(if (dark) Color.BLACK else Color.rgb(233, 238, 246))
            addView(ProgressBar(this@SecondDisplayMapActivity).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(if (dark) Color.WHITE else Color.DKGRAY)
                contentDescription = getString(com.shilapi.xcertplay.host.R.string.cluster_waiting_for_map)
            }, FrameLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.CENTER))
        }
        root.addView(waiting, FrameLayout.LayoutParams(-1, -1))
        turnCard = ClusterTurnCardView(this).apply { visibility = View.GONE }
        root.addView(turnCard, FrameLayout.LayoutParams(-1, -1))
        root.addView(ClusterMusicView(this), FrameLayout.LayoutParams(-1, -1))
        root.addView(ClusterSpeedometerView(this), FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        SecondDisplayMapOutput.attached(this)
        update()
    }

    internal fun update() {
        waiting?.visibility = if (SecondDisplayMapOutput.streamActive) View.GONE else View.VISIBLE
        val card = SecondDisplayMapOutput.turnCard
        turnCard?.setLayout(card.xPercent, card.yPercent, card.sizePercent)
        turnCard?.setOpacity(card.opacityPercent)
        turnCard?.setNightMode(card.night)
        turnCard?.setGuidance(if (SecondDisplayMapOutput.streamActive) card.guidance else null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!onIntendedDisplay()) finish()
    }

    override fun onResume() {
        super.onResume()
        if (!onIntendedDisplay()) finish()
    }

    override fun onDestroy() {
        SecondDisplayMapOutput.detached(this)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun onIntendedDisplay(): Boolean = intendedDisplay != Display.INVALID_DISPLAY &&
        intendedDisplay != Display.DEFAULT_DISPLAY && windowManager.defaultDisplay.displayId == intendedDisplay
}
