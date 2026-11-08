package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayVehicleState
import com.shilapi.xcertplay.airplay.CarPlayVehicleState.CarData
import com.shilapi.xcertplay.airplay.CarPlayVehicleState.CarReading
import com.shilapi.xcertplay.headunit.HeadUnitPlatforms
import com.shilapi.xcertplay.headunit.HeadUnitVehicleReading
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydTyreReading
import com.shilapi.xcertplay.hud.BydTyreStatus
import com.shilapi.xcertplay.vehicle.VehicleAdapterSettings
import com.shilapi.xcertplay.vehicle.VehicleReading
import com.shilapi.xcertplay.vehicle.VehicleSignal
import com.shilapi.xcertplay.vehicle.VehicleStatusSources
import java.io.Closeable
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CarPlay Ultra experiment: the car's read-only data for iOS's Vehicle app ([CarData]); see docs/CARPLAY_ULTRA.md.
 * Every source is a reader Tarmac already has (BYD's autoservice, the car data adapter, a TEYES unit's CAN box),
 * and nothing here writes to the car.
 *
 * A kind of data is described only where a source for it is set up and has given it at least once on this head
 * unit, so the Vehicle app never shows a gauge nobody fills. Data first read during a connection is described from
 * the next one.
 */
internal object CarPlayCarData {
    private const val TAG = "Tarmac-CarData"
    private const val READ_MILLIS = 2_000L

    /** Sample values for [AirPlayPersistence.loadCarPlayUltraTestCarData] only; never sent from a car. */
    val TEST_READING = CarReading(fuelPercent = 64.0, rangeKm = 312.0, odometerKm = 12_345.0,
        tyrePressureKpa = listOf(230, 232, 228, 231), charging = false, batteryEnergyKwh = 48.2,
        tyreTemperatureC = listOf(24, 25, 23, 24), outsideTemperatureC = 18.0)

    /** The readers set up on this head unit. */
    data class Setup(
        /** BYD's battery reader, on for the iPhone's EV routing: the car is declared electric. */
        val bydBattery: Boolean = false,
        /** The signals in the car data adapter's map; null without an adapter or when BYD's reader wins. */
        val adapterSignals: Set<VehicleSignal>? = null,
        /** The adapter's charge goes to the iPhone, so the car is declared electric. */
        val adapterEv: Boolean = false,
        /** A head unit whose CAN box Tarmac reads (TEYES). */
        val headUnitVehicle: Boolean = false,
    )

    /**
     * What the readers in [setup] can give. The charge counts as the fuel gauge only where the iPhone is already told
     * the car is electric, as LIVI does for electric cars; a TEYES unit's charge stays out, since Tarmac cannot tell an
     * electric car from a plug-in hybrid there. BYD's tyre reader runs everywhere and stops where autoservice is missing;
     * it gives each tyre's temperature with its pressure where the firmware has one (CAN-FD).
     */
    fun available(setup: Setup): Set<CarData> = EnumSet.noneOf(CarData::class.java).apply {
        val adapter = setup.adapterSignals.orEmpty()
        if (setup.bydBattery || setup.adapterEv || VehicleSignal.FUEL in adapter) add(CarData.FUEL_LEVEL)
        if (setup.bydBattery || VehicleSignal.CHARGING in adapter) add(CarData.CHARGING)
        if (setup.bydBattery) add(CarData.BATTERY_ENERGY)
        if (setup.bydBattery || VehicleSignal.RANGE in adapter || setup.headUnitVehicle) add(CarData.RANGE)
        if (VehicleSignal.ODOMETER in adapter) add(CarData.ODOMETER)
        if (setup.headUnitVehicle) add(CarData.OUTSIDE_TEMPERATURE)
        add(CarData.TYRES)
    }

    /** The reading for the iPhone from each source's latest values; BYD or the adapter first, then the head unit. */
    fun reading(vehicle: VehicleReading?, electric: Boolean, headUnit: HeadUnitVehicleReading?, tyres: BydTyreReading?) = CarReading(
        fuelPercent = vehicle?.fuelPercent ?: vehicle?.stateOfChargePercent?.takeIf { electric },
        rangeKm = (vehicle?.rangeKm ?: headUnit?.rangeKm)?.toDouble(),
        odometerKm = vehicle?.odometerKm,
        tyrePressureKpa = tyres?.pressureKpa.orEmpty(),
        charging = vehicle?.charging,
        batteryEnergyKwh = vehicle?.batteryEnergyKwh,
        tyreTemperatureC = tyres?.temperatureC.orEmpty(),
        outsideTemperatureC = headUnit?.outsideTemperatureC,
    )

    /** The kinds of data [reading] has a value for. */
    fun given(reading: CarReading): Set<CarData> = EnumSet.noneOf(CarData::class.java).apply {
        if (reading.fuelPercent != null) add(CarData.FUEL_LEVEL)
        if (reading.rangeKm != null) add(CarData.RANGE)
        if (reading.odometerKm != null) add(CarData.ODOMETER)
        if (reading.tyrePressureKpa.any { it != null }) add(CarData.TYRES)
        if (reading.charging != null) add(CarData.CHARGING)
        if (reading.batteryEnergyKwh != null) add(CarData.BATTERY_ENERGY)
        if (reading.outsideTemperatureC != null) add(CarData.OUTSIDE_TEMPERATURE)
    }

    fun enabled(context: Context): Boolean =
        AirPlayPersistence.loadCarPlayUltra(context) && AirPlayPersistence.loadCarPlayUltraCarData(context)

    fun setup(context: Context): Setup {
        val app = context.applicationContext
        val byd = BydOutputSettings.batteryToIphoneActive(app)
        val adapter = VehicleAdapterSettings.kind(app) != VehicleAdapterSettings.Kind.OFF && !byd
        return Setup(
            bydBattery = byd,
            adapterSignals = if (!adapter) null else VehicleAdapterSettings.signalMap(app).let { map ->
                (map.obd.map { it.decoding.signal } + map.can.map { it.decoding.signal }).toSet()
            },
            adapterEv = adapter && VehicleStatusSources.adapterActive(app),
            headUnitVehicle = HeadUnitPlatforms.current(app)?.vehicleSource(app) != null,
        )
    }

    fun seen(context: Context): Set<CarData> = AirPlayPersistence.loadCarPlayUltraCarDataSeen(context)
        .mapNotNullTo(EnumSet.noneOf(CarData::class.java)) { name -> CarData.entries.firstOrNull { it.name == name } }

    /** What the next connection describes; nothing while the experiment or the setting is off. */
    fun forConnection(context: Context): Set<CarData> = when {
        !enabled(context) -> emptySet()
        AirPlayPersistence.loadCarPlayUltraTestCarData(context) -> EnumSet.allOf(CarData::class.java)
        else -> available(setup(context)).intersect(seen(context))
    }

    /**
     * The Vehicle app's lines for a connection, labelled in the head unit's language and grouped the way a driver
     * reads them: the battery or tank, the tyres (a row per wheel), the car, and Tarmac's own lines last. Only data in
     * [carData] gets a line. Nothing while the experiment is off.
     */
    fun settingLines(context: Context, carData: Set<CarData>): List<CarPlayVehicleState.SettingLine> {
        if (!AirPlayPersistence.loadCarPlayUltra(context)) return emptyList()
        val setup = setup(context)
        // The adapter's tank wins over a charge in the reading, so it names the line and its section too.
        val tank = setup.adapterSignals.orEmpty().contains(VehicleSignal.FUEL)
        fun section(id: String, name: Int) = CarPlayVehicleState.Section(id, context.getString(name))
        val energy = if (tank) section("TarmacFuel", R.string.carplay_vehicle_section_fuel)
            else section("TarmacBattery", R.string.carplay_vehicle_section_battery)
        val tyres = section("TarmacTyres", R.string.carplay_vehicle_section_tyres)
        val car = section("TarmacCar", R.string.carplay_vehicle_section_car)
        fun line(data: CarData, id: String, label: Int, symbol: String, section: CarPlayVehicleState.Section) =
            CarPlayVehicleState.SettingLine(id, context.getString(label), symbol, data = data, section = section,
                yes = context.getString(R.string.carplay_vehicle_yes), no = context.getString(R.string.carplay_vehicle_no))
                .takeIf { data in carData }
        val wheels = listOf(R.string.carplay_vehicle_front_left, R.string.carplay_vehicle_front_right,
            R.string.carplay_vehicle_rear_left, R.string.carplay_vehicle_rear_right).mapIndexedNotNull { wheel, label ->
            line(CarData.TYRES, "TarmacTyre$wheel", label, "tirepressure", tyres)?.copy(wheel = wheel)
        }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val sources = when {
            carData.isEmpty() -> null
            AirPlayPersistence.loadCarPlayUltraTestCarData(context) -> context.getString(R.string.carplay_vehicle_source_test)
            else -> sourceNames(context, setup, carData).joinToString(context.getString(R.string.preview_list_separator))
                .takeIf { it.isNotEmpty() }
        }
        return listOfNotNull(
            if (tank) line(CarData.FUEL_LEVEL, "TarmacFuel", R.string.carplay_vehicle_fuel, "fuelpump", energy)
            else line(CarData.FUEL_LEVEL, "TarmacCharge", R.string.carplay_vehicle_charge, "bolt.car", energy),
            line(CarData.CHARGING, "TarmacCharging", R.string.carplay_vehicle_charging, "powerplug", energy),
            line(CarData.BATTERY_ENERGY, "TarmacEnergy", R.string.carplay_vehicle_energy, "minus.plus.batteryblock", energy),
            line(CarData.RANGE, "TarmacRange", R.string.carplay_vehicle_range, "road.lanes", energy),
        ) + wheels + listOfNotNull(
            line(CarData.ODOMETER, "TarmacOdometer", R.string.carplay_vehicle_odometer, "speedometer", car),
            line(CarData.OUTSIDE_TEMPERATURE, "TarmacOutside", R.string.carplay_vehicle_outside, "thermometer.medium", car),
            CarPlayVehicleState.SettingLine("TarmacVersion", context.getString(R.string.carplay_vehicle_version), "info.circle",
                version.orEmpty()),
            sources?.let {
                CarPlayVehicleState.SettingLine("TarmacSource", context.getString(R.string.carplay_vehicle_source),
                    "cable.connector", it)
            },
        )
    }

    /** The readers the described data comes from, as the driver knows them. */
    private fun sourceNames(context: Context, setup: Setup, carData: Set<CarData>): List<String> {
        val byd = setup.bydBattery && carData.any { it in BYD_BATTERY } || CarData.TYRES in carData
        val adapter = setup.adapterSignals != null && carData.any { it in ADAPTER }
        val headUnit = setup.headUnitVehicle && carData.any { it in HEAD_UNIT }
        return listOfNotNull(
            "BYD".takeIf { byd },
            context.getString(R.string.settings_reverse_camera_source_adapter).takeIf { adapter },
            HeadUnitPlatforms.current(context)?.displayName?.takeIf { headUnit },
        )
    }

    private val BYD_BATTERY = setOf(CarData.FUEL_LEVEL, CarData.CHARGING, CarData.BATTERY_ENERGY, CarData.RANGE)
    private val ADAPTER = setOf(CarData.FUEL_LEVEL, CarData.CHARGING, CarData.RANGE, CarData.ODOMETER)
    private val HEAD_UNIT = setOf(CarData.RANGE, CarData.OUTSIDE_TEMPERATURE)

    /** Feeds [state] every two seconds until closed, and records which data this head unit gives. */
    class Feed(context: Context, private val state: CarPlayVehicleState, private val log: (String) -> Unit) : Closeable {
        private val app = context.applicationContext
        private val test = AirPlayPersistence.loadCarPlayUltraTestCarData(app)
        private val electric = VehicleStatusSources.active(app)
        private val available = available(setup(app))
        private val vehicle = VehicleStatusSources.source(app)
        private val headUnit = HeadUnitPlatforms.current(app)?.vehicleSource(app)?.also { it.start() }
        @Volatile private var seen = seen(app)
        private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "tarmac-carplay-car-data").apply { isDaemon = true }
        }

        init {
            val sources = if (test) "test values" else listOfNotNull(vehicle?.name, headUnit?.let { "head unit" }, "BYD tyres").joinToString()
            log("CarPlay car data: describing ${state.describedCarData} from $sources")
            executor.scheduleWithFixedDelay(::read, 0, READ_MILLIS, TimeUnit.MILLISECONDS)
        }

        private fun read() {
            runCatching {
                val reading = if (test) TEST_READING else {
                    BydTyreStatus.start(app)
                    reading(vehicle?.latest(), electric, headUnit?.latest(), BydTyreStatus.latest())
                }
                state.carReads(reading)
                val newly = given(reading).intersect(available) - seen
                if (test || newly.isEmpty()) return
                seen = seen + newly
                AirPlayPersistence.saveCarPlayUltraCarDataSeen(app, seen.mapTo(HashSet()) { it.name })
                (newly - state.describedCarData).takeIf { it.isNotEmpty() }?.let {
                    log("CarPlay car data: $it read on this head unit; described from the next connection")
                }
            }.onFailure { Log.w(TAG, "car data read failed", it) }
        }

        override fun close() {
            executor.shutdownNow()
            headUnit?.close()
            BydTyreStatus.stop()
        }
    }
}
