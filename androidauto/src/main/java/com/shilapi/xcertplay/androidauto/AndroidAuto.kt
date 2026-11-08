// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.shilapi.xcertplay.androidauto.ui.AndroidAutoActivity
import com.shilapi.xcertplay.androidauto.wireless.AndroidAutoWifiNetwork

/**
 * The Android Auto receiver as the rest of the app sees it: open the projection screen, ask
 * whether a head-unit identity is installed, read and change the settings in [AndroidAutoSettings].
 * See docs/ANDROID_AUTO.md.
 */
object AndroidAuto {
    /** Opens the projection screen; it connects the first Android phone on USB. */
    fun projectionIntent(context: Context): Intent = AndroidAutoActivity.intent(context)

    /** Opens the projection screen and waits for a phone to start wireless Android Auto over Bluetooth. */
    fun wirelessIntent(context: Context): Intent = AndroidAutoActivity.wirelessIntent(context)

    /** False on every build made without a local identity; Android Auto then explains what is missing. */
    fun identityInstalled(context: Context): Boolean = HeadUnitIdentity.installed(context)

    /** Whether a phone is connecting or projecting now. */
    val running: Boolean get() = AndroidAutoProjection.active

    /**
     * Whether CarPlay holds a session, set by the app. Wireless Android Auto does not start beside
     * one: both need the head unit's Wi-Fi. Until the app sets it, no CarPlay session can exist.
     */
    @Volatile var carPlayActive: () -> Boolean = { false }

    /** The runtime permissions wireless Android Auto still needs, for the network wireless CarPlay is set up with. */
    fun missingWirelessPermissions(context: Context): List<String> =
        AndroidAutoWifiNetwork.permissions(AndroidAutoWifiNetwork.mode(context), Build.VERSION.SDK_INT)
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
