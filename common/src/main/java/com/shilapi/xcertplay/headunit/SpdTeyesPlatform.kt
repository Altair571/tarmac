package com.shilapi.xcertplay.headunit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import java.io.File

/**
 * TEYES head units ("SPD": TEYES's own layer on Qualcomm bengal, not Unisoc). Every name and number here was read
 * from the CC4 firmware; see docs/HEADUNIT_SPD_TEYES.md for where each comes from.
 */
internal object SpdTeyesPlatform : HeadUnitPlatform {
    const val CAR_INFO_PACKAGE = "com.spd.carinfo"
    const val SETTING_SERVICE_PACKAGE = "com.spd.setting"
    const val SETTINGS_APP_PACKAGE = "com.spd.spdsettings"
    const val OWN_CARPLAY_PACKAGE = "com.alink.carplay"

    // Settings.System keys written by com.spd.system.service.CarletterInterface.
    const val KEY_ACC = "acc_state"
    const val KEY_REVERSE = "carletter_reserve_state"
    const val KEY_NIGHT = "carletter_night_mode"
    const val KEY_STARTING_APP = "SETTING_LUNCH_FIRST_APP"
    const val KEY_OWN_CARPLAY_AUTO_START = "allowAutoBootCarplay"
    const val KEY_THIRD_UART_BOX = "SETTING_THIRD_UART_BOX"

    /** The node TEYES's CarPlay opens when its alink config names no other bus. */
    const val DEFAULT_MFI_I2C_PATH = "/dev/i2c-2"
    private const val ALINK_CONFIG = "/spd/etc/alink/config.json"

    // com.spd.system.utils.KeyDef: the virtual input devices TEYES injects the wheel and panel keys from,
    // and its own keycodes above Android's.
    private val VIRTUAL_KEY_DEVICES = setOf(
        8192, 8208, 8209, 8210, 8224, 8225, 8240, 8241, 8242, 8256, 8257, 8272, 8273, 8288, 8289, 8304,
    )
    const val KEYCODE_SPD_VOICE = 362
    const val KEYCODE_SPD_PHONE_ON = 424
    const val KEYCODE_SPD_PHONE_OFF = 425

    override val id = "spd-teyes"
    override val displayName = "TEYES"
    override val detectionPackages = setOf(CAR_INFO_PACKAGE, SETTING_SERVICE_PACKAGE, OWN_CARPLAY_PACKAGE)
    override val hasStartingAppSetting = true
    override val settingsPackage = SETTINGS_APP_PACKAGE
    override val mapsWheelKeys = true
    override val trackTestRoute = TrackTestRoute.TEYES_ROOT
    override val forwardsAmapGuidance = true
    override val readsCanProfile = true
    override val writesClimate = true

    override fun matches(probe: HeadUnitProbe): Boolean =
        CAR_INFO_PACKAGE in probe.systemPackages && SETTING_SERVICE_PACKAGE in probe.systemPackages

    override fun signals(context: Context): HeadUnitSignalSource = SettingsSystemSignals(
        context.applicationContext,
        mapOf(HeadUnitSignal.ACC to KEY_ACC, HeadUnitSignal.REVERSE to KEY_REVERSE, HeadUnitSignal.NIGHT to KEY_NIGHT),
    )

    override fun keyAction(keyCode: Int, deviceId: Int): HeadUnitKeyAction? {
        if (deviceId !in VIRTUAL_KEY_DEVICES) return null
        return when (keyCode) {
            KEYCODE_SPD_VOICE, KeyEvent.KEYCODE_VOICE_ASSIST -> HeadUnitKeyAction.SIRI
            KeyEvent.KEYCODE_CALL, KEYCODE_SPD_PHONE_ON -> HeadUnitKeyAction.ANSWER_CALL
            KeyEvent.KEYCODE_ENDCALL, KEYCODE_SPD_PHONE_OFF -> HeadUnitKeyAction.END_CALL
            else -> null
        }
    }

    override fun mfiI2cPath(): String =
        i2cPathFromAlinkConfig(runCatching { File(ALINK_CONFIG).readText() }.getOrNull()) ?: DEFAULT_MFI_I2C_PATH

    /** The bus in TEYES's alink config (`"i2cPort": "2"`), or null when the text names none. */
    fun i2cPathFromAlinkConfig(text: String?): String? {
        val port = text?.let { Regex("\"i2cPort\"\\s*:\\s*\"?(\\d{1,3})\"?").find(it) }?.groupValues?.get(1)
        return port?.let { "/dev/i2c-$it" }
    }

    override fun startingApp(context: Context): String? =
        startingAppFrom(runCatching { Settings.System.getString(context.contentResolver, KEY_STARTING_APP) }.getOrNull())

    /** TEYES stores "NULL" or an empty value when no starting app is chosen. */
    fun startingAppFrom(value: String?): String? =
        value?.trim()?.takeUnless { it.isEmpty() || it.equals("NULL", ignoreCase = true) }

    override fun ownCarPlayStartsItself(context: Context): Boolean =
        runCatching { Settings.System.getInt(context.contentResolver, KEY_OWN_CARPLAY_AUTO_START, 0) == 1 }
            .getOrDefault(false)

    override fun vehicleSource(context: Context): HeadUnitVehicleSource = SpdCarInfoSource(context.applicationContext)

    override fun readCanProfile(context: Context): HeadUnitCanProfile? {
        val app = context.applicationContext
        val thirdUartBox = runCatching { Settings.System.getInt(app.contentResolver, KEY_THIRD_UART_BOX, 0) }
            .getOrDefault(0)
        val navigationBox = (thirdUartBox and 0xff) == SpdCanProfile.NAVIGATION_BOX
        return HeadUnitBinding.use(app, SpdCarInfoClient.intent(), SpdCanProfile.CONNECT_MILLIS) { service ->
            val client = SpdCarInfoClient(service)
            val model = client.getInt(SpdCanProfile.CURRENT_MODEL) ?: return@use null
            SpdCanProfile.profile(model, navigationBox) { what, arg -> SpdCanProfile.namesFrom(client.getBundle(what, arg)) }
        }
    }

    override fun climateSource(context: Context): HeadUnitClimateSource = SpdClimateSource(context.applicationContext)

    override fun radioSource(context: Context): HeadUnitRadioSource = SpdRadioSource(context.applicationContext)
}

/** Car signals a platform publishes as integer Settings.System values, where any value but 0 means on. */
internal class SettingsSystemSignals(
    private val context: Context,
    private val keys: Map<HeadUnitSignal, String>,
) : HeadUnitSignalSource {
    private val observers = mutableListOf<ContentObserver>()
    private val last = mutableMapOf<HeadUnitSignal, Boolean?>()

    override fun current(signal: HeadUnitSignal): Boolean? {
        val key = keys[signal] ?: return null
        return runCatching { Settings.System.getInt(context.contentResolver, key) != 0 }.getOrNull()
    }

    @Synchronized override fun start(listener: (HeadUnitSignal, Boolean) -> Unit) {
        if (observers.isNotEmpty()) return
        val handler = Handler(Looper.getMainLooper())
        for ((signal, key) in keys) {
            last[signal] = current(signal)
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    val now = current(signal) ?: return
                    if (last[signal] == now) return
                    last[signal] = now
                    listener(signal, now)
                }
            }
            runCatching {
                context.contentResolver.registerContentObserver(Settings.System.getUriFor(key), false, observer)
                observers += observer
            }.onFailure { Log.w("Tarmac-HeadUnit", "cannot observe $key", it) }
        }
    }

    @Synchronized override fun stop() {
        observers.forEach { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observers.clear()
    }
}

/** The CAN-box values TEYES's `com.spd.carinfo` service decodes, read with its getters once a second while started. */
internal class SpdCarInfoSource(context: Context) : HeadUnitVehicleSource {
    private val poller = SpdCarInfoPoller(context, "tarmac-spd-carinfo") { service ->
        SpdCarInfoKeys.reading { what -> service.getInt(what) }
    }

    override fun start() = poller.start()

    override fun latest(): HeadUnitVehicleReading? = poller.latest()

    override fun close() = poller.close()
}

/**
 * Binds TEYES's `com.spd.carinfo` service and runs [read] on it once a second while started; [latest] is the last
 * result. Reads call only the service's getters. Its set calls are made only by [SpdClimateControl], and its source
 * calls never.
 */
internal class SpdCarInfoPoller<T : Any>(
    context: Context,
    private val threadName: String,
    private val read: (SpdCarInfoClient) -> T?,
) {
    private val context = context.applicationContext ?: context
    @Volatile private var client: SpdCarInfoClient? = null
    @Volatile private var latest: T? = null
    @Volatile private var bound = false
    private var thread: HandlerThread? = null
    private var poller: Handler? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            client = service?.let(::SpdCarInfoClient)
            poller?.post(poll)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            client = null
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val service = client ?: return
            latest = read(service)
            poller?.postDelayed(this, POLL_MILLIS)
        }
    }

    /** The bound service, or null before it connects and after [close]. */
    fun client(): SpdCarInfoClient? = client

    @Synchronized fun start() {
        if (thread != null) return
        val worker = HandlerThread(threadName).also { it.start() }
        thread = worker
        poller = Handler(worker.looper)
        bound = runCatching {
            context.bindService(SpdCarInfoClient.intent(), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.w(TAG, "car info service unavailable", it) }.getOrDefault(false)
    }

    fun latest(): T? = latest

    @Synchronized fun close() {
        poller?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        poller = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        client = null
        latest = null
    }

    private companion object {
        const val TAG = "Tarmac-HeadUnit"
        const val POLL_MILLIS = 1_000L
    }
}

/** The CarInfo keys DiPlay reads (`CarInfo$<group>` constants in the firmware) and how it trusts their values. */
internal object SpdCarInfoKeys {
    const val SPEED = 100043
    const val ENGINE_RPM = 100042
    const val REMAINING_FUEL = 100045
    const val MILEAGE = 100044
    const val GEAR = 140080
    const val REVERSE = 140011
    const val OUTSIDE_TEMPERATURE = 30023
    const val STATE_OF_CHARGE = 160029
    const val RANGE = 160002

    private val NAMES = linkedMapOf(
        SPEED to "speed", ENGINE_RPM to "rpm", REMAINING_FUEL to "fuel", MILEAGE to "mileage", GEAR to "gear",
        REVERSE to "reverse", OUTSIDE_TEMPERATURE to "outside", STATE_OF_CHARGE to "soc", RANGE to "range",
    )

    /** Units depend on the CAN-box decoder, so a value outside a plausible range is unknown, not a reading. */
    fun reading(read: (Int) -> Int?): HeadUnitVehicleReading {
        val raw = linkedMapOf<String, Int>()
        for ((key, name) in NAMES) read(key)?.let { raw[name] = it }
        return HeadUnitVehicleReading(
            speedKmh = raw["speed"]?.takeIf { it in 0..400 }?.toDouble(),
            engineRpm = raw["rpm"]?.takeIf { it in 1..12_000 },
            outsideTemperatureC = raw["outside"]?.takeIf { it in -60..80 }?.toDouble(),
            stateOfChargePercent = raw["soc"]?.takeIf { it in 1..100 }?.toDouble(),
            rangeKm = raw["range"]?.takeIf { it in 1..3_000 },
            raw = raw,
        )
    }
}

/**
 * The CAN-box model chosen in TEYES's car settings (`CarInfo$Vehicles`). The model ID packs the box (top byte), the
 * make (next byte) and the model (low 16 bits); the service names each from the unit's `/spd/config/CarModel.xml`.
 */
internal object SpdCanProfile {
    const val ALL_CANBOX = 170001
    const val ALL_MAKE = 170002
    const val ALL_MODEL = 170003
    const val CURRENT_MODEL = 170004

    /** The `SETTING_THIRD_UART_BOX` value of the add-on navigation box, which takes guidance whatever the model. */
    const val NAVIGATION_BOX = 1
    const val CONNECT_MILLIS = 3_000L

    fun box(model: Int) = model ushr 24 and 0xff

    fun make(model: Int) = model ushr 16 and 0xff

    fun car(model: Int) = model and 0xffff

    /** The profile of [model], or null for TEYES's "None". [names] answers one name list as value to name. */
    fun profile(model: Int, navigationBox: Boolean, names: (Int, Int) -> Map<Int, String>): HeadUnitCanProfile? {
        if (model == 0) return null
        // The service answers the model list for the box of the last make list, so they are asked for in order.
        val boxName = names(ALL_CANBOX, 0)[box(model)]
        val makeName = boxName?.let { names(ALL_MAKE, box(model))[make(model)] }
        val carName = makeName?.let { names(ALL_MODEL, make(model))[car(model)] }
        val guidance = navigationBox || HeadUnitCanTables.teyesGuidance(model)
        return HeadUnitCanProfile(
            id = model,
            names = listOfNotNull(boxName, makeName, carName).map(String::trim).filter(String::isNotEmpty),
            features = linkedMapOf(
                HeadUnitCanFeature.GUIDANCE to if (guidance) HeadUnitSupport.YES else HeadUnitSupport.NO,
                // TEYES sends track text only for its own sources; Tarmac's track can reach it only as root.
                HeadUnitCanFeature.TRACK_TEXT to
                    if (HeadUnitCanTables.teyesTrackText(model)) HeadUnitSupport.ROOT else HeadUnitSupport.NO,
            ),
        )
    }

    /** A name list (`names` and `values` arrays) as value to name. */
    fun namesFrom(bundle: Bundle?): Map<Int, String> {
        val names = bundle?.getStringArray("names") ?: return emptyMap()
        val values = bundle.getIntArray("values") ?: return emptyMap()
        return values.zip(names).toMap()
    }
}
