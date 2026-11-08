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
import com.shilapi.xcertplay.airplay.CarClimateCommand
import com.shilapi.xcertplay.airplay.CarClimateReading
import com.shilapi.xcertplay.airplay.CarClimateSwitch
import com.shilapi.xcertplay.airplay.CarClimateZone
import com.shilapi.xcertplay.airplay.CarPlayClimate
import com.shilapi.xcertplay.airplay.CarTemperature
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * FYT's climate for CarPlay, through `com.syu.ms`: the canbus module's shared climate codes (`FinalCanbus.U_AIR_*`)
 * to read, and FYT's voice-assistant command (main module `C_VA_CMD`) to change. Read from the UIS7870 firmware; see
 * docs/HEADUNIT_FYT_DUDU.md § Climate.
 */
internal object FytClimateCodes {
    // Canbus module (7): the active CAN-box protocol and whether it has climate.
    const val U_CANBUS_ID = 1000
    const val U_EXIST_AIR = 1007

    // FinalCanbus.U_AIR_*, shared by nearly every protocol with climate.
    const val POWER = 10
    const val AC = 11
    const val CYCLE = 12
    const val AUTO = 13
    const val WIND_LEVEL = 21
    const val TEMP_LEFT = 27
    const val TEMP_RIGHT = 28
    const val SEAT_HOT_LEFT = 29
    const val SEAT_HOT_RIGHT = 30
    const val AC_MAX = 53
    const val SYNC = 62
    const val FRONT_DEFROST = 65

    /** Target temperature markers FYT's climate popups draw as HI, LO and nothing. */
    const val TEMP_HI = -3
    const val TEMP_LO = -2
    const val TEMP_NONE = -1

    // Main module (0): the protocol's list of voice commands, and the command that carries them.
    const val U_AIR_CONTROL_SUPPORT_CMD = 72
    const val C_VA_CMD = 20

    // FinalVa: absolute on (1) or off (0); recirculation 1 inside, 0 outside; steps for fan and temperature.
    const val VA_AC = 34
    const val VA_FRONT_DEFROST = 36
    const val VA_CYCLE = 38
    const val VA_FAN = 39
    const val VA_TEMP_LEFT = 41
    const val VA_TEMP_RIGHT = 42
    const val VA_FAN_UP = -1
    const val VA_FAN_DOWN = -2
    const val VA_TEMP_UP = 1
    const val VA_TEMP_DOWN = 0

    val CANBUS_CODES = intArrayOf(
        U_CANBUS_ID, U_EXIST_AIR, POWER, AC, CYCLE, AUTO, WIND_LEVEL, TEMP_LEFT, TEMP_RIGHT, SEAT_HOT_LEFT,
        SEAT_HOT_RIGHT, AC_MAX, SYNC, FRONT_DEFROST,
    )

    /**
     * Protocols that put climate at other numbers or redefine the shared range (`com.syu.canbus` callbacks), by
     * protocol (`id & 0xFFFF`) or whole ID (`variant << 16 | protocol`).
     */
    private val OTHER_NUMBERS_PROTOCOLS = setOf(336, 423, 442)
    private val OTHER_NUMBERS_IDS = setOf(1 shl 16 or 443)

    /** Whether the active protocol says it has climate and uses the shared numbers. */
    fun sharedClimate(codes: Map<Int, Int>): Boolean {
        val id = codes[U_CANBUS_ID] ?: return false
        return codes[U_EXIST_AIR] == 1 && (id and 0xFFFF) !in OTHER_NUMBERS_PROTOCOLS && id !in OTHER_NUMBERS_IDS
    }

    /**
     * A target temperature, where its scale is certain: FYT's HI and LO markers, or tenths of a degree on half-degree
     * steps (`Air_0001_WC1_DaZhong`, `Air_0025_XP1_GM_H`). Other protocols use half degrees, raw CAN bytes or an index,
     * which cannot be told apart, so they are null.
     */
    fun temperature(raw: Int?): CarTemperature? = when {
        raw == null -> null
        raw == TEMP_HI -> CarTemperature.High
        raw == TEMP_LO -> CarTemperature.Low
        // 255 is also MQB's half-degree HI marker, so it is left unknown.
        raw == MQB_HI -> null
        raw in TENTHS_MIN..TENTHS_MAX && raw % TENTHS_STEP == 0 -> CarTemperature.Celsius(raw / 10.0)
        else -> null
    }

    /** The car's climate, or null unless the protocol has shared climate codes and the driver's target decodes. */
    fun reading(codes: Map<Int, Int>): CarClimateReading? {
        if (!sharedClimate(codes)) return null
        val driver = temperature(codes[TEMP_LEFT]) ?: return null
        fun on(code: Int) = codes[code]?.let { it != 0 }
        return CarClimateReading(
            hvacOn = on(POWER),
            compressorOn = on(AC),
            maxAcOn = on(AC_MAX),
            maxDefrostOn = on(FRONT_DEFROST),
            autoClimateOn = on(AUTO),
            // FYT's popups show any state but 0 (fresh air) as recirculation.
            recirculationOn = on(CYCLE),
            zonesSynced = on(SYNC),
            driverTarget = driver,
            passengerTarget = temperature(codes[TEMP_RIGHT]),
            // Negative hides the fan. Most protocols top out at 7; a higher level shows as CarPlay's highest.
            fanLevel = codes[WIND_LEVEL]?.takeIf { it >= 0 }?.coerceAtMost(CarPlayClimate.FAN_LEVEL_MAX),
            driverSeatHeating = codes[SEAT_HOT_LEFT]?.takeIf { it >= 0 },
            passengerSeatHeating = codes[SEAT_HOT_RIGHT]?.takeIf { it >= 0 },
        )
    }

    private const val TENTHS_MIN = 150
    private const val TENTHS_MAX = 330
    private const val TENTHS_STEP = 5
    private const val MQB_HI = 255
}

/** What FYT's module service has said, for one command's plan: canbus codes and the voice commands it accepts. */
internal data class FytClimateSnapshot(val codes: Map<Int, Int>, val voiceCommands: Set<Int>)

/**
 * Plans FYT voice commands for an allow-listed change. Switches are absolute; fan and temperature move one step a
 * command, checked against the next reading like [SpdClimatePresses].
 */
internal object FytClimateVoice {
    sealed class Step {
        data object Done : Step()
        data class Send(val ints: IntArray) : Step() {
            override fun equals(other: Any?) = other is Send && ints.contentEquals(other.ints)
            override fun hashCode() = ints.contentHashCode()
            override fun toString() = "Send(${ints.joinToString()})"
        }
        data class Unable(val reason: String) : Step()
    }

    fun maxCommands(command: CarClimateCommand): Int = when (command) {
        is CarClimateCommand.Switch -> 1
        is CarClimateCommand.TargetTemperature, is CarClimateCommand.FanLevel -> 36
        is CarClimateCommand.SeatHeating -> 0
    }

    /** [step] is how far one temperature step moved the car (tenths), once seen. */
    fun next(command: CarClimateCommand, s: FytClimateSnapshot, step: Int?): Step {
        fun send(va: Int, value: Int) =
            if (va in s.voiceCommands) Step.Send(intArrayOf(va, value)) else Step.Unable("the protocol does not take voice command $va")
        return when (command) {
            is CarClimateCommand.Switch -> {
                val (code, va) = switchCodes(command.function) ?: return Step.Unable("FYT has no voice command for ${command.function}")
                val on = s.codes[code]?.let { it != 0 } ?: return Step.Unable("the car does not report $code")
                if (on == command.on) Step.Done else send(va, if (command.on) 1 else 0)
            }
            is CarClimateCommand.TargetTemperature -> {
                val raw = s.codes[if (command.zone == CarClimateZone.DRIVER) FytClimateCodes.TEMP_LEFT else FytClimateCodes.TEMP_RIGHT]
                val current = effective(raw) ?: return Step.Unable("this zone's temperature does not decode")
                val target = (command.celsius * 10).toInt()
                val distance = abs(current - target)
                if (distance == 0 || (step != null && distance * 2 <= step)) return Step.Done
                val va = if (command.zone == CarClimateZone.DRIVER) FytClimateCodes.VA_TEMP_LEFT else FytClimateCodes.VA_TEMP_RIGHT
                send(va, if (target > current) FytClimateCodes.VA_TEMP_UP else FytClimateCodes.VA_TEMP_DOWN)
            }
            is CarClimateCommand.FanLevel -> {
                val current = s.codes[FytClimateCodes.WIND_LEVEL]?.takeIf { it >= 0 }
                    ?: return Step.Unable("the car does not report its fan")
                if (current == command.level) Step.Done
                else send(FytClimateCodes.VA_FAN, if (command.level > current) FytClimateCodes.VA_FAN_UP else FytClimateCodes.VA_FAN_DOWN)
            }
            // Only one protocol takes a seat-heating level; Tarmac does not send it.
            is CarClimateCommand.SeatHeating -> Step.Unable("FYT has no shared seat-heating command")
        }
    }

    fun observed(command: CarClimateCommand, s: FytClimateSnapshot): Int? = s.codes[observedCode(command)]

    fun progressed(command: CarClimateCommand, before: FytClimateSnapshot, after: FytClimateSnapshot): Boolean = when (command) {
        is CarClimateCommand.Switch -> switchCodes(command.function)?.let { (code, _) -> after.codes[code]?.let { it != 0 } } == command.on
        is CarClimateCommand.TargetTemperature -> {
            val target = (command.celsius * 10).toInt()
            val was = effective(before.codes[observedCode(command)])
            val now = effective(after.codes[observedCode(command)])
            was != null && now != null && abs(now - target) < abs(was - target)
        }
        is CarClimateCommand.FanLevel -> {
            val was = before.codes[FytClimateCodes.WIND_LEVEL]
            val now = after.codes[FytClimateCodes.WIND_LEVEL]
            was != null && now != null && abs(now - command.level) < abs(was - command.level)
        }
        is CarClimateCommand.SeatHeating -> false
    }

    private fun observedCode(command: CarClimateCommand): Int = when (command) {
        is CarClimateCommand.Switch -> switchCodes(command.function)?.first ?: -1
        is CarClimateCommand.TargetTemperature ->
            if (command.zone == CarClimateZone.DRIVER) FytClimateCodes.TEMP_LEFT else FytClimateCodes.TEMP_RIGHT
        is CarClimateCommand.FanLevel -> FytClimateCodes.WIND_LEVEL
        is CarClimateCommand.SeatHeating -> -1
    }

    private fun switchCodes(function: CarClimateSwitch): Pair<Int, Int>? = when (function) {
        CarClimateSwitch.AC -> FytClimateCodes.AC to FytClimateCodes.VA_AC
        CarClimateSwitch.FRONT_DEFROST -> FytClimateCodes.FRONT_DEFROST to FytClimateCodes.VA_FRONT_DEFROST
        CarClimateSwitch.RECIRCULATION -> FytClimateCodes.CYCLE to FytClimateCodes.VA_CYCLE
        CarClimateSwitch.MAX_AC -> null
    }

    /** Tenths of a degree; LO and HI sit beyond every panel. Null when the value's scale is not certain. */
    private fun effective(raw: Int?): Int? = when (val t = FytClimateCodes.temperature(raw)) {
        CarTemperature.Low -> 0
        CarTemperature.High -> 1_000
        is CarTemperature.Celsius -> raw
        null -> null
    }
}

/** Sends FYT voice commands one at a time and checks each against the car's next reading; see [SpdClimateControl]. */
internal class FytClimateControl(
    private val read: () -> FytClimateSnapshot?,
    /** Main module `cmd(C_VA_CMD, ints)`. */
    private val send: (IntArray) -> Unit,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : HeadUnitClimateControl {
    override fun apply(command: CarClimateCommand): String? {
        var state = read() ?: return "the car reports no climate"
        var step: Int? = null
        repeat(FytClimateVoice.maxCommands(command)) {
            val ints = when (val next = FytClimateVoice.next(command, state, step)) {
                FytClimateVoice.Step.Done -> return null
                is FytClimateVoice.Step.Unable -> return next.reason
                is FytClimateVoice.Step.Send -> next.ints
            }
            send(ints)
            val before = state
            state = changed(command, before) ?: return "the car did not answer voice command ${ints.joinToString()}"
            if (!FytClimateVoice.progressed(command, before, state)) return "the car moved away from the target after ${ints.joinToString()}"
            if (command is CarClimateCommand.TargetTemperature) {
                val moved = abs((FytClimateVoice.observed(command, state) ?: 0) - (FytClimateVoice.observed(command, before) ?: 0))
                if (moved in 1..TEMPERATURE_STEP_MAX) step = moved
            }
        }
        return when (val next = FytClimateVoice.next(command, state, step)) {
            FytClimateVoice.Step.Done -> null
            is FytClimateVoice.Step.Unable -> next.reason
            is FytClimateVoice.Step.Send -> "not reached after ${FytClimateVoice.maxCommands(command)} commands"
        }
    }

    private fun changed(command: CarClimateCommand, before: FytClimateSnapshot): FytClimateSnapshot? {
        val was = FytClimateVoice.observed(command, before)
        repeat((SpdClimateControl.ANSWER_MILLIS / SpdClimateControl.READ_EVERY_MILLIS).toInt()) {
            sleep(SpdClimateControl.READ_EVERY_MILLIS)
            val now = read() ?: return null
            if (FytClimateVoice.observed(command, now) != was) return now
        }
        return null
    }

    private companion object {
        // A step of more than 2 °C is not one panel step.
        const val TEMPERATURE_STEP_MAX = 20
    }
}

/**
 * FYT's climate source: callbacks on `com.syu.ms`'s canbus module for the shared climate codes and on its main
 * module for the voice commands the protocol accepts. Only the main module's `C_VA_CMD` is ever sent, by [control].
 */
internal class FytClimateSource(private val context: Context) : HeadUnitClimateSource {
    private val canbus = ConcurrentHashMap<Int, Int>()
    @Volatile private var voiceCommands: Set<Int> = emptySet()
    @Volatile private var main: IBinder? = null
    @Volatile private var canbusModule: IBinder? = null
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
            if (update == FytClimateCodes.U_AIR_CONTROL_SUPPORT_CMD) {
                voiceCommands = ints?.toSet().orEmpty()
            } else {
                ints?.firstOrNull()?.let { canbus[update] = it }
            }
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val toolkit = service ?: return
            worker?.post { register(toolkit) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            main = null
            canbusModule = null
        }
    }

    override val control: HeadUnitClimateControl = FytClimateControl(
        read = ::snapshot,
        send = { ints -> FytModuleIpc.cmd(checkNotNull(main) { "module service not connected" }, FytClimateCodes.C_VA_CMD, ints, null) },
    )

    @Synchronized override fun start() {
        if (thread != null) return
        val handlerThread = HandlerThread("tarmac-fyt-climate").also { it.start() }
        thread = handlerThread
        worker = Handler(handlerThread.looper)
        bound = runCatching { context.bindService(FytModuleIpc.toolkitIntent(), connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "module service unavailable", it) }.getOrDefault(false)
    }

    override fun latest(): CarClimateReading? = FytClimateCodes.reading(canbus)

    private fun snapshot(): FytClimateSnapshot? =
        if (FytClimateCodes.sharedClimate(canbus)) FytClimateSnapshot(HashMap(canbus), voiceCommands) else null

    @Synchronized override fun close() {
        canbusModule?.let { module -> FytClimateCodes.CANBUS_CODES.forEach { runCatching { FytModuleIpc.unregister(module, callback, it) } } }
        main?.let { runCatching { FytModuleIpc.unregister(it, callback, FytClimateCodes.U_AIR_CONTROL_SUPPORT_CMD) } }
        main = null
        canbusModule = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        thread?.quitSafely()
        thread = null
        worker = null
        canbus.clear()
    }

    private fun register(toolkit: IBinder) {
        runCatching {
            val module = FytModuleIpc.module(toolkit, FytModuleIpc.MODULE_CANBUS) ?: return
            canbusModule = module
            // Registering with update = 1 also sends each current value.
            FytClimateCodes.CANBUS_CODES.forEach { FytModuleIpc.register(module, callback, it) }
            val mainModule = FytModuleIpc.mainModule(toolkit) ?: return
            main = mainModule
            FytModuleIpc.register(mainModule, callback, FytClimateCodes.U_AIR_CONTROL_SUPPORT_CMD)
        }.onFailure { Log.w(TAG, "climate registration failed", it) }
    }

    private companion object {
        const val TAG = "Tarmac-HeadUnit"
        const val CALLBACK_DESCRIPTOR = "com.syu.ipc.IModuleCallback"
        const val TRANSACTION_UPDATE = 1
    }
}
