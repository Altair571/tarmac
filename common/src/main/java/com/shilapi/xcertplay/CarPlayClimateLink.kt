package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarClimateCommand
import com.shilapi.xcertplay.airplay.CarClimateReading
import com.shilapi.xcertplay.airplay.CarPlayClimate
import com.shilapi.xcertplay.airplay.CarPlayVehicleProfile
import com.shilapi.xcertplay.headunit.HeadUnitClimateControl
import com.shilapi.xcertplay.headunit.HeadUnitClimateSource
import com.shilapi.xcertplay.headunit.HeadUnitPlatforms
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CarPlay's climate (the CarPlay Ultra experiment's vehicle state) joined to the head unit's climate source: the
 * car's values go to the iPhone once a second, and, when the driver opted in, the iPhone's allow-listed changes go
 * to the car one at a time. Without a source, CarPlay describes no climate. See docs/CARPLAY_ULTRA.md.
 */
internal class CarPlayClimateLink(context: Context, private val log: (String) -> Unit) {
    private val context = context.applicationContext ?: context
    @Volatile private var source: HeadUnitClimateSource? = null
    @Volatile private var demo = false
    @Volatile private var session: AirPlaySession? = null
    @Volatile private var stopped: String? = null
    private var thread: HandlerThread? = null
    private var ticker: Handler? = null
    private var writer: ExecutorService? = null
    private var reportedThisSession = false
    private var seatHeatingSaved = false
    private var attachedAt = 0L

    // The iPhone's latest wish per characteristic, and every characteristic queued or being applied.
    private val pending = LinkedHashMap<String, CarClimateCommand>()
    private val busy = HashSet<String>()
    private val applied = ArrayDeque<Long>()

    /** Starts reading the car while the experiment is on, and stops when it is off. Safe to call again. */
    @Synchronized fun start() {
        if (!AirPlayPersistence.loadCarPlayUltra(context)) return close()
        demo = AirPlayPersistence.loadCarPlayUltraDemoClimate(context)
        if (source == null) {
            source = HeadUnitPlatforms.current(context)?.climateSource(context)?.also {
                it.start()
                log("CarPlay climate: reading the car through ${HeadUnitPlatforms.current(context)?.displayName}")
            }
        }
        if (thread == null) {
            val worker = HandlerThread("tarmac-carplay-climate").also { it.start() }
            thread = worker
            ticker = Handler(worker.looper)
            writer = Executors.newSingleThreadExecutor { Thread(it, "tarmac-carplay-climate-write") }
        }
    }

    /**
     * The accessories to describe at SETUP: climate only where the car has reported it (now or on an earlier
     * drive), seat heating only once a seat has been heated. The demo preference describes everything.
     */
    fun profile(): CarPlayVehicleProfile {
        if (demo) return CarPlayVehicleProfile.ALL
        val source = source ?: return CarPlayVehicleProfile.NONE
        val reading = source.latest()
        val climate = reading != null || AirPlayPersistence.loadCarPlayClimateSeen(context)
        val seats = reading?.let(::seatHeated) == true || AirPlayPersistence.loadCarPlaySeatHeatingSeen(context)
        return CarPlayVehicleProfile(climate = climate, seatHeating = climate && seats)
    }

    fun attach(session: AirPlaySession) {
        this.session = session
        stopped = null
        reportedThisSession = false
        seatHeatingSaved = false
        attachedAt = SystemClock.elapsedRealtime()
        ticker?.removeCallbacks(tick)
        ticker?.post(tick)
    }

    fun detach(session: AirPlaySession) {
        if (this.session !== session) return
        this.session = null
        ticker?.removeCallbacks(tick)
        // A car that no longer reports climate (another CAN-box profile) stops describing it next time.
        val lasted = SystemClock.elapsedRealtime() - attachedAt >= FORGET_AFTER_MILLIS
        if (source != null && !demo && !reportedThisSession && lasted) AirPlayPersistence.forgetCarPlayClimate(context)
        synchronized(this) {
            pending.clear()
            busy.clear()
        }
    }

    /**
     * The iPhone changed [name]. True keeps it; false refuses it and gives the iPhone the car's value back. Called on
     * the vehicle-state stream's thread, so it only queues.
     */
    fun onPhoneWrite(name: String, value: Any?): Boolean {
        if (demo) return true
        val control = source?.control ?: return false
        if (!AirPlayPersistence.loadCarPlayClimateWrites(context)) {
            log("CarPlay climate: kept $name from the car; changes from the iPhone are off")
            return false
        }
        stopped?.let {
            log("CarPlay climate: refused $name; sending stopped earlier ($it)")
            return false
        }
        val command = CarPlayClimate.command(name, value) ?: run {
            log("CarPlay climate: refused $name = $value; not on the allow-list or out of range")
            return false
        }
        val executor = writer ?: return false
        synchronized(this) {
            pending.remove(name)
            pending[name] = command
            busy += name
        }
        executor.execute { drain(control) }
        return true
    }

    @Synchronized fun close() {
        ticker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        ticker = null
        writer?.shutdownNow()
        writer = null
        source?.close()
        source = null
        session = null
    }

    private val tick = object : Runnable {
        override fun run() {
            forward()
            ticker?.postDelayed(this, TICK_MILLIS)
        }
    }

    private fun forward() {
        val state = session?.vehicleState ?: return
        val reading = source?.latest() ?: return
        if (!reportedThisSession) {
            reportedThisSession = true
            log("CarPlay climate: the car reports $reading")
            AirPlayPersistence.markCarPlayClimateSeen(context, seatHeating = false)
        }
        if (!seatHeatingSaved && seatHeated(reading)) {
            seatHeatingSaved = true
            AirPlayPersistence.markCarPlayClimateSeen(context, seatHeating = true)
        }
        val waiting = synchronized(this) { busy.flatMap(::shownWith).toSet() }
        for ((name, value) in CarPlayClimate.characteristics(reading)) {
            if (name !in waiting) state.carSet(name, value)
        }
    }

    private fun drain(control: HeadUnitClimateControl) {
        while (true) {
            val (name, command) = synchronized(this) {
                pending.entries.firstOrNull()?.let { entry -> pending.remove(entry.key); entry.key to entry.value }
            } ?: return
            if (stopped == null) {
                waitForRate()
                val reason = runCatching { control.apply(command) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
                if (reason == null) {
                    log("CarPlay climate: the car took $command")
                } else {
                    stopped = reason
                    log("CarPlay climate: stopped sending to the car after $command: $reason")
                }
            }
            synchronized(this) { if (name !in pending) busy -= name }
        }
    }

    /** At most [COMMANDS_PER_MINUTE] commands a minute and [COMMAND_GAP_MILLIS] between two; later ones wait, coalesced. */
    private fun waitForRate() {
        val now = SystemClock.elapsedRealtime()
        while (applied.isNotEmpty() && now - applied.first() > MINUTE_MILLIS) applied.removeFirst()
        val wait = maxOf(
            applied.lastOrNull()?.let { it + COMMAND_GAP_MILLIS - now } ?: 0L,
            if (applied.size >= COMMANDS_PER_MINUTE) applied.first() + MINUTE_MILLIS - now else 0L,
        )
        if (wait > 0) Thread.sleep(wait)
        applied.addLast(SystemClock.elapsedRealtime())
    }

    private fun seatHeated(reading: CarClimateReading): Boolean =
        (reading.driverSeatHeating ?: 0) > 0 || (reading.passengerSeatHeating ?: 0) > 0

    /** The characteristics a write to [name] changes, which wait for the car while it applies. */
    private fun shownWith(name: String): List<String> = when (name) {
        "fanLevel" -> listOf("fanLevel", "fanOn")
        "driverSeatHeatingLevel" -> listOf(name, "driverSeatHeatingOn")
        "passengerSeatHeatingLevel" -> listOf(name, "passengerSeatHeatingOn")
        else -> listOf(name)
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val COMMAND_GAP_MILLIS = 300L
        const val COMMANDS_PER_MINUTE = 30
        const val MINUTE_MILLIS = 60_000L
        const val FORGET_AFTER_MILLIS = 30_000L
    }
}
