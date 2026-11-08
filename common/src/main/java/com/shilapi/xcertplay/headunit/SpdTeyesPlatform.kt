package com.shilapi.xcertplay.headunit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
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

/**
 * The CAN-box values TEYES's `com.spd.carinfo` service decodes, read with its getters only. Its setters and
 * source calls change the car or the unit and are never called. Polls once a second while started.
 */
internal class SpdCarInfoSource(private val context: Context) : HeadUnitVehicleSource {
    @Volatile private var binder: IBinder? = null
    @Volatile private var latest: HeadUnitVehicleReading? = null
    @Volatile private var bound = false
    private var thread: HandlerThread? = null
    private var poller: Handler? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            poller?.post(poll)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val service = binder ?: return
            latest = SpdCarInfoKeys.reading { what -> getInt(service, what) }
            poller?.postDelayed(this, POLL_MILLIS)
        }
    }

    @Synchronized override fun start() {
        if (thread != null) return
        val worker = HandlerThread("tarmac-spd-carinfo").also { it.start() }
        thread = worker
        poller = Handler(worker.looper)
        bound = runCatching {
            context.bindService(
                Intent(ACTION).setPackage(SpdTeyesPlatform.CAR_INFO_PACKAGE), connection, Context.BIND_AUTO_CREATE,
            )
        }.onFailure { Log.w(TAG, "car info service unavailable", it) }.getOrDefault(false)
    }

    override fun latest(): HeadUnitVehicleReading? = latest

    @Synchronized override fun close() {
        poller?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        poller = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        binder = null
    }

    private fun getInt(service: IBinder, what: Int): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(what)
            data.writeInt(0)
            if (!service.transact(TRANSACTION_GET_INT, data, reply, 0)) return null
            reply.readException()
            reply.readInt()
        } catch (error: Exception) {
            Log.w(TAG, "getInt $what failed", error)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val TAG = "Tarmac-HeadUnit"
        const val ACTION = "com.spd.carinfo.service"
        const val DESCRIPTOR = "com.spd.carinfo.ICarInfoAidlInterface"
        const val TRANSACTION_GET_INT = 9
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
