package com.shilapi.xcertplay.headunit

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/** What DiPlay knows about the device before it picks a platform; plain data, so detection is testable. */
internal data class HeadUnitProbe(
    val manufacturer: String,
    val hardware: String,
    /** The platform packages that are installed as system apps on this device. */
    val systemPackages: Set<String>,
)

/** A car state a head unit announces. */
internal enum class HeadUnitSignal { ACC, REVERSE, NIGHT }

/** A CarPlay action one of the head unit's own keys stands for. */
internal enum class HeadUnitKeyAction { SIRI, ANSWER_CALL, END_CALL }

/** Watches the platform's car signals. Each one is true, false, or null while the platform has not said. */
internal interface HeadUnitSignalSource {
    fun current(signal: HeadUnitSignal): Boolean?
    fun start(listener: (HeadUnitSignal, Boolean) -> Unit)
    fun stop()
}

/** What the CAN box reports through the head unit; a null field is unknown, never zero. */
internal data class HeadUnitVehicleReading(
    val speedKmh: Double? = null,
    val engineRpm: Int? = null,
    val outsideTemperatureC: Double? = null,
    val stateOfChargePercent: Double? = null,
    val rangeKm: Int? = null,
    /** The raw value of every key that was read, for the diagnostic report and testing on a car. */
    val raw: Map<String, Int> = emptyMap(),
)

/** Read-only vehicle data from the head unit; [latest] reads a cache and never blocks. */
internal interface HeadUnitVehicleSource : AutoCloseable {
    fun start()
    fun latest(): HeadUnitVehicleReading?
    override fun close()
}

/**
 * One family of Android head units: how it announces the car's state and what of it DiPlay may read.
 * Every adapter is read-only toward the car: it observes settings, keys and getters, and never writes a
 * vehicle setting, never sends to the MCU and never changes the unit's audio source.
 */
internal interface HeadUnitPlatform {
    val id: String

    /** The brand shown in Settings. Not translated. */
    val displayName: String

    /** The packages [matches] looks at; the probe reports which of them are system apps. */
    val detectionPackages: Set<String>

    fun matches(probe: HeadUnitProbe): Boolean

    fun signals(context: Context): HeadUnitSignalSource? = null

    /** Whether [keyAction] knows any of the unit's keys, so Settings offers the switch only where it does something. */
    val mapsWheelKeys: Boolean get() = false

    /** The CarPlay action for a key from the head unit's own wheel or panel, or null to leave the key alone. */
    fun keyAction(keyCode: Int, deviceId: Int): HeadUnitKeyAction? = null

    /** The `/dev/i2c-N` node of the Apple authentication chip the unit's own CarPlay uses, or null. */
    fun mfiI2cPath(): String? = null

    /** The package the unit opens itself when the car starts, or null when it has none or no such setting. */
    fun startingApp(context: Context): String? = null

    /** Whether the unit can open an app at car start, so Settings can explain how to choose DiPlay. */
    val hasStartingAppSetting: Boolean get() = false

    /** The package of the unit's own settings app, for a shortcut. */
    val settingsPackage: String? get() = null

    /** Whether the unit's own CarPlay starts by itself and can take the iPhone first. */
    fun ownCarPlayStartsItself(context: Context): Boolean = false

    fun vehicleSource(context: Context): HeadUnitVehicleSource? = null

    /** How Settings' display test hands the unit's CAN-box service a track, or null where it cannot. */
    val trackTestRoute: TrackTestRoute? get() = null

    /** Whether the unit forwards AMap's guidance broadcast to clusters and HUDs, so Settings can test it. */
    val forwardsAmapGuidance: Boolean get() = false
}

/** The head-unit platforms DiPlay knows. A new platform is one adapter added to [all]. */
internal object HeadUnitPlatforms {
    private const val TAG = "Tarmac-HeadUnit"

    val all: List<HeadUnitPlatform> = listOf(SpdTeyesPlatform, FytSyuPlatform)

    @Volatile private var detectedFor: Context? = null
    @Volatile private var detected: HeadUnitPlatform? = null

    fun detect(probe: HeadUnitProbe): HeadUnitPlatform? = all.firstOrNull { it.matches(probe) }

    /** The platform of this device, or null on a phone or an unknown head unit. Detected once per process. */
    fun current(context: Context): HeadUnitPlatform? {
        val app = context.applicationContext ?: context
        if (detectedFor === app) return detected
        synchronized(this) {
            if (detectedFor !== app) {
                detected = detect(probe(app))
                detectedFor = app
                detected?.let { Log.i(TAG, "head-unit platform ${it.id}") }
            }
            return detected
        }
    }

    fun probe(context: Context): HeadUnitProbe {
        val packages = context.packageManager
        val system = all.flatMap { it.detectionPackages }.toSet().filterTo(mutableSetOf()) { name ->
            runCatching {
                packages.getApplicationInfo(name, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
            }.getOrElse { if (it !is PackageManager.NameNotFoundException) Log.w(TAG, "probe $name", it); false }
        }
        return HeadUnitProbe(Build.MANUFACTURER.orEmpty(), Build.HARDWARE.orEmpty(), system)
    }
}
