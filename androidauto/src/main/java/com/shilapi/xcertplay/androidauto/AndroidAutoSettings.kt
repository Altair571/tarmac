// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto

import android.content.Context
import com.shilapi.xcertplay.androidauto.aap.AndroidAutoResolution
import java.util.Locale
import java.util.UUID

/** Night mode for Android Auto's maps and interface. */
enum class AndroidAutoNight { FOLLOW_ANDROID, DAY, NIGHT }

/** Whose GPS Android Auto navigates with. */
enum class AndroidAutoGpsSource { PHONE, HEAD_UNIT }

/** The driver's Android Auto choices. Picture, density, frame rate and sound apply at the next connection. */
object AndroidAutoSettings {
    private const val PREFERENCES = "android_auto"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_DENSITY = "density"
    private const val KEY_SIXTY_FPS = "sixty_fps"
    private const val KEY_SEPARATE_STREAMS = "separate_streams"
    private const val KEY_NIGHT = "night"
    private const val KEY_GPS_SOURCE = "gps_source"
    private const val KEY_OPEN_WHEN_PLUGGED_IN = "open_when_plugged_in"
    private const val KEY_RECONNECT = "reconnect"
    private const val KEY_BLUETOOTH_START = "bluetooth_start"
    private const val KEY_WIRELESS_PHONES = "wireless_phones"
    private const val KEY_LAST_CONNECT_MILLIS = "last_connect_millis"
    private const val KEY_VEHICLE_ID = "vehicle_id"

    /** The densities offered besides automatic; Android Auto lays out for 120 to 320 dpi. */
    val DENSITIES = listOf(120, 140, 160, 180, 200, 240, 280, 320)

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Null means automatic: the largest size this screen shows sharply. */
    fun resolution(context: Context): AndroidAutoResolution? =
        preferences(context).getString(KEY_RESOLUTION, null)?.let { name -> AndroidAutoResolution.entries.firstOrNull { it.name == name } }

    fun setResolution(context: Context, resolution: AndroidAutoResolution?) {
        preferences(context).edit().putString(KEY_RESOLUTION, resolution?.name).apply()
    }

    /** Null means automatic: from the panel's real density, or a size-based default where it reports none. */
    fun density(context: Context): Int? = preferences(context).getInt(KEY_DENSITY, 0).takeIf { it in DENSITIES }

    fun setDensity(context: Context, density: Int?) {
        preferences(context).edit().putInt(KEY_DENSITY, density ?: 0).apply()
    }

    /** 30 fps by default: head-unit decoders keep up with it, and Android Auto's maps need no more. */
    fun sixtyFps(context: Context): Boolean = preferences(context).getBoolean(KEY_SIXTY_FPS, false)

    fun setSixtyFps(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_SIXTY_FPS, enabled).apply()
    }

    /**
     * Guidance and system sounds on their own Android streams (navigation guidance, sonification),
     * so the car can mix them over music. Off plays everything on the media stream, for head units
     * that only unmute that one.
     */
    fun separateStreams(context: Context): Boolean = preferences(context).getBoolean(KEY_SEPARATE_STREAMS, true)

    fun setSeparateStreams(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_SEPARATE_STREAMS, enabled).apply()
    }

    fun night(context: Context): AndroidAutoNight =
        preferences(context).getString(KEY_NIGHT, null)?.let { name -> AndroidAutoNight.entries.firstOrNull { it.name == name } }
            ?: AndroidAutoNight.FOLLOW_ANDROID

    /** Applies at once to a running session. */
    fun setNight(context: Context, night: AndroidAutoNight) {
        preferences(context).edit().putString(KEY_NIGHT, night.name).apply()
        AndroidAutoProjection.refreshNight(context)
    }

    /**
     * The phone's own GPS by default. The head unit's is better where its antenna has the sky and
     * the phone lies in a pocket or a console; it applies at the next connection, because the
     * location sensor is announced only then.
     */
    fun gpsSource(context: Context): AndroidAutoGpsSource =
        preferences(context).getString(KEY_GPS_SOURCE, null)?.let { name -> AndroidAutoGpsSource.entries.firstOrNull { it.name == name } }
            ?: AndroidAutoGpsSource.PHONE

    fun setGpsSource(context: Context, source: AndroidAutoGpsSource) {
        preferences(context).edit().putString(KEY_GPS_SOURCE, source.name).apply()
    }

    /** Open Android Auto when an Android phone is plugged in; on by default, as for CarPlay. */
    fun openWhenPluggedIn(context: Context): Boolean = preferences(context).getBoolean(KEY_OPEN_WHEN_PLUGGED_IN, true)

    fun setOpenWhenPluggedIn(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_OPEN_WHEN_PLUGGED_IN, enabled).apply()
    }

    /** Connect again, with growing waits, when a session fails while the phone stays plugged in. */
    fun reconnect(context: Context): Boolean = preferences(context).getBoolean(KEY_RECONNECT, true)

    fun setReconnect(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_RECONNECT, enabled).apply()
    }

    /**
     * Start wireless Android Auto when a phone that has used it here before connects over Bluetooth.
     * Off by default: wireless is experimental, and it raises a Wi-Fi network on every match.
     */
    fun bluetoothStart(context: Context): Boolean = preferences(context).getBoolean(KEY_BLUETOOTH_START, false)

    fun setBluetoothStart(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_BLUETOOTH_START, enabled).apply()
    }

    /** Bluetooth addresses of the phones that have completed wireless Android Auto here, upper case. */
    fun wirelessPhones(context: Context): Set<String> =
        preferences(context).getStringSet(KEY_WIRELESS_PHONES, null)?.toSet().orEmpty()

    internal fun rememberWirelessPhone(context: Context, address: String) {
        val phones = wirelessPhones(context)
        val normalized = address.uppercase(Locale.ROOT)
        if (normalized !in phones) preferences(context).edit().putStringSet(KEY_WIRELESS_PHONES, phones + normalized).apply()
    }

    fun forgetWirelessPhones(context: Context) {
        preferences(context).edit().remove(KEY_WIRELESS_PHONES).apply()
    }

    /** From plug-in (or the Open button) to the first picture, on the last connection that got one. */
    fun lastConnectMillis(context: Context): Long? = preferences(context).getLong(KEY_LAST_CONNECT_MILLIS, 0).takeIf { it > 0 }

    internal fun setLastConnectMillis(context: Context, millis: Long) {
        preferences(context).edit().putLong(KEY_LAST_CONNECT_MILLIS, millis).apply()
    }

    /**
     * The driving side is CarPlay's own setting (Settings → Vehicle → Right-hand drive), so one
     * choice places both interfaces. Read from its preference to keep a single source of truth.
     */
    fun rightHandDrive(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(CARPLAY_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(CARPLAY_RIGHT_HAND_DRIVE, false)

    /** `AirPlayPersistence`'s file and key in `common/`; `AndroidAutoSettingsTest` keeps them in step. */
    internal const val CARPLAY_PREFERENCES = "xcertplay_airplay"
    internal const val CARPLAY_RIGHT_HAND_DRIVE = "right_hand_drive"

    /** A stable random identifier, so the phone recognises this head unit as the same car. */
    internal fun vehicleId(context: Context): String {
        val preferences = preferences(context)
        preferences.getString(KEY_VEHICLE_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { preferences.edit().putString(KEY_VEHICLE_ID, it).apply() }
    }
}
