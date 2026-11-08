package com.shilapi.xcertplay.headunit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.shilapi.xcertplay.airplay.CarClimateCommand
import com.shilapi.xcertplay.airplay.CarClimateReading
import com.shilapi.xcertplay.airplay.CarRadioCommand
import com.shilapi.xcertplay.airplay.CarRadioReading

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

/** A car feature whose support depends on the CAN-box profile the driver chose on the head unit. */
internal enum class HeadUnitCanFeature { GUIDANCE, TRACK_TEXT, DOORS, CLIMATE, OUTSIDE_TEMPERATURE, SURROUND_CAMERA }

/** Whether a CAN-box profile carries a feature; [ROOT] is reachable only through the root test. */
internal enum class HeadUnitSupport { YES, NO, ROOT }

/**
 * The CAN-box profile the head unit is set to. [names] are the unit's own names for the box, make and model, empty
 * when the unit has none; then [protocol] and [variant] are the two numbers inside [id].
 */
internal data class HeadUnitCanProfile(
    val id: Int,
    val names: List<String> = emptyList(),
    val protocol: Int? = null,
    val variant: Int? = null,
    val features: Map<HeadUnitCanFeature, HeadUnitSupport> = emptyMap(),
)

/** Read-only vehicle data from the head unit; [latest] reads a cache and never blocks. */
internal interface HeadUnitVehicleSource : AutoCloseable {
    fun start()
    fun latest(): HeadUnitVehicleReading?
    override fun close()
}

/**
 * The car's climate as the head unit's CAN-box service reports it, for CarPlay's climate (docs/CARPLAY_ULTRA.md).
 * [latest] reads a cache and never blocks.
 */
internal interface HeadUnitClimateSource : AutoCloseable {
    fun start()

    /** Null until the car has reported its climate, and always on cars whose CAN-box profile carries none. */
    fun latest(): CarClimateReading?

    /** How the iPhone's changes reach the car, or null where the head unit offers no way. */
    val control: HeadUnitClimateControl? get() = null

    override fun close()
}

/** Applies allow-listed climate changes through the head unit's own service; see docs/CAR_CONTROLS.md. */
internal interface HeadUnitClimateControl {
    /**
     * Applies [command] and blocks until the car reports it, or stops. Returns null when the car reached the value,
     * otherwise why it stopped; the caller then sends nothing more for the session.
     */
    fun apply(command: CarClimateCommand): String?
}

/** The head unit's own tuner, for CarPlay's Radio app (docs/CARPLAY_ULTRA.md). [latest] reads a cache. */
internal interface HeadUnitRadioSource : AutoCloseable {
    fun start()

    /** Null until the tuner has said where it is. */
    fun latest(): CarRadioReading?

    /** Hands [command] to the unit's radio; false where this unit has no such command. May block briefly. */
    fun command(command: CarRadioCommand): Boolean

    override fun close()
}

/**
 * One family of Android head units: how it announces the car's state and what of it DiPlay may read.
 * Adapters observe settings, keys and getters. They write only what the driver starts: Settings' display tests
 * and, when opted in, a climate control's allow-listed comfort presses through the vendor's own service. Nothing
 * is ever sent to the MCU directly.
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

    /** Whether a rooted unit hands its wheel keys to Tarmac during CarPlay only through a link switch ([FytKeyLink]). */
    val linksKeysWithRoot: Boolean get() = false

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

    /** The car's climate through the CAN-box service, or null where the unit has no climate source DiPlay knows. */
    fun climateSource(context: Context): HeadUnitClimateSource? = null

    /** Whether [climateSource] can also take the iPhone's changes, so Settings offers that switch only here. */
    val writesClimate: Boolean get() = false

    /** The unit's own tuner, or null where DiPlay cannot read it. */
    fun radioSource(context: Context): HeadUnitRadioSource? = null

    /** How Settings' display test hands the unit's CAN-box service a track, or null where it cannot. */
    val trackTestRoute: TrackTestRoute? get() = null

    /** Whether the unit forwards AMap's guidance broadcast to clusters and HUDs, so Settings can test it. */
    val forwardsAmapGuidance: Boolean get() = false

    /** Whether [readCanProfile] can tell which CAN-box profile the unit is set to. */
    val readsCanProfile: Boolean get() = false

    /** The unit's CAN-box profile, or null when it does not say. Blocks for a few seconds; never on the main thread. */
    fun readCanProfile(context: Context): HeadUnitCanProfile? = null
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

/** A short, blocking look at a head-unit service, for reads that Settings makes once. */
internal object HeadUnitBinding {
    /** Binds [intent], hands the service to [use] on the calling thread and unbinds. Never call on the main thread. */
    fun <T> use(context: Context, intent: Intent, timeoutMillis: Long, use: (IBinder) -> T?): T? {
        val service = AtomicReference<IBinder?>()
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service.set(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service.set(null)
            }
        }
        val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w("Tarmac-HeadUnit", "cannot bind ${intent.action}", it) }.getOrDefault(false)
        return try {
            if (bound && connected.await(timeoutMillis, TimeUnit.MILLISECONDS)) service.get()?.let(use) else null
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }
}
