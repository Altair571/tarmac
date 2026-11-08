package com.shilapi.xcertplay.headunit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.shilapi.xcertplay.airplay.CarClimateCommand
import com.shilapi.xcertplay.airplay.CarClimateReading
import com.shilapi.xcertplay.airplay.CarClimateSwitch
import com.shilapi.xcertplay.airplay.CarClimateZone
import com.shilapi.xcertplay.airplay.CarPlayClimate
import com.shilapi.xcertplay.airplay.CarTemperature
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * TEYES's climate for CarPlay, through the CAN-box service `com.spd.carinfo`. Keys, value forms and the button
 * convention were read from the CC4 firmware (`CarInfo$AirCondition`, `CarInfo$Seats`, `CarInfo`'s flags and
 * TEYES's own climate app `com.spd.aircondition`); see docs/HEADUNIT_SPD_TEYES.md § Climate.
 */
internal object SpdClimateKeys {
    // CarInfo$AirCondition (group 3) and CarInfo$Seats (group 4).
    const val POWER = 30000
    const val AC = 30001
    const val AUTO = 30002
    const val WIND_LEVEL = 30004
    const val RECIRCULATION = 30005
    const val TEMPERATURE = 30006
    const val AC_MAX = 30007
    const val SYNC = 30009
    const val FRONT_DEFROST = 30010
    const val INNER_TEMPERATURE = 30028
    const val WIND_LEVEL_MAX = 30051
    const val SEAT_HEATED = 40001

    // CarInfo.SEAT_DRIVER / SEAT_PASSENGER, also the zone argument of the temperature key.
    const val ZONE_DRIVER = 0
    const val ZONE_PASSENGER = 1

    // CarInfo's button flags, as TEYES's climate widgets send them on touch down and up.
    const val FLAG_BUTTON_UP = 0x10000
    const val FLAG_BUTTON_DOWN = 0x20000
    const val FLAG_INCREASE = 0x2000
    const val FLAG_DECREASE = 0x4000

    /** CarInfo.FLAG_RECIRCULATION_INNER; 0 is outside air and 2 automatic. */
    const val RECIRCULATION_INNER = 1

    // Target values TEYES's climate app shows as LO, HI and OFF.
    const val TEMPERATURE_LO = 0f
    const val TEMPERATURE_HI = 254f
    const val TEMPERATURE_OFF = 255f
    const val UNIT_FAHRENHEIT = 1

    /** The fan's top level when the CAN-box profile does not report one. */
    const val FAN_LEVEL_MAX_DEFAULT = 7

    fun zone(zone: CarClimateZone): Int = if (zone == CarClimateZone.DRIVER) ZONE_DRIVER else ZONE_PASSENGER

    fun key(function: CarClimateSwitch): Int = when (function) {
        CarClimateSwitch.AC -> AC
        CarClimateSwitch.MAX_AC -> AC_MAX
        CarClimateSwitch.RECIRCULATION -> RECIRCULATION
        CarClimateSwitch.FRONT_DEFROST -> FRONT_DEFROST
    }

    /**
     * The car's raw climate, or null when the CAN-box profile reports none. TEYES fills a target temperature's
     * range only once the car has sent its climate, so an empty range means no climate.
     */
    fun snapshot(readInt: (key: Int, zone: Int) -> Int?, readTemperature: (key: Int, zone: Int) -> SpdTemperature?): SpdClimateSnapshot? {
        val driver = readTemperature(TEMPERATURE, ZONE_DRIVER)?.takeIf { it.max > it.min } ?: return null
        return SpdClimateSnapshot(
            power = readInt(POWER, 0),
            ac = readInt(AC, 0),
            auto = readInt(AUTO, 0),
            fan = readInt(WIND_LEVEL, ZONE_DRIVER),
            fanMax = readInt(WIND_LEVEL_MAX, 0)?.takeIf { it > 0 },
            recirculation = readInt(RECIRCULATION, 0),
            acMax = readInt(AC_MAX, 0),
            sync = readInt(SYNC, 0),
            frontDefrost = readInt(FRONT_DEFROST, 0),
            driver = driver,
            passenger = readTemperature(TEMPERATURE, ZONE_PASSENGER)?.takeIf { it.max > it.min },
            cabin = readTemperature(INNER_TEMPERATURE, 0),
            driverSeat = readInt(SEAT_HEATED, ZONE_DRIVER),
            passengerSeat = readInt(SEAT_HEATED, ZONE_PASSENGER),
        )
    }

    fun reading(s: SpdClimateSnapshot): CarClimateReading = CarClimateReading(
        hvacOn = s.power?.let { it > 0 },
        compressorOn = s.ac?.let { it > 0 },
        maxAcOn = s.acMax?.let { it > 0 },
        maxDefrostOn = s.frontDefrost?.let { it > 0 },
        autoClimateOn = s.auto?.let { it > 0 },
        recirculationOn = s.recirculation?.let { it == RECIRCULATION_INNER },
        zonesSynced = s.sync?.let { it > 0 },
        driverTarget = s.driver.target(),
        passengerTarget = s.passenger?.target(),
        // A cabin reading of exactly 0 is what a profile that never sent one returns.
        cabinCelsius = s.cabin?.takeIf { it.value != 0f }?.let { it.celsius(it.value) },
        fanLevel = s.fan?.let { CarPlayClimate.fanLevel(it, s.fanMax ?: FAN_LEVEL_MAX_DEFAULT) },
        driverSeatHeating = s.driverSeat?.takeIf { it >= 0 },
        passengerSeatHeating = s.passengerSeat?.takeIf { it >= 0 },
    )
}

/** A temperature bundle from `com.spd.carinfo`: `value`, `unit` (0 °C, 1 °F) and the panel's `min`, `max`, `step`. */
internal data class SpdTemperature(val value: Float, val unit: Int, val min: Float = 0f, val max: Float = 0f, val step: Float = 0f) {
    fun celsius(raw: Float): Double =
        if (unit == SpdClimateKeys.UNIT_FAHRENHEIT) CarPlayClimate.fahrenheitToCelsius(raw.toDouble()) else raw.toDouble()

    /** As a target: LO and HI are the panel's ends, OFF and nonsense are unknown. */
    fun target(): CarTemperature? = when {
        value == SpdClimateKeys.TEMPERATURE_LO -> CarTemperature.Low
        value == SpdClimateKeys.TEMPERATURE_HI -> CarTemperature.High
        value == SpdClimateKeys.TEMPERATURE_OFF || !value.isFinite() -> null
        else -> CarTemperature.Celsius(celsius(value))
    }

    companion object {
        @Suppress("DEPRECATION")
        fun from(bundle: Bundle?): SpdTemperature? {
            if (bundle == null || !bundle.containsKey("value")) return null
            fun number(key: String) = bundle.get(key) as? Number
            return SpdTemperature(
                value = number("value")?.toFloat() ?: return null,
                unit = number("unit")?.toInt() ?: 0,
                min = number("min")?.toFloat() ?: 0f,
                max = number("max")?.toFloat() ?: 0f,
                step = number("step")?.toFloat() ?: 0f,
            )
        }
    }
}

internal data class SpdClimateSnapshot(
    val power: Int? = null,
    val ac: Int? = null,
    val auto: Int? = null,
    val fan: Int? = null,
    val fanMax: Int? = null,
    val recirculation: Int? = null,
    val acMax: Int? = null,
    val sync: Int? = null,
    val frontDefrost: Int? = null,
    val driver: SpdTemperature,
    val passenger: SpdTemperature? = null,
    val cabin: SpdTemperature? = null,
    val driverSeat: Int? = null,
    val passengerSeat: Int? = null,
)

/** One button press as TEYES's climate widgets send it: [action] is 0, [SpdClimateKeys.FLAG_INCREASE] or DECREASE. */
internal data class SpdPress(val key: Int, val zone: Int, val action: Int = 0)

/**
 * Turns an allow-listed command into button presses. TEYES's set calls are button presses, not values, so each
 * press is planned from the car's latest reading and checked against the next one.
 */
internal object SpdClimatePresses {
    sealed class Step {
        data object Done : Step()
        data class Press(val press: SpdPress) : Step()
        data class Unable(val reason: String) : Step()
    }

    /** How many presses one command may take at most: a toggle one, seat heating a full cycle, a stepper the panel. */
    fun maxPresses(command: CarClimateCommand): Int = when (command) {
        is CarClimateCommand.Switch -> 1
        is CarClimateCommand.SeatHeating -> 4
        is CarClimateCommand.TargetTemperature, is CarClimateCommand.FanLevel -> 36
    }

    fun next(command: CarClimateCommand, s: SpdClimateSnapshot): Step = when (command) {
        is CarClimateCommand.Switch -> {
            val key = SpdClimateKeys.key(command.function)
            when (val on = switchState(command.function, s)) {
                null -> Step.Unable("the car does not report $key")
                command.on -> Step.Done
                else -> Step.Press(SpdPress(key, 0))
            }
        }
        is CarClimateCommand.TargetTemperature -> temperatureStep(command, s)
        is CarClimateCommand.FanLevel -> {
            val current = s.fan
            val target = fanTarget(command.level, s)
            when {
                current == null || current < 0 -> Step.Unable("the car does not report its fan")
                current == target -> Step.Done
                else -> Step.Press(SpdPress(SpdClimateKeys.WIND_LEVEL, SpdClimateKeys.ZONE_DRIVER,
                    if (target > current) SpdClimateKeys.FLAG_INCREASE else SpdClimateKeys.FLAG_DECREASE))
            }
        }
        is CarClimateCommand.SeatHeating -> {
            val zone = SpdClimateKeys.zone(command.zone)
            when (val current = seat(command.zone, s)) {
                null -> Step.Unable("the car does not report seat heating")
                // CarPlay has levels 0–2; a car's third level counts as 2.
                else -> if (current.coerceAtMost(CarPlayClimate.SEAT_HEATING_MAX) == command.level) Step.Done
                else Step.Press(SpdPress(SpdClimateKeys.SEAT_HEATED, zone))
            }
        }
    }

    /** The value a press of [command] changes, to see that the car took it. */
    fun observed(command: CarClimateCommand, s: SpdClimateSnapshot): Any? = when (command) {
        is CarClimateCommand.Switch -> switchState(command.function, s)
        is CarClimateCommand.TargetTemperature -> zoneTemperature(command.zone, s)?.value
        is CarClimateCommand.FanLevel -> s.fan
        is CarClimateCommand.SeatHeating -> seat(command.zone, s)
    }

    /** Whether the car moved toward the target; a seat-heating cycle may pass through other levels. */
    fun progressed(command: CarClimateCommand, before: SpdClimateSnapshot, after: SpdClimateSnapshot): Boolean = when (command) {
        is CarClimateCommand.Switch -> switchState(command.function, after) == command.on
        is CarClimateCommand.SeatHeating -> seat(command.zone, after) != seat(command.zone, before)
        is CarClimateCommand.FanLevel -> {
            val target = fanTarget(command.level, after)
            val was = before.fan ?: return false
            val now = after.fan ?: return false
            abs(now - target) < abs(was - target)
        }
        is CarClimateCommand.TargetTemperature -> {
            val was = zoneTemperature(command.zone, before) ?: return false
            val now = zoneTemperature(command.zone, after) ?: return false
            val target = carTarget(command.celsius, now)
            abs(effective(now) - target) < abs(effective(was) - target)
        }
    }

    private fun switchState(function: CarClimateSwitch, s: SpdClimateSnapshot): Boolean? = when (function) {
        CarClimateSwitch.AC -> s.ac?.let { it > 0 }
        CarClimateSwitch.MAX_AC -> s.acMax?.let { it > 0 }
        CarClimateSwitch.RECIRCULATION -> s.recirculation?.let { it == SpdClimateKeys.RECIRCULATION_INNER }
        CarClimateSwitch.FRONT_DEFROST -> s.frontDefrost?.let { it > 0 }
    }

    private fun seat(zone: CarClimateZone, s: SpdClimateSnapshot): Int? =
        (if (zone == CarClimateZone.DRIVER) s.driverSeat else s.passengerSeat)?.takeIf { it >= 0 }

    private fun zoneTemperature(zone: CarClimateZone, s: SpdClimateSnapshot): SpdTemperature? =
        (if (zone == CarClimateZone.DRIVER) s.driver else s.passenger)?.takeIf { it.max > it.min && it.step > 0f }

    private fun fanTarget(level: Int, s: SpdClimateSnapshot): Int {
        if (level == 0) return 0
        val max = s.fanMax ?: SpdClimateKeys.FAN_LEVEL_MAX_DEFAULT
        return (level.toDouble() * max / CarPlayClimate.FAN_LEVEL_MAX).roundToInt().coerceIn(1, max)
    }

    private fun temperatureStep(command: CarClimateCommand.TargetTemperature, s: SpdClimateSnapshot): Step {
        val t = zoneTemperature(command.zone, s) ?: return Step.Unable("the car does not report this zone's temperature")
        if (t.value == SpdClimateKeys.TEMPERATURE_OFF) return Step.Unable("the zone is off")
        val target = carTarget(command.celsius, t)
        val current = effective(t)
        if (abs(current - target) < t.step / 2) return Step.Done
        val action = if (target > current) SpdClimateKeys.FLAG_INCREASE else SpdClimateKeys.FLAG_DECREASE
        return Step.Press(SpdPress(SpdClimateKeys.TEMPERATURE, SpdClimateKeys.zone(command.zone), action))
    }

    /** [celsius] in the panel's unit, on its steps and inside its range. */
    fun carTarget(celsius: Double, t: SpdTemperature): Double {
        val wanted = if (t.unit == SpdClimateKeys.UNIT_FAHRENHEIT) celsius * 9.0 / 5.0 + 32.0 else celsius
        val steps = ((wanted - t.min) / t.step).roundToInt()
        return (t.min + steps * t.step.toDouble()).coerceIn(t.min.toDouble(), t.max.toDouble())
    }

    /** LO and HI sit one step outside the panel's range. */
    private fun effective(t: SpdTemperature): Double = when (t.value) {
        SpdClimateKeys.TEMPERATURE_LO -> (t.min - t.step).toDouble()
        SpdClimateKeys.TEMPERATURE_HI -> (t.max + t.step).toDouble()
        else -> t.value.toDouble()
    }
}

/**
 * Applies allow-listed commands as TEYES's climate widgets do: a touch down and, [PRESS_MILLIS] later, a touch up
 * through `com.spd.carinfo` `setInt`. After every press it waits for the car's reading to move toward the target
 * and stops at the first press the car does not answer that way.
 */
internal class SpdClimateControl(
    /** The car's climate now, or null when the service cannot say. */
    private val read: () -> SpdClimateSnapshot?,
    /** `setInt(what, value, arg)`; throws when the service does not take it. */
    private val setInt: (what: Int, value: Int, arg: Int) -> Unit,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : HeadUnitClimateControl {
    override fun apply(command: CarClimateCommand): String? {
        var state = read() ?: return "the car reports no climate"
        repeat(SpdClimatePresses.maxPresses(command)) {
            val press = when (val step = SpdClimatePresses.next(command, state)) {
                SpdClimatePresses.Step.Done -> return null
                is SpdClimatePresses.Step.Unable -> return step.reason
                is SpdClimatePresses.Step.Press -> step.press
            }
            press(press)
            val before = state
            state = changed(command, before) ?: return "the car did not answer $press"
            if (!SpdClimatePresses.progressed(command, before, state)) return "the car moved away from the target after $press"
        }
        return if (SpdClimatePresses.next(command, state) == SpdClimatePresses.Step.Done) null
        else "not reached after ${SpdClimatePresses.maxPresses(command)} presses"
    }

    private fun press(press: SpdPress) {
        setInt(press.key, SpdClimateKeys.FLAG_BUTTON_DOWN or press.action, press.zone)
        try {
            sleep(PRESS_MILLIS)
        } finally {
            // Always let go, so no profile is left with a held button.
            setInt(press.key, SpdClimateKeys.FLAG_BUTTON_UP or press.action, press.zone)
        }
    }

    private fun changed(command: CarClimateCommand, before: SpdClimateSnapshot): SpdClimateSnapshot? {
        val was = SpdClimatePresses.observed(command, before)
        repeat((ANSWER_MILLIS / READ_EVERY_MILLIS).toInt()) {
            sleep(READ_EVERY_MILLIS)
            val now = read() ?: return null
            if (SpdClimatePresses.observed(command, now) != was) return now
        }
        return null
    }

    companion object {
        const val PRESS_MILLIS = 120L
        const val READ_EVERY_MILLIS = 150L
        const val ANSWER_MILLIS = 2_100L
    }
}

/** TEYES's climate source: the car info service polled once a second, and presses through the same binding. */
internal class SpdClimateSource(context: Context) : HeadUnitClimateSource {
    private val poller = SpdCarInfoPoller(context, "tarmac-spd-climate") { service ->
        SpdClimateKeys.snapshot(service::getInt, service::temperature)
    }

    override val control: HeadUnitClimateControl = SpdClimateControl(
        read = { poller.client()?.let { SpdClimateKeys.snapshot(it::getInt, it::temperature) } },
        setInt = { what, value, arg -> checkNotNull(poller.client()) { "car info service not connected" }.setInt(what, value, arg) },
    )

    override fun start() = poller.start()

    override fun latest(): CarClimateReading? = poller.latest()?.let(SpdClimateKeys::reading)

    override fun close() = poller.close()
}

/** `com.spd.carinfo.ICarInfoAidlInterface` calls; transaction numbers are in docs/HEADUNIT_SPD_TEYES.md. */
internal class SpdCarInfoClient(private val service: IBinder) {
    private val failed = HashSet<String>()

    fun getInt(what: Int, arg: Int = 0): Int? = call("getInt $what/$arg", TRANSACTION_GET_INT, what, arg) { readInt() }

    fun getBundle(what: Int, arg: Int = 0): Bundle? = call("getBundle $what/$arg", TRANSACTION_GET_BUNDLE, what, arg) {
        if (readInt() != 0) Bundle.CREATOR.createFromParcel(this) else null
    }

    fun temperature(what: Int, arg: Int): SpdTemperature? = SpdTemperature.from(getBundle(what, arg))

    /** A press for TEYES's climate widgets' button convention; throws when the service does not take it. */
    fun setInt(what: Int, value: Int, arg: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(what)
            data.writeInt(value)
            data.writeInt(arg)
            check(service.transact(TRANSACTION_SET_INT, data, reply, 0)) { "setInt $what not handled" }
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun <T> call(name: String, code: Int, what: Int, arg: Int, read: Parcel.() -> T?): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(what)
            data.writeInt(arg)
            if (!service.transact(code, data, reply, 0)) return null
            // A key the profile stores in another form fails its cast inside the service and arrives here.
            reply.readException()
            reply.read()
        } catch (error: Exception) {
            // Polled every second: say it once per key.
            if (synchronized(failed) { failed.add(name) }) Log.w(TAG, "$name failed: ${error.message}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        /** Binds the service; the CAN-box profile and the pollers use it. */
        fun intent(): Intent = Intent(ACTION).setPackage(SpdTeyesPlatform.CAR_INFO_PACKAGE)

        private const val TAG = "Tarmac-HeadUnit"
        private const val ACTION = "com.spd.carinfo.service"
        private const val DESCRIPTOR = "com.spd.carinfo.ICarInfoAidlInterface"
        private const val TRANSACTION_SET_INT = 5
        private const val TRANSACTION_GET_INT = 9
        private const val TRANSACTION_GET_BUNDLE = 12
    }
}
