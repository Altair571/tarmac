package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.shilapi.xcertplay.glance.CarPlayGlance
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** "Send turns to the car's cluster and HUD" (Settings → Navigation): off by default, for head units other than BYD's. */
object CarGuidanceSettings {
    private const val PREFS = "diplay_car_guidance"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Applies at once: the sender reads it every second and ends the guidance it sent when it goes off. */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * BYD's own AMap adapter gets this broadcast from [BydClusterBridge], under the BYD navigation switch; the
     * generic sender stays out of its way there, so the BYD path is unchanged.
     */
    fun available(context: Context): Boolean = BydAmapAdapter.find { packageName ->
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    } == null

    /** One diagnostic-report line. */
    fun report(context: Context): String = CarGuidanceBroadcast.report(context)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * CarPlay's next turn as AMap's standard guidance broadcast (`AUTONAVI_STANDARD_BROADCAST_SEND`, KEY_TYPE 10001),
 * for any head unit whose CAN-box or cluster service listens for it, as many Chinese-market head units do for the
 * stock AMap app. They pass the arrow, distance and street on to the factory cluster or HUD; a unit with no such
 * receiver ignores the broadcast. See docs/OEM_CLUSTER.md.
 *
 * Fed from [CarPlayGlance] once a second, like a stock navigation client: a change goes out at once, the same turn
 * is repeated every five seconds as a keepalive, and the end of the route sends the AMap "navigation ended" state.
 * The extras and icon codes are the ones [BydClusterBridge] sends on BYD, without its BYD-only flags.
 */
internal object CarGuidanceBroadcast {
    private const val TAG = "DiPlay-CarGuidance"
    const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    const val KEY_GUIDANCE = 10001
    const val KEY_STATE = 10019
    const val STATE_ENDED = 9
    private const val KEEPALIVE_TICKS = 5
    private const val FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000 // hidden Intent flag, as sent by stock clients

    private var context: Context? = null
    private var executor: ScheduledExecutorService? = null
    private var lastSent: BydClusterFrame? = null
    private var ticksSinceSend = 0
    internal var glance: () -> CarPlayGlance.Snapshot = CarPlayGlance::snapshot
    internal var send: (Context, Intent) -> Boolean = { app, intent ->
        try {
            app.sendBroadcast(intent)
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "guidance broadcast failed", error)
            false
        }
    }

    /** A CarPlay session became active; idempotent. */
    @Synchronized
    fun start(appContext: Context) {
        context = appContext.applicationContext
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-car-guidance").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::tick, 1, 1, TimeUnit.SECONDS) }
    }

    /** The session ended: stop, and end guidance on the car if any was sent. */
    @Synchronized
    fun stop() {
        executor?.shutdownNow()
        executor = null
        if (lastSent != null) sendEndLocked()
    }

    @Synchronized
    internal fun tick() {
        val app = context ?: return
        val frame = if (CarGuidanceSettings.enabled(app) && CarGuidanceSettings.available(app)) {
            frame(glance())
        } else null
        if (frame == null) {
            if (lastSent != null) sendEndLocked()
            return
        }
        if (frame == lastSent && ++ticksSinceSend < KEEPALIVE_TICKS) return
        if (send(app, guidanceIntent(frame))) {
            if (lastSent == null) Log.i(TAG, "guidance sent $frame")
            lastSent = frame
            ticksSinceSend = 0
        }
    }

    /** The AMap frame for [snapshot], or null without a CarPlay route. */
    internal fun frame(snapshot: CarPlayGlance.Snapshot): BydClusterFrame? {
        if (!snapshot.connected) return null
        val type = snapshot.maneuverType ?: return null
        return BydClusterFrame.from(BydAppleManeuver(
            distanceMeters = snapshot.distanceMeters,
            type = type,
            drivingSide = snapshot.drivingSide,
            road = snapshot.road,
            remainingSeconds = snapshot.remainingSeconds,
            remainingMeters = snapshot.remainingMeters,
        ))
    }

    internal fun guidanceIntent(frame: BydClusterFrame): Intent = baseIntent(KEY_GUIDANCE).apply {
        putExtra("TYPE", 0)
        putExtra("EXTRA_STATE", 0)
        putExtra("EXTRA_IS_FOREGROUND", 0)
        putExtra("NEW_ICON", frame.icon)
        putExtra("ROUNG_ABOUT_NUM", frame.roundaboutExit)
        putExtra("SEG_REMAIN_DIS", frame.distanceMeters)
        putExtra("NEXT_ROAD_NAME", frame.road)
        putExtra("ROUTE_REMAIN_DIS", frame.routeRemainingMeters)
        putExtra("ROUTE_REMAIN_TIME", frame.routeRemainingSeconds)
    }

    internal fun endIntent(): Intent = baseIntent(KEY_STATE).apply {
        putExtra("EXTRA_STATE", STATE_ENDED)
        putExtra("EXTRA_IS_FOREGROUND", 1)
        putExtra("NEW_ICON", -1)
        putExtra("SEG_REMAIN_DIS", -1)
        putExtra("NEXT_ROAD_NAME", "")
        putExtra("ROUTE_REMAIN_DIS", -1)
        putExtra("ROUTE_REMAIN_TIME", -1)
    }

    private fun sendEndLocked() {
        val app = context ?: return
        if (!send(app, endIntent())) return
        lastSent = null
        ticksSinceSend = 0
        Log.i(TAG, "guidance ended")
    }

    // No package: the receivers differ per head unit, and stock navigation apps broadcast it the same way.
    private fun baseIntent(keyType: Int) = Intent(ACTION).apply {
        addFlags(FLAG_RECEIVER_INCLUDE_BACKGROUND)
        putExtra("KEY_TYPE", keyType)
    }

    fun report(context: Context): String =
        "enabled=${CarGuidanceSettings.enabled(context)} available=${CarGuidanceSettings.available(context)} " +
            "running=${executor != null} lastSent=$lastSent"
}
