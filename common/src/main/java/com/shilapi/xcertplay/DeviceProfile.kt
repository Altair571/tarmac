package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.DisplayMetrics
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.CarPlaySize
import kotlin.math.max
import kotlin.math.roundToInt

/** The kind of Android device DiPlay runs on. */
internal enum class DeviceKind { HEAD_UNIT, PHONE, TABLET, TV }

/**
 * Tells a head unit from a phone or tablet so defaults and copy fit the screen DiPlay is on.
 * A head unit stays the default: only a device that is plainly handheld gets handheld behaviour.
 */
internal object DeviceProfile {
    /** A panel that reports less than this is a placeholder density, as on many head units, not a measurement. */
    private const val MIN_TRUSTED_DPI = 80f
    private const val TABLET_SMALLEST_WIDTH_DP = 600

    fun kind(context: Context): DeviceKind {
        val packages = context.packageManager
        // A sticky broadcast: registering without a receiver only reads the last battery state.
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        return classify(
            automotive = packages.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
            television = packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            telephony = packages.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            batteryPresent = battery?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true,
            smallestWidthDp = context.resources.configuration.smallestScreenWidthDp,
        )
    }

    fun classify(
        automotive: Boolean,
        television: Boolean,
        telephony: Boolean,
        batteryPresent: Boolean,
        smallestWidthDp: Int,
    ): DeviceKind = when {
        automotive -> DeviceKind.HEAD_UNIT
        television -> DeviceKind.TV
        // Head units run from the car's supply. Many fit a SIM card, so telephony alone is no phone.
        !batteryPresent -> DeviceKind.HEAD_UNIT
        smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP -> DeviceKind.TABLET
        telephony -> DeviceKind.PHONE
        else -> DeviceKind.HEAD_UNIT
    }

    fun handheld(context: Context): Boolean = kind(context).let { it == DeviceKind.PHONE || it == DeviceKind.TABLET }

    /** Wired CarPlay needs this device to be the USB host that powers the iPhone. */
    fun hasUsbHost(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    /**
     * The CarPlay width to report before the driver picks one. A handheld panel reports its real
     * density, so CarPlay can keep its controls at their true size; a head unit keeps the preset.
     */
    fun defaultCarPlayWidthMillimeters(context: Context): Int {
        val fallback = CarPlaySize.DEFAULT.widthMillimeters
        if (!handheld(context)) return fallback
        val measured = longSideMillimeters(context.resources.displayMetrics) ?: return fallback
        return AirPlayDisplaySettings.sanitizeWidthPhysicalMm(measured)
    }

    /** The long side of the screen, as a phone is mounted in landscape for CarPlay, or null without a real density. */
    fun longSideMillimeters(metrics: DisplayMetrics): Int? {
        if (metrics.xdpi < MIN_TRUSTED_DPI || metrics.ydpi < MIN_TRUSTED_DPI) return null
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
        val width = metrics.widthPixels / metrics.xdpi * MILLIMETERS_PER_INCH
        val height = metrics.heightPixels / metrics.ydpi * MILLIMETERS_PER_INCH
        return max(width, height).roundToInt()
    }

    private const val MILLIMETERS_PER_INCH = 25.4f
}
