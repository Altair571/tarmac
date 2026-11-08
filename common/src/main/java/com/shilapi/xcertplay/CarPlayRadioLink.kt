package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarPlayRadio
import com.shilapi.xcertplay.airplay.CarRadioBand
import com.shilapi.xcertplay.airplay.CarRadioCommand
import com.shilapi.xcertplay.airplay.CarRadioReading
import com.shilapi.xcertplay.airplay.CarRadioStation
import com.shilapi.xcertplay.headunit.HeadUnitPlatforms
import com.shilapi.xcertplay.headunit.HeadUnitRadioSource

/**
 * CarPlay's Radio app (the CarPlay Ultra experiment) joined to the head unit's own tuner: the tuner's state goes to the
 * iPhone once a second, and the iPhone's radio commands go to the tuner one at a time. See docs/CARPLAY_ULTRA.md.
 */
internal class CarPlayRadioLink(context: Context, private val log: (String) -> Unit) {
    private val context = context.applicationContext ?: context
    @Volatile private var source: HeadUnitRadioSource? = null
    @Volatile private var session: AirPlaySession? = null
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var reported = false
    /** Whether this session's main audio was taken for the head unit's radio. */
    private var holdsAudio = false

    /** Starts reading the tuner while the experiment is on, and stops when it is off. Safe to call again. */
    @Synchronized fun start() {
        if (!AirPlayPersistence.loadCarPlayUltra(context)) return close()
        if (source == null) {
            source = if (AirPlayPersistence.loadCarPlayUltraDemoClimate(context)) DemoRadio()
            else HeadUnitPlatforms.current(context)?.radioSource(context)
            source?.start()
        }
        if (thread == null) {
            val handlerThread = HandlerThread("tarmac-carplay-radio").also { it.start() }
            thread = handlerThread
            worker = Handler(handlerThread.looper)
        }
    }

    /** Whether to describe the radio at SETUP: only where there is a tuner to show. */
    fun available(): Boolean = source != null

    fun attach(session: AirPlaySession) {
        this.session = session
        reported = false
        holdsAudio = false
        worker?.removeCallbacks(tick)
        worker?.post(tick)
    }

    fun detach(session: AirPlaySession) {
        if (this.session !== session) return
        this.session = null
        worker?.removeCallbacks(tick)
    }

    /** A command from the iPhone's Radio app; handed to the tuner off the vehicle-state stream's thread. */
    fun onRadioCommand(command: CarRadioCommand) {
        val tuner = source ?: return
        worker?.post {
            val taken = runCatching { tuner.command(command) }.onFailure { log("CarPlay radio: $command failed: ${it.message}") }
                .getOrDefault(false)
            log("CarPlay radio: ${if (taken) "sent" else "this head unit has no"} $command")
            forward()
        }
    }

    @Synchronized fun close() {
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        worker = null
        source?.close()
        source = null
        session = null
    }

    private val tick = object : Runnable {
        override fun run() {
            forward()
            worker?.postDelayed(this, TICK_MILLIS)
        }
    }

    private fun forward() {
        val current = session ?: return
        val state = current.vehicleState ?: return
        val reading = source?.latest() ?: return
        if (!reported) {
            reported = true
            // Counts only: the log drops lines that carry station names.
            log("CarPlay radio: the tuner reports FM ${reading.frequencyKhz} kHz, ${reading.fmStations.size} stations, " +
                "playing ${reading.playing}")
        }
        state.carSetRadio(reading)
        // A car takes the main audio while its own radio plays, and iOS's Radio app draws only then.
        if (reading.playing != holdsAudio) {
            val sent = current.sendCommand(CarPlayRadio.mainAudio(take = reading.playing))
            log("CarPlay radio: ${if (reading.playing) "took" else "gave back"} the main audio (sent $sent)")
            if (sent) holdsAudio = reading.playing
        }
    }

    /** Two FM stations that play and tune in Tarmac only, for trying the Radio app without a car. */
    private class DemoRadio : HeadUnitRadioSource {
        private val stations = listOf(101_500 to "Tarmac FM", 99_100 to "Demo Radio")
            .map { (khz, name) -> CarRadioStation(CarPlayRadio.fmId(khz), name, frequencyKhz = khz) }
        @Volatile private var reading = CarRadioReading(
            frequencyKhz = stations[0].frequencyKhz, fmStations = stations, fmCurrent = stations[0].id, title = stations[0].name,
        )

        override fun start() = Unit

        override fun latest() = reading

        override fun command(command: CarRadioCommand): Boolean {
            val index = stations.indexOfFirst { it.id == reading.fmCurrent }
            val next = when (command) {
                CarRadioCommand.Play -> return true.also { reading = reading.copy(playing = true) }
                CarRadioCommand.Pause -> return true.also { reading = reading.copy(playing = false) }
                CarRadioCommand.Next -> stations[(index + 1).mod(stations.size)]
                CarRadioCommand.Previous -> stations[(index - 1).mod(stations.size)]
                is CarRadioCommand.Tune -> stations.firstOrNull { it.id == command.id } ?: return false
                is CarRadioCommand.TuneFrequency -> stations.firstOrNull { it.frequencyKhz == command.khz }
                    ?: CarRadioStation(CarPlayRadio.fmId(command.khz), CarPlayRadio.fmName(command.khz), frequencyKhz = command.khz)
                is CarRadioCommand.Source -> return command.band == CarRadioBand.FM
            }
            reading = reading.copy(frequencyKhz = next.frequencyKhz, fmCurrent = next.id, title = next.name, playing = true)
            return true
        }

        override fun close() = Unit
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}
