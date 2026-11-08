package com.shilapi.xcertplay.headunit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
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

/**
 * TEYES's own tuner for CarPlay's Radio app: the exported `com.spd.radio` service (`IRadioAidlInterface`), read from
 * the CC4 firmware; see docs/HEADUNIT_SPD_TEYES.md § Radio.
 */
internal object SpdRadio {
    const val PACKAGE = "com.spd.radio"
    const val ACTION = "com.spd.radio.service"
    const val DESCRIPTOR = "com.spd.radio.IRadioAidlInterface"

    const val TRANSACTION_ENTER_SOURCE = 3
    const val TRANSACTION_GET_FREQ_INFO = 5
    const val TRANSACTION_GET_RDS_PS = 11
    const val TRANSACTION_GET_RDS_RT = 12
    const val TRANSACTION_GET_STATUS = 13
    const val TRANSACTION_GET_LIST = 18
    const val TRANSACTION_SET_COMMAND = 20

    // RadioConstant$Command values Tarmac sends. Search, scan, save, region, reset and enter/exit are never sent.
    const val CMD_PRESET_DOWN = 4101
    const val CMD_PRESET_UP = 4102
    const val CMD_SET_BAND = 8192
    const val CMD_SET_FREQ = 8193
    const val CMD_PLAY_STATE = 8199

    const val FM = "FM"
    /** What TEYES's own music widget passes to `enterSource`. */
    const val SOURCE_TOKEN = "com.spd.spdradio|com.spd.radio.START"

    /** `Tools.isFM`: TEYES gives both bands in kHz. */
    private const val FM_ABOVE_KHZ = 2_000

    /** `RadioFreqInfo`: the band's name, the frequency in kHz and the RDS station name. */
    data class FreqInfo(val band: String, val khz: Int, val ps: String = "")

    /** One `setCommand(cmd, arg1, arg2, Bundle{"string": band})`, or the `enterSource` that makes the radio current. */
    sealed class Call {
        data class Command(val cmd: Int, val arg1: Int = 0, val arg2: Int = 0, val band: String? = null) : Call()
        data class EnterSource(val token: String) : Call()
    }

    fun isFm(info: FreqInfo) = info.band.equals(FM, ignoreCase = true) || info.khz > FM_ABOVE_KHZ

    /** The tuner for CarPlay, or null until it has said where it is. AM shows as text only. */
    fun reading(info: FreqInfo?, list: List<FreqInfo>, ps: String?, radiotext: String?, playState: Int?): CarRadioReading? {
        val now = info?.takeIf { it.khz > 0 } ?: return null
        val playing = playState == PLAYING
        if (!isFm(now)) return CarRadioReading(title = "${now.band.ifEmpty { "AM" }} ${now.khz}", playing = playing)
        val stations = list.filter { isFm(it) && it.khz > 0 }.distinctBy { it.khz }.map {
            CarRadioStation(CarPlayRadio.fmId(it.khz), it.ps.trim().ifEmpty { CarPlayRadio.fmName(it.khz) }, frequencyKhz = it.khz)
        }
        return CarRadioReading(
            band = CarRadioBand.FM,
            frequencyKhz = now.khz,
            fmStations = stations,
            fmCurrent = stations.firstOrNull { it.frequencyKhz == now.khz }?.id,
            title = (ps ?: now.ps).trim().ifEmpty { CarPlayRadio.fmName(now.khz) },
            artist = radiotext.orEmpty().trim(),
            playing = playing,
        )
    }

    /** The calls for [command]; tuning takes TEYES's audio to the radio, as the unit's own controls do. */
    fun calls(command: CarRadioCommand, info: FreqInfo?): List<Call> {
        val band = info?.band?.takeIf { it.isNotEmpty() } ?: FM
        val toFm = if (info != null && isFm(info)) emptyList() else listOf(Call.Command(CMD_SET_BAND, band = FM))
        return when (command) {
            CarRadioCommand.Play -> listOf(Call.EnterSource(SOURCE_TOKEN), Call.Command(CMD_PLAY_STATE, PLAY, band = band))
            // Pausing releases TEYES's audio focus, so CarPlay's audio can play again.
            CarRadioCommand.Pause -> listOf(Call.Command(CMD_PLAY_STATE, PAUSE, band = band))
            CarRadioCommand.Next -> listOf(Call.Command(CMD_PRESET_UP, band = band))
            CarRadioCommand.Previous -> listOf(Call.Command(CMD_PRESET_DOWN, band = band))
            is CarRadioCommand.Tune -> CarPlayRadio.fmKhz(command.id)?.takeIf { command.band == CarRadioBand.FM }
                ?.let { toFm + Call.Command(CMD_SET_FREQ, it, band = FM) }.orEmpty()
            is CarRadioCommand.TuneFrequency -> toFm + Call.Command(CMD_SET_FREQ, command.khz, band = FM)
            is CarRadioCommand.Source -> if (command.band == CarRadioBand.FM) listOf(Call.Command(CMD_SET_BAND, band = FM)) else emptyList()
        }
    }

    private const val PLAYING = 1
    private const val PLAY = 1
    private const val PAUSE = 0
}

/** TEYES's tuner source: the radio service's getters polled once a second (it pushes nothing on register). */
internal class SpdRadioSource(private val context: Context) : HeadUnitRadioSource {
    @Volatile private var service: IBinder? = null
    @Volatile private var latest: CarRadioReading? = null
    @Volatile private var info: SpdRadio.FreqInfo? = null
    @Volatile private var list: List<SpdRadio.FreqInfo> = emptyList()
    private var thread: HandlerThread? = null
    private var poller: Handler? = null
    private var bound = false
    private var ticks = 0
    private val failed = HashSet<String>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder
            poller?.post(poll)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val radio = service ?: return
            val now = call(radio, "getFreqInfo", SpdRadio.TRANSACTION_GET_FREQ_INFO) { if (readInt() != 0) freqInfo() else null }
            info = now
            // The station list changes rarely: every few seconds, and whenever the band does.
            if (ticks++ % LIST_EVERY == 0 || list.firstOrNull()?.band != now?.band) {
                list = call(radio, "getList", SpdRadio.TRANSACTION_GET_LIST, now?.band ?: SpdRadio.FM) {
                    val count = readInt()
                    if (count < 0) emptyList() else List(count) { if (readInt() != 0) freqInfo() else null }.filterNotNull()
                }.orEmpty()
            }
            val ps = call(radio, "getRadioRdsPS", SpdRadio.TRANSACTION_GET_RDS_PS) { readString() }
            val rt = call(radio, "getRadioRdsRT", SpdRadio.TRANSACTION_GET_RDS_RT) { readString() }
            // RadioStatus: signalLevel, six flags, local, rdsPTY, then playState.
            val playState = call(radio, "getRadioStatus", SpdRadio.TRANSACTION_GET_STATUS) {
                if (readInt() != 0) { repeat(STATUS_INTS_BEFORE_PLAY_STATE) { readInt() }; readInt() } else null
            }
            latest = SpdRadio.reading(now, list, ps, rt, playState)
            poller?.postDelayed(this, POLL_MILLIS)
        }
    }

    @Synchronized override fun start() {
        if (thread != null) return
        val worker = HandlerThread("tarmac-spd-radio").also { it.start() }
        thread = worker
        poller = Handler(worker.looper)
        bound = runCatching {
            context.bindService(Intent(SpdRadio.ACTION).setPackage(SpdRadio.PACKAGE), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.w(TAG, "radio service unavailable", it) }.getOrDefault(false)
    }

    override fun latest(): CarRadioReading? = latest

    override fun command(command: CarRadioCommand): Boolean {
        val radio = checkNotNull(service) { "radio service not connected" }
        val calls = SpdRadio.calls(command, info)
        for (c in calls) when (c) {
            is SpdRadio.Call.EnterSource -> transact(radio, SpdRadio.TRANSACTION_ENTER_SOURCE) { writeString(c.token) }
            is SpdRadio.Call.Command -> transact(radio, SpdRadio.TRANSACTION_SET_COMMAND) {
                writeInt(c.cmd)
                writeInt(c.arg1)
                writeInt(c.arg2)
                // Several commands read the bundle without a null check, so there always is one.
                writeInt(1)
                Bundle().apply { c.band?.let { putString("string", it) } }.writeToParcel(this, 0)
            }
        }
        return calls.isNotEmpty()
    }

    @Synchronized override fun close() {
        poller?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        poller = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
        latest = null
    }

    private fun Parcel.freqInfo(): SpdRadio.FreqInfo {
        val band = readString().orEmpty()
        val khz = readInt()
        repeat(FREQ_INFO_INTS_BEFORE_PS) { readInt() } // min, max, step, pi, signal
        return SpdRadio.FreqInfo(band, khz, readString().orEmpty())
    }

    private fun <T> call(radio: IBinder, name: String, code: Int, argument: String? = null, read: Parcel.() -> T?): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SpdRadio.DESCRIPTOR)
            argument?.let(data::writeString)
            if (!radio.transact(code, data, reply, 0)) return null
            reply.readException()
            reply.read()
        } catch (error: Exception) {
            if (failed.add(name)) Log.w(TAG, "$name failed: ${error.message}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transact(radio: IBinder, code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SpdRadio.DESCRIPTOR)
            data.write()
            check(radio.transact(code, data, reply, 0)) { "radio transaction $code not handled" }
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val TAG = "Tarmac-HeadUnit"
        const val POLL_MILLIS = 1_000L
        const val LIST_EVERY = 5
        const val FREQ_INFO_INTS_BEFORE_PS = 5
        const val STATUS_INTS_BEFORE_PLAY_STATE = 9
    }
}
