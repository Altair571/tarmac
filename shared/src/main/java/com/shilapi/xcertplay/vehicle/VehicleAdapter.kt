package com.shilapi.xcertplay.vehicle

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.transport.EvChargingConnectors
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.io.IOException

/** The driver's choices for an OBD-II or CAN adapter (Settings → Advanced → Car data adapter). */
object VehicleAdapterSettings {
    private const val PREFS = "diplay_vehicle_adapter"
    private const val KEY_KIND = "kind"
    private const val KEY_BLUETOOTH = "bluetooth_address"
    private const val KEY_SIGNAL_MAP = "signal_map"
    private const val KEY_EV_TO_IPHONE = "ev_to_iphone"
    private const val KEY_FULL_RANGE = "full_range_km"
    private const val KEY_CONNECTORS = "charging_connectors"
    private const val KEY_DECLARED = "declared_ev"

    enum class Kind { OFF, OBD_BLUETOOTH, OBD_USB, CAN_USB }

    /** 0 = unknown: the range then has to come from the car. */
    val fullRangePresets = listOf(0, 200, 250, 300, 350, 400, 450, 500, 550, 600, 700)

    fun kind(context: Context): Kind =
        Kind.entries.firstOrNull { it.name == prefs(context).getString(KEY_KIND, null) } ?: Kind.OFF

    fun setKind(context: Context, kind: Kind) {
        prefs(context).edit().putString(KEY_KIND, kind.name).remove(KEY_DECLARED).apply()
        VehicleAdapter.restart(context)
    }

    fun bluetoothAddress(context: Context): String? = prefs(context).getString(KEY_BLUETOOTH, null)

    fun setBluetoothAddress(context: Context, address: String?) {
        prefs(context).edit().apply { if (address == null) remove(KEY_BLUETOOTH) else putString(KEY_BLUETOOTH, address) }
            .remove(KEY_DECLARED).apply()
        VehicleAdapter.restart(context)
    }

    fun signalMapText(context: Context): String? = prefs(context).getString(KEY_SIGNAL_MAP, null)

    /** The parsed map; an unreadable saved map counts as none. */
    fun signalMap(context: Context): VehicleSignalMap =
        VehicleSignalMap.parseOrNull(signalMapText(context)) ?: VehicleSignalMap.EMPTY

    /** Saves [text] after [VehicleSignalMap.parse] accepted it; throws its [IllegalArgumentException] otherwise. */
    fun setSignalMapText(context: Context, text: String?) {
        if (text != null) VehicleSignalMap.parse(text)
        prefs(context).edit().apply { if (text == null) remove(KEY_SIGNAL_MAP) else putString(KEY_SIGNAL_MAP, text) }
            .remove(KEY_DECLARED).apply()
        VehicleAdapter.restart(context)
    }

    /** Tell the iPhone the adapter's charge and range for Apple Maps EV routing; applies on the next connection. */
    fun evToIphone(context: Context): Boolean = prefs(context).getBoolean(KEY_EV_TO_IPHONE, false)

    fun setEvToIphone(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EV_TO_IPHONE, enabled).apply()
    }

    fun fullRangeKm(context: Context): Int? = prefs(context).getInt(KEY_FULL_RANGE, 0).takeIf { it > 0 }

    fun setFullRangeKm(context: Context, km: Int) {
        require(km in fullRangePresets)
        prefs(context).edit().putInt(KEY_FULL_RANGE, km).apply()
    }

    fun chargingConnectors(context: Context): EvChargingConnectors =
        prefs(context).getString(KEY_CONNECTORS, null)
            ?.let { saved -> EvChargingConnectors.entries.firstOrNull { it.name == saved } }
            ?: EvChargingConnectors.CCS2_TYPE2

    fun setChargingConnectors(context: Context, connectors: EvChargingConnectors) {
        prefs(context).edit().putString(KEY_CONNECTORS, connectors.name).apply()
    }

    /** Whether this adapter setup has given a battery reading before; reset whenever the setup changes. */
    internal fun declaredBefore(context: Context): Boolean = prefs(context).getBoolean(KEY_DECLARED, false)

    internal fun markDeclared(context: Context) {
        if (!declaredBefore(context)) prefs(context).edit().putBoolean(KEY_DECLARED, true).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Reads an OBD-II or CAN adapter in the background, the way BYD's battery reader polls autoservice: the iAP2
 * loop and the reverse-gear watcher only ever read the cache. The reader stops two minutes after nobody asked
 * for a reading, and starts again on the next request.
 */
object VehicleAdapter {
    private const val TAG = "DiPlay-VehicleData"
    private const val IDLE_MILLIS = 2 * 60_000L
    private const val RETRY_MILLIS = 10_000L
    private const val POLL_MILLIS = 200L
    private const val SLOW_EVERY_CYCLES = 25
    private const val READING_MAX_AGE_MILLIS = 3 * 60_000L

    /** A gear older than this is unknown: a stuck reverse must never keep CarPlay off the screen. */
    const val GEAR_MAX_AGE_MILLIS = 2_000L
    private const val PUBLISH_MILLIS = 250L

    enum class State { OFF, CONNECTING, NOT_FOUND, NO_PERMISSION, NO_ANSWER, NO_LISTEN_ONLY, CONNECTED }

    @Volatile var state: State = State.OFF
        private set
    @Volatile var detail: String = ""
        private set
    @Volatile private var reading: VehicleReading? = null
    @Volatile private var readingMillis = 0L
    @Volatile private var gearMillis = 0L
    @Volatile private var askedMillis = 0L
    @Volatile private var context: Context? = null
    private var worker: Thread? = null

    /** Starts reading when an adapter is set up; idempotent. */
    @Synchronized
    fun start(appContext: Context) {
        val app = appContext.applicationContext
        context = app
        askedMillis = now()
        if (VehicleAdapterSettings.kind(app) == VehicleAdapterSettings.Kind.OFF) {
            stopLocked()
            return
        }
        if (worker?.isAlive == true) return
        worker = Thread({ run(app) }, "diplay-vehicle-adapter").apply { isDaemon = true; start() }
    }

    /** After a settings change: drop the old link and reading. */
    @Synchronized
    fun restart(appContext: Context) {
        val wasRunning = worker != null
        stopLocked()
        if (wasRunning || askedMillis > 0) start(appContext)
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        worker?.interrupt()
        worker = null
        reading = null
        state = State.OFF
        detail = ""
    }

    /** The latest reading, at most three minutes old. */
    fun latest(): VehicleReading? {
        askedMillis = now()
        context?.let { if (worker == null) start(it) }
        return reading?.takeIf { now() - readingMillis <= READING_MAX_AGE_MILLIS }
    }

    /** The gear as last read, or null when it is unknown or older than [GEAR_MAX_AGE_MILLIS]. */
    fun gear(): VehicleGear? = latest()?.gear?.takeIf { now() - gearMillis <= GEAR_MAX_AGE_MILLIS }

    /** The adapter as a [VehicleDataSource]. */
    fun source(appContext: Context): VehicleDataSource = object : VehicleDataSource {
        override val name: String get() = "adapter ${VehicleAdapterSettings.kind(appContext)} $state $detail".trim()
        override fun latest(): VehicleReading? = this@VehicleAdapter.latest()
    }.also { start(appContext) }

    /** The USB serial adapter, when one is plugged in. */
    fun usbDevice(appContext: Context): UsbDevice? =
        appContext.getSystemService(UsbManager::class.java)?.let(CdcAcmSerialLink::serialDevices)?.firstOrNull()

    /** Shows Android's USB permission dialog for the adapter; the reader connects on its next attempt. */
    fun requestUsbPermission(appContext: Context): Boolean {
        val manager = appContext.getSystemService(UsbManager::class.java) ?: return false
        val device = usbDevice(appContext) ?: return false
        if (manager.hasPermission(device)) return true
        val intent = Intent(USB_PERMISSION_ACTION).setPackage(appContext.packageName)
        manager.requestPermission(device, PendingIntent.getBroadcast(appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return true
    }

    private const val USB_PERMISSION_ACTION = "com.shilapi.xcertplay.vehicle.USB_PERMISSION"

    fun report(appContext: Context): String = buildString {
        append("kind=${VehicleAdapterSettings.kind(appContext)} state=$state detail=$detail")
        append(" map=${VehicleAdapterSettings.signalMap(appContext).let { "${it.name}:${it.obd.size}obd/${it.can.size}can" }}")
        append(" evToIphone=${VehicleAdapterSettings.evToIphone(appContext)} reading=$reading")
    }

    internal fun publish(next: VehicleReading, gearSeen: Boolean, at: Long = now()) {
        reading = next
        readingMillis = at
        if (gearSeen) gearMillis = at
        if (next.stateOfChargePercent != null) context?.let(VehicleAdapterSettings::markDeclared)
    }

    private fun run(app: Context) {
        val me = Thread.currentThread()
        var attempt = 0
        while (alive(me)) {
            if (now() - askedMillis > IDLE_MILLIS) break
            val kind = VehicleAdapterSettings.kind(app)
            if (kind == VehicleAdapterSettings.Kind.OFF) break
            state = State.CONNECTING
            try {
                open(app, kind, attempt++)?.use { link ->
                    when (kind) {
                        VehicleAdapterSettings.Kind.CAN_USB -> listen(me, link, VehicleAdapterSettings.signalMap(app))
                        else -> poll(me, link, VehicleAdapterSettings.signalMap(app))
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (error: IOException) {
                Log.i(TAG, "adapter link lost: ${error.message}")
            } catch (error: SecurityException) {
                state = State.NO_PERMISSION
                Log.w(TAG, "adapter not allowed", error)
            } catch (error: RuntimeException) {
                Log.w(TAG, "adapter failed", error)
            }
            if (state == State.CONNECTED || state == State.CONNECTING) state = State.NOT_FOUND
            try { Thread.sleep(RETRY_MILLIS) } catch (_: InterruptedException) { break }
        }
        synchronized(this) {
            if (worker === me) {
                worker = null
                state = State.OFF
            }
        }
    }

    private fun alive(thread: Thread) = !thread.isInterrupted && synchronized(this) { worker === thread }

    private fun open(app: Context, kind: VehicleAdapterSettings.Kind, attempt: Int): SerialLink? = when (kind) {
        VehicleAdapterSettings.Kind.OBD_BLUETOOTH -> {
            val address = VehicleAdapterSettings.bluetoothAddress(app)
            val permitted = Build.VERSION.SDK_INT < 31 ||
                app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            when {
                !permitted -> null.also { state = State.NO_PERMISSION }
                address == null || adapter == null || !adapter.isEnabled -> null.also { state = State.NOT_FOUND }
                else -> BluetoothSerialLink.open(adapter, address)
            }
        }
        VehicleAdapterSettings.Kind.OBD_USB, VehicleAdapterSettings.Kind.CAN_USB -> {
            val manager = app.getSystemService(UsbManager::class.java)
            val device = manager?.let(CdcAcmSerialLink::serialDevices)?.firstOrNull()
            when {
                manager == null || device == null -> null.also { state = State.NOT_FOUND }
                !manager.hasPermission(device) -> null.also { state = State.NO_PERMISSION }
                // Genuine ELM327 USB adapters start at 38400 baud, STN ones at 115200; slcan ignores the rate.
                else -> CdcAcmSerialLink.open(manager, device, if (attempt % 2 == 0) 38_400 else 115_200)
            }
        }
        VehicleAdapterSettings.Kind.OFF -> null
    }

    private fun poll(thread: Thread, link: SerialLink, map: VehicleSignalMap) {
        val elm = Elm327(link)
        if (!elm.initialize()) {
            state = State.NO_ANSWER
            return
        }
        val soc = Elm327.PID_HYBRID_BATTERY_REMAINING in elm.supportedPids()
        state = State.CONNECTED
        detail = listOfNotNull(elm.version, "pid5B=$soc", map.name.takeIf { it.isNotEmpty() }).joinToString(" ")
        Log.i(TAG, "OBD-II adapter ready: $detail")
        var current = VehicleReading()
        var cycle = 0
        while (alive(thread)) {
            val slow = cycle++ % SLOW_EVERY_CYCLES == 0
            if (slow && soc) elm.query("015B", null)?.let(Elm327::stateOfCharge)?.let {
                current = current.copy(stateOfChargePercent = it)
            }
            var gearSeen = false
            for (signal in map.obd) {
                if (!signal.decoding.signal.fast && !slow) continue
                val raw = elm.query(signal.request, signal.header)?.let { CanBits.bytes(it, signal.byte, signal.length, signal.signed) }
                current = if (raw != null) signal.decoding.apply(raw, current) else forget(current, signal.decoding.signal)
                if (raw != null && signal.decoding.signal == VehicleSignal.GEAR) gearSeen = true
            }
            publish(current, gearSeen)
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun listen(thread: Thread, link: SerialLink, map: VehicleSignalMap) {
        val slcan = Slcan(link)
        if (!slcan.openListenOnly(map.bitrate)) {
            state = State.NO_LISTEN_ONLY
            Log.w(TAG, "the CAN adapter refused listen-only mode; DiPlay stays off the bus")
            return
        }
        state = State.CONNECTED
        detail = listOfNotNull("slcan ${map.bitrate}", map.name.takeIf { it.isNotEmpty() }).joinToString(" ")
        Log.i(TAG, "CAN adapter listening: $detail")
        val byId = map.can.groupBy { it.id to it.extended }
        var current = VehicleReading()
        var gearSeen = false
        var published = 0L
        try {
            while (alive(thread)) {
                val frame = slcan.readFrame(PUBLISH_MILLIS.toInt())
                byId[frame?.let { it.id to it.extended }]?.forEach { signal ->
                    CanBits.extract(frame!!.data, signal)?.let { raw ->
                        current = signal.decoding.apply(raw, current)
                        if (signal.decoding.signal == VehicleSignal.GEAR) gearSeen = true
                    }
                }
                if (now() - published >= PUBLISH_MILLIS) {
                    publish(current, gearSeen)
                    published = now()
                    gearSeen = false
                }
            }
        } finally {
            slcan.close()
        }
    }

    private fun forget(reading: VehicleReading, signal: VehicleSignal): VehicleReading = when (signal) {
        VehicleSignal.GEAR -> reading.copy(gear = null)
        VehicleSignal.SPEED -> reading.copy(speedKmh = null)
        else -> reading // a missed slow answer keeps the last value until it ages out
    }

    private fun now() = SystemClock.elapsedRealtime()
}

/**
 * Which source gives the iPhone its VehicleStatus. BYD's autoservice reader keeps priority where it is on;
 * otherwise an adapter whose EV data the driver turned on.
 */
object VehicleStatusSources {
    fun adapterActive(context: Context): Boolean =
        VehicleAdapterSettings.kind(context) != VehicleAdapterSettings.Kind.OFF && VehicleAdapterSettings.evToIphone(context)

    fun active(context: Context): Boolean = BydOutputSettings.batteryToIphoneActive(context) || adapterActive(context)

    /** The provider for this connection, or null when the iPhone is not told about an EV. Starts its reader. */
    fun provider(context: Context): VehicleStatusProvider? = when {
        BydOutputSettings.batteryToIphoneActive(context) -> BydNavigationOutputs.batteryStatus(context)
        adapterActive(context) -> adapterProvider(context.applicationContext)
        else -> null
    }

    fun chargingConnectors(context: Context): EvChargingConnectors =
        if (BydOutputSettings.batteryToIphoneActive(context)) BydOutputSettings.chargingConnectors(context)
        else VehicleAdapterSettings.chargingConnectors(context)

    /** The source of the vehicle's data for this head unit, BYD's when it is on. */
    fun source(context: Context): VehicleDataSource? = when {
        BydOutputSettings.batteryToIphoneActive(context) -> BydVehicleDataSource(context.applicationContext)
        VehicleAdapterSettings.kind(context) != VehicleAdapterSettings.Kind.OFF -> VehicleAdapter.source(context)
        else -> null
    }

    private fun adapterProvider(app: Context): VehicleStatusProvider {
        VehicleAdapter.start(app)
        return object : VehicleStatusProvider {
            override fun snapshot(): VehicleStatusSnapshot? = VehicleAdapter.latest()?.let {
                VehicleStatusMapping.snapshot(it, BydOutputSettings.DEFAULT_LOW_CHARGE_PERCENT, VehicleAdapterSettings.fullRangeKm(app))
            }

            // An adapter takes seconds to find the car's protocol, often longer than the iPhone takes to connect.
            // Once this setup has read the battery, the EV is declared and the first live reading follows.
            override fun declarable(): Boolean = snapshot() != null || VehicleAdapterSettings.declaredBefore(app)
        }
    }
}

/** BYD's existing battery reader (autoservice over ADB, see BydBattery) as a [VehicleDataSource]. */
class BydVehicleDataSource(private val context: Context) : VehicleDataSource {
    override val name = "BYD autoservice"

    override fun latest(): VehicleReading? = BydNavigationOutputs.batteryStatus(context).snapshot()?.let {
        VehicleReading(stateOfChargePercent = it.batteryPercent, rangeKm = it.rangeKm, charging = it.charging)
    }
}
