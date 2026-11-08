package com.shilapi.xcertplay.headunit

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarStartLaunch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Decides when a head unit waking from sleep should open CarPlay. Many units suspend at ACC off instead of
 * shutting down, so BOOT_COMPLETED never comes again; the platform's ACC signal stands in for it.
 */
internal class AccWakePolicy {
    private var acc: Boolean? = null
    private var reversing = false
    private var waitingForReverse = false

    fun initial(acc: Boolean?, reversing: Boolean?) {
        this.acc = acc
        this.reversing = reversing == true
    }

    /** True when CarPlay should open now. Only a change from off to on counts: the first value is no wake. */
    fun onAcc(on: Boolean, autoStart: Boolean): Boolean {
        val woke = acc == false && on
        acc = on
        if (!on) {
            waitingForReverse = false
            return false
        }
        if (!woke || !autoStart) return false
        if (reversing) {
            // The camera has the screen; open once the driver leaves reverse.
            waitingForReverse = true
            return false
        }
        return true
    }

    /** True when a wake that waited for reverse to end should open CarPlay now. */
    fun onReverse(on: Boolean): Boolean {
        reversing = on
        if (on || !waitingForReverse) return false
        waitingForReverse = false
        return acc == true
    }
}

/** Follows the head unit's car signals for the whole process; other features listen here. */
internal object HeadUnitSignalMonitor {
    private const val TAG = "Tarmac-HeadUnit"

    private val listeners = CopyOnWriteArraySet<(HeadUnitSignal, Boolean) -> Unit>()
    private val policy = AccWakePolicy()
    @Volatile private var source: HeadUnitSignalSource? = null

    /** Safe to call from every entry point; only the first call on a known platform starts observing. */
    @Synchronized fun start(context: Context) {
        if (source != null) return
        val app = context.applicationContext ?: context
        val platform = HeadUnitPlatforms.current(app) ?: return
        val signals = platform.signals(app) ?: return
        source = signals
        policy.initial(signals.current(HeadUnitSignal.ACC), signals.current(HeadUnitSignal.REVERSE))
        signals.start { signal, on -> onSignal(app, platform, signal, on) }
        Log.i(TAG, "watching ${platform.id} signals: acc=${signals.current(HeadUnitSignal.ACC)} " +
            "reverse=${signals.current(HeadUnitSignal.REVERSE)}")
    }

    /** The last state the platform announced, or null before it has said or on a platform without the signal. */
    fun current(context: Context, signal: HeadUnitSignal): Boolean? =
        (source ?: HeadUnitPlatforms.current(context)?.signals(context))?.current(signal)

    fun reversing(context: Context): Boolean = current(context, HeadUnitSignal.REVERSE) == true

    fun addListener(listener: (HeadUnitSignal, Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (HeadUnitSignal, Boolean) -> Unit) {
        listeners -= listener
    }

    private fun onSignal(app: Context, platform: HeadUnitPlatform, signal: HeadUnitSignal, on: Boolean) {
        Log.i(TAG, "${platform.id} $signal=$on")
        val open = when (signal) {
            HeadUnitSignal.ACC -> policy.onAcc(on, AirPlayPersistence.loadAutoStartOnBoot(app))
            HeadUnitSignal.REVERSE -> policy.onReverse(on)
            HeadUnitSignal.NIGHT -> false
        }
        // The unit opens its own starting app at wake; a second launch would only race it.
        if (open && platform.startingApp(app) != app.packageName) openCarPlay(app)
        listeners.forEach { listener -> runCatching { listener(signal, on) }.onFailure { Log.w(TAG, "listener", it) } }
    }

    private fun openCarPlay(app: Context) {
        runCatching { app.startActivity(CarStartLaunch.intent(app)) }
            .onSuccess { Log.i(TAG, "car woke: opened DiPlay") }
            .onFailure { Log.w(TAG, "car woke: DiPlay could not open", it) }
    }
}
