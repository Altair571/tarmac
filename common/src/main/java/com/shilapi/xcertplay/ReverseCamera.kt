package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.vehicle.VehicleAdapter
import com.shilapi.xcertplay.vehicle.VehicleAdapterSettings
import com.shilapi.xcertplay.vehicle.VehicleSignal

/** The driver's choices for the reversing-camera switch (Settings → Vehicle → Reversing camera). Off by default. */
internal object ReverseCameraSettings {
    private const val PREFS = "diplay_reverse_camera"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SOURCE = "source"
    private const val KEY_CAMERA = "camera_component"
    private const val KEY_ACTION = "broadcast_action"
    private const val KEY_EXTRA = "broadcast_extra"
    private const val KEY_VALUE = "broadcast_value"

    enum class Source { AUTOMATIC, BYD, ECARX, ADAPTER, BROADCAST }

    /** A head unit's own reverse broadcast: [action], with [extra] equal to [reverseValue] while in reverse. */
    data class Broadcast(val action: String, val extra: String, val reverseValue: String)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) ReverseCameraSwitch.ensure(context) else ReverseCameraSwitch.stop()
    }

    fun source(context: Context): Source =
        Source.entries.firstOrNull { it.name == prefs(context).getString(KEY_SOURCE, null) } ?: Source.AUTOMATIC

    fun setSource(context: Context, source: Source) {
        prefs(context).edit().putString(KEY_SOURCE, source.name).apply()
        ReverseCameraSwitch.restart(context)
    }

    fun camera(context: Context): ComponentName? =
        prefs(context).getString(KEY_CAMERA, null)?.let(ComponentName::unflattenFromString)

    fun setCamera(context: Context, component: ComponentName?) {
        prefs(context).edit().apply {
            if (component == null) remove(KEY_CAMERA) else putString(KEY_CAMERA, component.flattenToString())
        }.apply()
    }

    fun broadcast(context: Context): Broadcast? {
        val prefs = prefs(context)
        val action = prefs.getString(KEY_ACTION, null)?.takeIf { it.isNotBlank() } ?: return null
        return Broadcast(action, prefs.getString(KEY_EXTRA, "").orEmpty(), prefs.getString(KEY_VALUE, "").orEmpty())
    }

    fun setBroadcast(context: Context, broadcast: Broadcast?) {
        prefs(context).edit().apply {
            if (broadcast == null) {
                remove(KEY_ACTION); remove(KEY_EXTRA); remove(KEY_VALUE)
            } else {
                putString(KEY_ACTION, broadcast.action.trim())
                putString(KEY_EXTRA, broadcast.extra.trim())
                putString(KEY_VALUE, broadcast.reverseValue.trim())
            }
        }.apply()
        ReverseCameraSwitch.restart(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * When to hand the screen to the camera and when to give it back, from one gear reading at a time. No Android
 * types, so it is unit-tested. Reverse takes the screen at once. The screen comes back only after the car has
 * been out of reverse for [leaveDelayMillis], so a quick R-D-R while parking does not flicker, or after the gear
 * has been unknown for [unknownGraceMillis], so a lost gear source never keeps CarPlay away.
 */
internal class ReverseCameraPolicy(
    private val leaveDelayMillis: Long = LEAVE_DELAY_MILLIS,
    private val unknownGraceMillis: Long = UNKNOWN_GRACE_MILLIS,
) {
    enum class Action { NONE, ENTER, LEAVE }

    var reversing = false
        private set
    private var outSince: Long? = null

    fun onReading(reverse: Boolean?, now: Long): Action {
        if (reverse == true) {
            outSince = null
            if (reversing) return Action.NONE
            reversing = true
            return Action.ENTER
        }
        if (!reversing) return Action.NONE
        val since = outSince ?: now.also { outSince = it }
        if (now - since < if (reverse == null) unknownGraceMillis else leaveDelayMillis) return Action.NONE
        reversing = false
        outSince = null
        return Action.LEAVE
    }

    fun reset() {
        reversing = false
        outSince = null
    }

    companion object {
        const val LEAVE_DELAY_MILLIS = 1_500L
        const val UNKNOWN_GRACE_MILLIS = 5_000L
    }
}

/** Puts likely camera apps first in the picker; only the order depends on it. */
internal object ReverseCameraApps {
    private val camera = Regex("(?i)camera|\\bcam\\b|avm|360|reverse|rear|backview|倒车|倒車|后视|後視|全景")

    fun likelyCamera(label: String, packageName: String): Boolean =
        camera.containsMatchIn(label) || camera.containsMatchIn(packageName)
}

/** Where the gear comes from: BYD's gear field, the ECARX sensor, the car data adapter or a head-unit broadcast. */
internal object ReverseGearSources {
    /** The source [ReverseCameraSettings.Source.AUTOMATIC] uses here, or null when this head unit has none. */
    fun resolve(context: Context, chosen: ReverseCameraSettings.Source = ReverseCameraSettings.source(context)): ReverseCameraSettings.Source? {
        if (chosen != ReverseCameraSettings.Source.AUTOMATIC) return chosen
        return when {
            CarHotspotSetup.isBydHeadUnit(context) -> ReverseCameraSettings.Source.BYD
            EcarxWheelSpeed.present() -> ReverseCameraSettings.Source.ECARX
            adapterHasGear(context) -> ReverseCameraSettings.Source.ADAPTER
            ReverseCameraSettings.broadcast(context) != null -> ReverseCameraSettings.Source.BROADCAST
            else -> null
        }
    }

    fun adapterHasGear(context: Context): Boolean =
        VehicleAdapterSettings.kind(context) != VehicleAdapterSettings.Kind.OFF &&
            VehicleAdapterSettings.signalMap(context).let { map ->
                (map.obd.map { it.decoding } + map.can.map { it.decoding }).any { it.signal == VehicleSignal.GEAR }
            }

    /** True in reverse, false out of it, null when unknown. May block on ADB; call off the main thread. */
    fun reversing(context: Context, source: ReverseCameraSettings.Source?): Boolean? = when (source) {
        ReverseCameraSettings.Source.BYD -> BydNavigationOutputs.reversing(context)
        ReverseCameraSettings.Source.ECARX -> EcarxWheelSpeed.readGear(context)?.let { it == VehicleGear.REVERSE }
        ReverseCameraSettings.Source.ADAPTER -> VehicleAdapter.gear()?.let { it == VehicleGear.REVERSE }
        ReverseCameraSettings.Source.BROADCAST -> ReverseBroadcast.reversing
        ReverseCameraSettings.Source.AUTOMATIC, null -> null
    }
}

/** A head unit's reverse broadcast, configured by the driver; holds the last state it announced. */
internal object ReverseBroadcast {
    @Volatile var reversing: Boolean? = null
        private set
    private var receiver: BroadcastReceiver? = null

    /** Whether [intent] says reverse; the extra is compared as text, so ints, booleans and strings all work. */
    fun matches(intent: Intent, broadcast: ReverseCameraSettings.Broadcast): Boolean =
        intent.extras?.get(broadcast.extra)?.toString()?.equals(broadcast.reverseValue, ignoreCase = true) == true

    @Synchronized
    fun register(context: Context, broadcast: ReverseCameraSettings.Broadcast) {
        unregister(context)
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                reversing = matches(intent, broadcast)
            }
        }
        // Sent by the head unit's system apps, so it has to be exported.
        val filter = IntentFilter(broadcast.action)
        val app = context.applicationContext
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(next, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(next, filter)
            receiver = next
        }.onFailure { Log.w(ReverseCameraSwitch.TAG, "reverse broadcast ${broadcast.action} unavailable", it) }
    }

    @Synchronized
    fun unregister(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        reversing = null
    }
}

/**
 * Watches the gear while a CarPlay session runs and the switch is on. In reverse, while CarPlay is on screen,
 * it opens the chosen camera app or moves CarPlay to the back; after reverse it brings CarPlay to the front
 * again, but only if it was the one that took CarPlay away. CarPlay stays connected throughout.
 */
internal object ReverseCameraSwitch {
    internal const val TAG = "DiPlay-Reverse"
    private const val POLL_MILLIS = 300L

    private val main = Handler(Looper.getMainLooper())
    private val policy = ReverseCameraPolicy()
    @Volatile private var carPlayInFront = false
    private var tookScreen = false // main thread
    private var worker: Thread? = null
    @Volatile private var source: ReverseCameraSettings.Source? = null
    private var appContext: Context? = null

    /** The CarPlay screen came to the front or left it. */
    fun carPlayInFront(context: Context, inFront: Boolean) {
        carPlayInFront = inFront
        if (inFront) ensure(context)
    }

    /** Starts watching when the switch is on and a CarPlay session exists; idempotent. */
    @Synchronized
    fun ensure(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!ReverseCameraSettings.enabled(app) || !CarPlayBackgroundSession.hasSession()) return
        if (worker?.isAlive == true) return
        val resolved = ReverseGearSources.resolve(app)
        source = resolved
        if (resolved == null) {
            Log.i(TAG, "no gear source on this head unit")
            return
        }
        if (resolved == ReverseCameraSettings.Source.BROADCAST) {
            ReverseCameraSettings.broadcast(app)?.let { ReverseBroadcast.register(app, it) }
        }
        if (resolved == ReverseCameraSettings.Source.ADAPTER) VehicleAdapter.start(app)
        policy.reset()
        Log.i(TAG, "watching the gear from $resolved")
        worker = Thread({ run(app, resolved) }, "diplay-reverse-camera").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
        source = null
        appContext?.let(ReverseBroadcast::unregister)
    }

    fun restart(context: Context) {
        stop()
        ensure(context)
    }

    fun report(context: Context): String =
        "enabled=${ReverseCameraSettings.enabled(context)} chosen=${ReverseCameraSettings.source(context)} " +
            "using=${source ?: ReverseGearSources.resolve(context)} reversing=${policy.reversing} " +
            "camera=${ReverseCameraSettings.camera(context)?.flattenToShortString() ?: "none"}"

    private fun run(app: Context, source: ReverseCameraSettings.Source) {
        val me = Thread.currentThread()
        try {
            while (!me.isInterrupted && synchronized(this) { worker === me }) {
                if (!ReverseCameraSettings.enabled(app) || !CarPlayBackgroundSession.hasSession()) break
                val reverse = runCatching { ReverseGearSources.reversing(app, source) }.getOrNull()
                when (policy.onReading(reverse, SystemClock.elapsedRealtime())) {
                    ReverseCameraPolicy.Action.ENTER -> main.post { enter(app) }
                    ReverseCameraPolicy.Action.LEAVE -> main.post { leave(app) }
                    ReverseCameraPolicy.Action.NONE -> Unit
                }
                Thread.sleep(POLL_MILLIS)
            }
        } catch (_: InterruptedException) {
            // stopped
        }
        synchronized(this) { if (worker === me) worker = null }
        // A session that ended in reverse must not leave CarPlay behind for the next one.
        if (policy.reversing) main.post { leave(app) }
        policy.reset()
    }

    private fun enter(app: Context) {
        if (!carPlayInFront) {
            Log.i(TAG, "reverse: CarPlay is not on screen, nothing to do")
            return
        }
        tookScreen = openCamera(app) || moveCarPlayBack()
        Log.i(TAG, "reverse: screen handed over=$tookScreen")
    }

    private fun leave(app: Context) {
        if (!tookScreen) return
        tookScreen = false
        if (!CarPlayBackgroundSession.hasSession()) return
        runCatching {
            app.startActivity(CarPlayScreen.intent(app)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            Log.i(TAG, "out of reverse: CarPlay back")
        }.onFailure { Log.w(TAG, "could not bring CarPlay back after reverse", it) }
    }

    private fun openCamera(app: Context): Boolean {
        val component = ReverseCameraSettings.camera(app) ?: return false
        return runCatching {
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrElse {
            Log.w(TAG, "camera app ${component.flattenToShortString()} did not open", it)
            false
        }
    }

    private fun moveCarPlayBack(): Boolean = CarPlayScreen.current()?.moveTaskToBack(true) == true
}
