package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.VehicleGear
import org.json.JSONArray
import org.json.JSONObject

/** The signals DiPlay can use. Each maps to one [VehicleReading] field. */
enum class VehicleSignal(val key: String) {
    STATE_OF_CHARGE("soc"),
    RANGE("range_km"),
    CHARGING("charging"),
    GEAR("gear"),
    SPEED("speed_kmh"),
    FUEL("fuel_pct"),
    ODOMETER("odometer_km"),
    ;

    /** Gear and speed change by the second; the rest is polled less often over OBD-II. */
    val fast: Boolean get() = this == GEAR || this == SPEED

    companion object {
        fun of(key: String): VehicleSignal? = entries.firstOrNull { it.key == key }
    }
}

/** How a raw number becomes a reading: `raw * scale + offset`, or a gear letter from [gears]. */
data class SignalDecoding(
    val signal: VehicleSignal,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    /** Raw value to P, R, N or D; only for [VehicleSignal.GEAR]. */
    val gears: Map<Long, VehicleGear> = emptyMap(),
    /** For [VehicleSignal.CHARGING]: the raw values that mean charging. Default: any value but 0. */
    val chargingValues: Set<Long> = emptySet(),
) {
    fun apply(raw: Long, into: VehicleReading): VehicleReading {
        val value = raw * scale + offset
        return when (signal) {
            VehicleSignal.STATE_OF_CHARGE -> into.copy(stateOfChargePercent = value.takeIf { it in 0.0..100.0 })
            VehicleSignal.RANGE -> into.copy(rangeKm = value.takeIf { it in 0.0..3000.0 }?.toInt())
            VehicleSignal.CHARGING -> into.copy(charging = if (chargingValues.isEmpty()) raw != 0L else raw in chargingValues)
            VehicleSignal.GEAR -> gears[raw]?.let { into.copy(gear = it) } ?: into
            VehicleSignal.SPEED -> into.copy(speedKmh = value.takeIf { it in 0.0..400.0 })
            VehicleSignal.FUEL -> into.copy(fuelPercent = value.takeIf { it in 0.0..100.0 })
            VehicleSignal.ODOMETER -> into.copy(odometerKm = value.takeIf { it in 0.0..2_000_000.0 })
        }
    }
}

/**
 * A diagnostic read request for an ELM327/STN adapter: `header` is the CAN id the request goes to (`7E4`
 * for a battery ECU, for example; null keeps the adapter's broadcast `7DF`), `request` the service and PID
 * in hex (`015B`, `220105`). [byte] counts from the first data byte after the echoed service and PID, as
 * Torque-style PID lists do (A = 0, B = 1).
 */
data class ObdSignal(
    val decoding: SignalDecoding,
    val header: String?,
    val request: String,
    val byte: Int,
    val length: Int = 1,
    val signed: Boolean = false,
)

/**
 * A signal inside a CAN frame, as a DBC file describes it: [startBit] in DBC numbering, little-endian
 * (Intel, `@1`) or big-endian (Motorola, `@0`, start bit = most significant bit).
 */
data class CanSignal(
    val decoding: SignalDecoding,
    val id: Int,
    val extended: Boolean,
    val startBit: Int,
    val lengthBits: Int,
    val bigEndian: Boolean = false,
    val signed: Boolean = false,
)

/**
 * The driver's description of their car, as JSON (docs/VEHICLE_DATA.md):
 *
 * ```
 * { "name": "...", "bitrate": 500000,
 *   "obd": [ { "signal": "soc", "header": "7E4", "request": "220105", "byte": 32, "scale": 0.5 } ],
 *   "can": [ { "signal": "gear", "id": "0x111", "start": 8, "length": 4, "endian": "little",
 *              "values": { "0": "P", "1": "R", "2": "N", "3": "D" } } ] }
 * ```
 *
 * An OBD request outside the read-only services in [Elm327Requests] makes the whole map invalid.
 */
data class VehicleSignalMap(
    val name: String = "",
    val bitrate: Int = DEFAULT_BITRATE,
    val obd: List<ObdSignal> = emptyList(),
    val can: List<CanSignal> = emptyList(),
) {
    companion object {
        const val DEFAULT_BITRATE = 500_000
        val EMPTY = VehicleSignalMap()

        /** Throws [IllegalArgumentException] with a reason the driver can act on. */
        fun parse(text: String): VehicleSignalMap {
            val root = try { JSONObject(text) } catch (error: org.json.JSONException) {
                throw IllegalArgumentException("not JSON: ${error.message}")
            }
            val bitrate = root.optInt("bitrate", DEFAULT_BITRATE)
            require(Slcan.bitrateCommand(bitrate) != null) { "bitrate $bitrate is not one slcan supports" }
            return VehicleSignalMap(
                name = root.optString("name", ""),
                bitrate = bitrate,
                obd = objects(root.optJSONArray("obd")).mapIndexed { index, entry -> obd(entry, "obd[$index]") },
                can = objects(root.optJSONArray("can")).mapIndexed { index, entry -> can(entry, "can[$index]") },
            )
        }

        fun parseOrNull(text: String?): VehicleSignalMap? = text?.let { runCatching { parse(it) }.getOrNull() }

        private fun objects(array: JSONArray?): List<JSONObject> =
            (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }

        private fun decoding(entry: JSONObject, where: String): SignalDecoding {
            val signal = VehicleSignal.of(entry.optString("signal")) ?: throw IllegalArgumentException(
                "$where: signal must be one of ${VehicleSignal.entries.joinToString { it.key }}")
            val gears = entry.optJSONObject("values")?.let { values ->
                values.keys().asSequence().associate { raw ->
                    val letter = values.getString(raw).trim().uppercase()
                    val gear = VehicleGear.entries.firstOrNull { it.letter.toString() == letter }
                        ?: throw IllegalArgumentException("$where: gear $letter must be P, R, N or D")
                    (raw.toLongOrNull() ?: throw IllegalArgumentException("$where: value key $raw is not a number")) to gear
                }
            }.orEmpty()
            require(signal != VehicleSignal.GEAR || gears.isNotEmpty()) { "$where: a gear signal needs \"values\"" }
            val charging = entry.optJSONArray("charging_values")?.let { array ->
                (0 until array.length()).map { array.getLong(it) }.toSet()
            }.orEmpty()
            return SignalDecoding(signal, entry.optDouble("scale", 1.0), entry.optDouble("offset", 0.0), gears, charging)
        }

        private fun obd(entry: JSONObject, where: String): ObdSignal {
            val request = entry.optString("request").trim().uppercase()
            require(Elm327Requests.allowed(request)) {
                "$where: request $request is not a read; only services ${Elm327Requests.READ_SERVICES.joinToString()} are allowed"
            }
            val header = entry.optString("header", "").trim().uppercase().takeIf { it.isNotEmpty() }
            require(header == null || Elm327Requests.validHeader(header)) { "$where: header $header must be 3 or 8 hex digits" }
            val byte = entry.optInt("byte", -1)
            val length = entry.optInt("length", 1)
            require(byte >= 0) { "$where: byte must be 0 or more" }
            require(length in 1..4) { "$where: length must be 1 to 4 bytes" }
            return ObdSignal(decoding(entry, where), header, request, byte, length, entry.optBoolean("signed", false))
        }

        private fun can(entry: JSONObject, where: String): CanSignal {
            val idText = entry.opt("id")?.toString()?.trim() ?: throw IllegalArgumentException("$where: id is missing")
            val id = (if (idText.startsWith("0x", ignoreCase = true)) idText.drop(2).toIntOrNull(16) else idText.toIntOrNull())
                ?: throw IllegalArgumentException("$where: id $idText is not a number")
            val extended = entry.optBoolean("extended", id > 0x7FF)
            require(id >= 0 && id <= (if (extended) 0x1FFFFFFF else 0x7FF)) { "$where: id $idText is out of range" }
            val start = entry.optInt("start", -1)
            val length = entry.optInt("length", -1)
            require(start in 0..63) { "$where: start must be 0 to 63" }
            require(length in 1..32) { "$where: length must be 1 to 32 bits" }
            val endian = entry.optString("endian", "little").lowercase()
            require(endian == "little" || endian == "big") { "$where: endian must be little or big" }
            return CanSignal(decoding(entry, where), id, extended, start, length, endian == "big", entry.optBoolean("signed", false))
        }
    }
}

/** Bit extraction for [CanSignal], kept apart so it is easy to test against DBC examples. */
object CanBits {
    /** The raw value, or null when the frame is too short for the signal. */
    fun extract(data: ByteArray, signal: CanSignal): Long? {
        fun bit(index: Int): Long? = data.getOrNull(index / 8)?.let { ((it.toInt() shr (index % 8)) and 1).toLong() }
        var raw = 0L
        if (signal.bigEndian) {
            var position = signal.startBit
            repeat(signal.lengthBits) {
                raw = (raw shl 1) or (bit(position) ?: return null)
                // Motorola order runs from bit 7 down to bit 0 of a byte, then on to bit 7 of the next byte.
                position = if (position % 8 == 0) position + 15 else position - 1
            }
        } else {
            for (offset in 0 until signal.lengthBits) raw = raw or ((bit(signal.startBit + offset) ?: return null) shl offset)
        }
        return if (signal.signed) signExtend(raw, signal.lengthBits) else raw
    }

    fun signExtend(raw: Long, bits: Int): Long = if (bits in 1..63 && raw and (1L shl (bits - 1)) != 0L) raw - (1L shl bits) else raw

    /** An unsigned or signed big-endian number of [length] bytes from [offset]; null when the data is short. */
    fun bytes(data: ByteArray, offset: Int, length: Int, signed: Boolean): Long? {
        if (offset < 0 || offset + length > data.size) return null
        var raw = 0L
        for (index in offset until offset + length) raw = (raw shl 8) or (data[index].toLong() and 0xFF)
        return if (signed) signExtend(raw, length * 8) else raw
    }
}
