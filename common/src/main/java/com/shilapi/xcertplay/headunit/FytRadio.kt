package com.shilapi.xcertplay.headunit

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayRadio
import com.shilapi.xcertplay.airplay.CarRadioBand
import com.shilapi.xcertplay.airplay.CarRadioCommand
import com.shilapi.xcertplay.airplay.CarRadioReading
import com.shilapi.xcertplay.airplay.CarRadioStation
import java.util.concurrent.ConcurrentHashMap

/**
 * FYT's own tuner for CarPlay's Radio app: the radio module (1) of `com.syu.ms` (`FinalRadio`), and the main
 * module's app ID for which source plays. Read from the UIS7870 firmware; see docs/HEADUNIT_FYT_DUDU.md § Radio.
 */
internal object FytRadio {
    const val MODULE_RADIO = 1

    // FinalRadio update codes.
    const val U_BAND = 0
    const val U_FREQ = 1
    const val U_CHANNEL_FREQ = 4
    const val U_RDS_TEXT = 13
    const val U_RDS_CHANNEL_TEXT = 14
    const val U_PS_TEXT = 26
    val UPDATES = intArrayOf(U_BAND, U_FREQ, U_CHANNEL_FREQ, U_RDS_TEXT, U_RDS_CHANNEL_TEXT, U_PS_TEXT)

    // FinalRadio commands Tarmac sends. Scan, save, area, sensitivity, power and the pass-through codes are never sent.
    const val C_NEXT_CHANNEL = 0
    const val C_PREV_CHANNEL = 1
    const val C_SELECT_CHANNEL = 7
    const val C_BAND = 11
    const val C_FREQ = 13
    const val FREQ_DIRECT_NEW = 3

    /** FM1; FM2 and FM3 follow. AM1 and AM2 are 0 and 1. Preset indexes for FM start here too. */
    const val BAND_FM1 = 65_536
    private const val BAND_FM3 = 65_538

    // Main module (0): which app plays (FinalMain.U_APP_ID / C_APP_ID).
    const val U_APP_ID = 0
    const val C_APP_ID = 0
    const val APP_ID_RADIO = 1

    /** FM frequencies are in 10 kHz. */
    private const val FM_UNIT_KHZ = 10

    fun isFm(band: Int?) = band != null && band in BAND_FM1..BAND_FM3

    /** One module command: radio module (1) or main module (0), with its ints. */
    data class Command(val module: Int, val code: Int, val ints: IntArray) {
        override fun equals(other: Any?) = other is Command && module == other.module && code == other.code && ints.contentEquals(other.ints)
        override fun hashCode() = 31 * (31 * module + code) + ints.contentHashCode()
        override fun toString() = "Command($module, $code, ${ints.joinToString()})"
    }

    /** What the radio and main modules have said. */
    class State {
        @Volatile var band: Int? = null
        @Volatile var frequency: Int? = null
        @Volatile var ps: String = ""
        @Volatile var radiotext: String = ""
        @Volatile var appId: Int? = null
        val presets = ConcurrentHashMap<Int, Int>()
        val presetNames = ConcurrentHashMap<Int, String>()
    }

    /** The tuner for CarPlay, or null until it has given a band and a frequency. AM shows as text only. */
    fun reading(s: State): CarRadioReading? {
        val band = s.band ?: return null
        val frequency = s.frequency?.takeIf { it > 0 } ?: return null
        val playing = s.appId == APP_ID_RADIO
        if (!isFm(band)) return CarRadioReading(title = "AM $frequency", playing = playing)
        val khz = frequency * FM_UNIT_KHZ
        val stations = s.presets.entries.filter { it.key >= BAND_FM1 && it.value > 0 }.sortedBy { it.key }
            .distinctBy { it.value }
            .map { (index, value) ->
                val presetKhz = value * FM_UNIT_KHZ
                val name = s.presetNames[index]?.trim()?.takeIf { it.isNotEmpty() } ?: CarPlayRadio.fmName(presetKhz)
                CarRadioStation(CarPlayRadio.fmId(presetKhz), name, frequencyKhz = presetKhz)
            }
        return CarRadioReading(
            band = CarRadioBand.FM,
            frequencyKhz = khz,
            fmStations = stations,
            fmCurrent = stations.firstOrNull { it.frequencyKhz == khz }?.id,
            title = s.ps.trim().ifEmpty { CarPlayRadio.fmName(khz) },
            artist = s.radiotext.trim(),
            playing = playing,
        )
    }

    /** The module commands for [command], or empty where FYT has none (DAB). */
    fun commands(command: CarRadioCommand, s: State): List<Command> {
        fun radio(code: Int, vararg ints: Int) = Command(MODULE_RADIO, code, ints)
        // FYT plays the tuner only while it is the current app; tuning alone stays silent.
        val play = Command(FytModuleIpc.MODULE_MAIN, C_APP_ID, intArrayOf(APP_ID_RADIO))
        val toFm = if (isFm(s.band)) emptyList() else listOf(radio(C_BAND, BAND_FM1))
        fun tune(khz: Int): List<Command> {
            val preset = s.presets.entries.firstOrNull { it.key >= BAND_FM1 && it.value * FM_UNIT_KHZ == khz }?.key
            val select = preset?.let { radio(C_SELECT_CHANNEL, it) } ?: radio(C_FREQ, FREQ_DIRECT_NEW, khz / FM_UNIT_KHZ)
            return toFm + select + play
        }
        return when (command) {
            CarRadioCommand.Play -> listOf(play)
            // Back to the third-party player, which is how FYT's own CarPlay gets its audio.
            CarRadioCommand.Pause -> listOf(Command(FytModuleIpc.MODULE_MAIN, C_APP_ID, intArrayOf(FytTrackText.APP_ID_THIRD_PLAYER)))
            CarRadioCommand.Next -> listOf(radio(C_NEXT_CHANNEL), play)
            CarRadioCommand.Previous -> listOf(radio(C_PREV_CHANNEL), play)
            is CarRadioCommand.Tune -> CarPlayRadio.fmKhz(command.id)?.takeIf { command.band == CarRadioBand.FM }?.let(::tune).orEmpty()
            is CarRadioCommand.TuneFrequency -> tune(command.khz)
            is CarRadioCommand.Source -> if (command.band == CarRadioBand.FM) toFm + play else emptyList()
        }
    }
}

/**
 * FYT's tuner source: callbacks on `com.syu.ms`'s radio and main modules (registering sends the current values), and
 * the radio commands FYT's own radio screen sends.
 */
internal class FytRadioSource(private val context: Context) : HeadUnitRadioSource {
    private val state = FytRadio.State()
    @Volatile private var radio: IBinder? = null
    @Volatile private var main: IBinder? = null
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var bound = false

    private val callback = object : Binder() {
        init {
            attachInterface(null, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_UPDATE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK_DESCRIPTOR)
            val update = data.readInt()
            val ints = data.createIntArray()
            data.createFloatArray()
            val strs = data.createStringArray()
            onUpdate(update, ints, strs)
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val toolkit = service ?: return
            worker?.post { register(toolkit) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            radio = null
            main = null
        }
    }

    @Synchronized override fun start() {
        if (thread != null) return
        val handlerThread = HandlerThread("tarmac-fyt-radio").also { it.start() }
        thread = handlerThread
        worker = Handler(handlerThread.looper)
        bound = runCatching { context.bindService(FytModuleIpc.toolkitIntent(), connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "module service unavailable", it) }.getOrDefault(false)
    }

    override fun latest(): CarRadioReading? = FytRadio.reading(state)

    override fun command(command: CarRadioCommand): Boolean {
        val commands = FytRadio.commands(command, state)
        for (c in commands) {
            val module = checkNotNull(if (c.module == FytRadio.MODULE_RADIO) radio else main) { "module service not connected" }
            FytModuleIpc.cmd(module, c.code, c.ints, null)
        }
        return commands.isNotEmpty()
    }

    @Synchronized override fun close() {
        radio?.let { module ->
            runCatching { FytModuleIpc.unregister(module, bandCallback, FytRadio.U_BAND) }
            FytRadio.UPDATES.filter { it != FytRadio.U_BAND }.forEach { runCatching { FytModuleIpc.unregister(module, callback, it) } }
        }
        main?.let { runCatching { FytModuleIpc.unregister(it, appCallback, FytRadio.U_APP_ID) } }
        radio = null
        main = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        thread?.quitSafely()
        thread = null
        worker = null
    }

    private fun onUpdate(update: Int, ints: IntArray?, strs: Array<String?>?) {
        when (update) {
            FytRadio.U_FREQ -> ints?.firstOrNull()?.let { state.frequency = it }
            FytRadio.U_CHANNEL_FREQ -> if (ints != null && ints.size >= 2) state.presets[ints[0]] = ints[1]
            FytRadio.U_RDS_CHANNEL_TEXT -> if (ints != null && ints.isNotEmpty()) state.presetNames[ints[0]] = strs?.firstOrNull().orEmpty()
            FytRadio.U_PS_TEXT -> state.ps = strs?.firstOrNull().orEmpty()
            FytRadio.U_RDS_TEXT -> state.radiotext = strs?.firstOrNull().orEmpty()
        }
    }

    /** The radio module's code 0 is the band; the main module's code 0 is the app ID, so each has its own callback. */
    private val bandCallback = codeZeroCallback { state.band = it }
    private val appCallback = codeZeroCallback { state.appId = it }

    private fun codeZeroCallback(store: (Int) -> Unit) = object : Binder() {
        init {
            attachInterface(null, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_UPDATE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK_DESCRIPTOR)
            if (data.readInt() == 0) data.createIntArray()?.firstOrNull()?.let(store)
            return true
        }
    }

    private fun register(toolkit: IBinder) {
        runCatching {
            val module = FytModuleIpc.module(toolkit, FytRadio.MODULE_RADIO) ?: return
            radio = module
            FytModuleIpc.register(module, bandCallback, FytRadio.U_BAND)
            FytRadio.UPDATES.filter { it != FytRadio.U_BAND }.forEach { FytModuleIpc.register(module, callback, it) }
            val mainModule = FytModuleIpc.mainModule(toolkit) ?: return
            main = mainModule
            FytModuleIpc.register(mainModule, appCallback, FytRadio.U_APP_ID)
        }.onFailure { Log.w(TAG, "radio registration failed", it) }
    }

    private companion object {
        const val TAG = "Tarmac-HeadUnit"
        const val CALLBACK_DESCRIPTOR = "com.syu.ipc.IModuleCallback"
        const val TRANSACTION_UPDATE = 1
    }
}
