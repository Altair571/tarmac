package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.io.Closeable
import kotlin.math.roundToInt

/** What DiPlay knows about the car right now; a null field is unknown, never zero. */
data class VehicleReading(
    val stateOfChargePercent: Double? = null,
    val rangeKm: Int? = null,
    val charging: Boolean? = null,
    val gear: VehicleGear? = null,
    val speedKmh: Double? = null,
    /** The fuel tank, in percent. */
    val fuelPercent: Double? = null,
    val odometerKm: Double? = null,
    /** Energy left in the traction battery. */
    val batteryEnergyKwh: Double? = null,
) {
    /** [newer]'s known fields win; a field it does not know keeps this reading's value. */
    fun updatedBy(newer: VehicleReading): VehicleReading = VehicleReading(
        stateOfChargePercent = newer.stateOfChargePercent ?: stateOfChargePercent,
        rangeKm = newer.rangeKm ?: rangeKm,
        charging = newer.charging ?: charging,
        gear = newer.gear ?: gear,
        speedKmh = newer.speedKmh ?: speedKmh,
        fuelPercent = newer.fuelPercent ?: fuelPercent,
        odometerKm = newer.odometerKm ?: odometerKm,
        batteryEnergyKwh = newer.batteryEnergyKwh ?: batteryEnergyKwh,
    )
}

/**
 * A read-only source of vehicle data: BYD's autoservice over ADB, an ELM327/STN OBD-II adapter, or an slcan
 * USB CAN adapter in listen-only mode. No source ever writes vehicle state or sends a frame of its own onto a
 * CAN bus; an OBD-II adapter sends only the diagnostic read requests that [Elm327Requests] allows.
 */
interface VehicleDataSource : Closeable {
    /** A short name for the diagnostic report. */
    val name: String

    /** The latest reading; reads a cache and never blocks. Null while nothing is known. */
    fun latest(): VehicleReading?

    override fun close() = Unit
}

/** Turns a reading into what CarPlay's VehicleStatusUpdate carries for Apple Maps' EV routing. */
object VehicleStatusMapping {
    /**
     * Null without a state of charge, or without any way to know the range: the iPhone must not be told about
     * an EV whose range DiPlay would invent. [fullRangeKm] is the driver's figure for a full battery; it gives
     * the range when the car reports only its charge, and the maximum range otherwise.
     */
    fun snapshot(reading: VehicleReading, lowPercent: Int, fullRangeKm: Int?): VehicleStatusSnapshot? {
        val percent = reading.stateOfChargePercent?.takeIf { it in 0.0..100.0 } ?: return null
        val fraction = percent / 100
        val range = reading.rangeKm?.takeIf { it in 0..3000 }
            ?: fullRangeKm?.let { (it * fraction).roundToInt() }
            ?: return null
        val maxRange = fullRangeKm ?: if (fraction > 0) (range / fraction).roundToInt() else range
        return VehicleStatusSnapshot(
            rangeKm = range,
            rangeWarning = percent <= lowPercent,
            batteryPercent = percent,
            // No source here knows the battery's energy; unknown energy is omitted, never sent as zero.
            currentChargeWh = null,
            maxChargeWh = null,
            maxRangeKm = maxOf(maxRange, range),
            charging = reading.charging ?: false,
        )
    }
}
