// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto.wireless

import android.Manifest
import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.androidauto.AndroidAutoLog
import com.shilapi.xcertplay.androidauto.AndroidAutoSettings
import com.shilapi.xcertplay.network.ExistingWifiManager
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * The Wi-Fi network the phone joins for wireless Android Auto: the one wireless CarPlay is set up
 * with (Settings → Connection), so a head unit that needs its car hotspot for CarPlay uses it here
 * too. Without a usable CarPlay choice it is a Wi-Fi Direct group, which needs no setup.
 *
 * Read from CarPlay's own preferences, as the driving side is, to keep one source of truth;
 * `AndroidAutoSettingsTest` in `common/` keeps the keys in step with `AirPlayPersistence`.
 */
object AndroidAutoWifiNetwork {
    internal const val KEY_MODE = "wireless_hotspot_mode"
    internal const val KEY_P2P_CHANNEL = "wifi_p2p_preferred_channel"
    internal const val KEY_HOTSPOT_SSID = "manual_hotspot_ssid"
    internal const val KEY_HOTSPOT_PASSPHRASE = "manual_hotspot_passphrase"
    internal const val KEY_HOTSPOT_BAND = "manual_hotspot_band"
    internal const val KEY_HOTSPOT_CHANNEL = "manual_hotspot_channel"
    internal const val KEY_HOTSPOT_SECURITY = "manual_hotspot_security"
    internal const val KEY_HOTSPOT_INTERFACE = "manual_hotspot_interface"
    internal const val KEY_EXISTING_SSID = "existing_wifi_ssid"
    internal const val KEY_EXISTING_PASSPHRASE = "existing_wifi_passphrase"
    internal const val KEY_EXISTING_INTERFACE = "existing_wifi_interface"

    private fun carPlay(context: Context) =
        context.applicationContext.getSharedPreferences(AndroidAutoSettings.CARPLAY_PREFERENCES, Context.MODE_PRIVATE)

    /** The kind of network [manager] makes. A car hotspot or Wi-Fi that was never filled in falls back to Wi-Fi Direct. */
    fun mode(context: Context): WirelessHotspotMode {
        val preferences = carPlay(context)
        return when (WirelessHotspotMode.entries.firstOrNull { it.name == preferences.getString(KEY_MODE, null) }) {
            // CarPlay offers no local-only hotspot any more and reads one as the car hotspot.
            WirelessHotspotMode.MANUAL, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT ->
                if (preferences.getString(KEY_HOTSPOT_SSID, null).isNullOrBlank()) WirelessHotspotMode.WIFI_P2P
                else WirelessHotspotMode.MANUAL
            WirelessHotspotMode.EXISTING_WIFI ->
                if (preferences.getString(KEY_EXISTING_SSID, null).isNullOrBlank()) WirelessHotspotMode.WIFI_P2P
                else WirelessHotspotMode.EXISTING_WIFI
            WirelessHotspotMode.WIFI_P2P, null -> WirelessHotspotMode.WIFI_P2P
        }
    }

    fun manager(context: Context): WirelessHotspotManager {
        val app = context.applicationContext
        val preferences = carPlay(app)
        val log: (String) -> Unit = { AndroidAutoLog.i("Wi-Fi: $it") }
        return when (mode(app)) {
            WirelessHotspotMode.MANUAL, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> ManualHotspotManager(
                context = app,
                ssid = preferences.getString(KEY_HOTSPOT_SSID, null).orEmpty(),
                passphrase = preferences.getString(KEY_HOTSPOT_PASSPHRASE, null).orEmpty(),
                band = ManualHotspotBand.entries.firstOrNull { it.name == preferences.getString(KEY_HOTSPOT_BAND, null) }
                    ?: ManualHotspotBand.AUTO,
                channel = preferences.getInt(KEY_HOTSPOT_CHANNEL, 0).coerceIn(0, 196),
                security = ManualHotspotSecurity.entries.firstOrNull { it.name == preferences.getString(KEY_HOTSPOT_SECURITY, null) }
                    ?: if (preferences.getString(KEY_HOTSPOT_PASSPHRASE, null).isNullOrEmpty()) ManualHotspotSecurity.OPEN
                    else ManualHotspotSecurity.WPA2,
                onDiagnostic = log,
                preferredInterface = preferences.getString(KEY_HOTSPOT_INTERFACE, null)?.trim()?.takeIf { it.isNotEmpty() },
            )
            WirelessHotspotMode.EXISTING_WIFI -> ExistingWifiManager(
                app,
                preferences.getString(KEY_EXISTING_SSID, null).orEmpty(),
                preferences.getString(KEY_EXISTING_PASSPHRASE, null).orEmpty(),
                log,
                preferredInterface = preferences.getString(KEY_EXISTING_INTERFACE, null)?.trim()?.takeIf { it.isNotEmpty() },
            )
            WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(app, log,
                preferredChannel = preferences.getInt(KEY_P2P_CHANNEL, WifiP2pChannels.AUTO).takeIf(WifiP2pChannels::isValid)
                    ?: WifiP2pChannels.AUTO)
        }
    }

    /**
     * Runtime permissions a wireless start needs: Bluetooth for the setup channel, and what the
     * network itself needs, as for wireless CarPlay (`WirelessPermissions` in `common/`, which
     * `AndroidAutoSettingsTest` compares with this).
     */
    fun permissions(mode: WirelessHotspotMode, sdkInt: Int): List<String> {
        val bluetooth = if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        val network = when {
            mode == WirelessHotspotMode.EXISTING_WIFI -> emptyList()
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Android 17 needs local network access for the phone's inbound connection.
        val local = if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) listOf(Manifest.permission.ACCESS_LOCAL_NETWORK) else emptyList()
        return bluetooth + network + local
    }
}
