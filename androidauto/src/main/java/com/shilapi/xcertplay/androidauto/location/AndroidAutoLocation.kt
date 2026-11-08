// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.androidauto.aap.AapMessages
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider

/**
 * The head unit's own position as Android Auto location events, when the driver chooses
 * "GPS source · Head unit". Fixes come from CarPlay's provider (GPS first, then fused on Android 12+,
 * then network). Each new fix is sent at once, and the newest one again every second, so the phone
 * keeps a steady 1 Hz feed through gaps between fixes.
 */
internal class AndroidAutoLocation(
    private val context: Context,
    /** Gets each encoded event; called on the main thread, so it hands the USB write to a worker. */
    private val send: (ByteArray) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val provider = AndroidCarPlayLocationProvider(context, onFix = ::onFix)
    @Volatile private var running = false
    private var lastSent = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() - lastSent >= RESEND_MILLIS) provider.latestFix()?.let(::sendFix)
            main.postDelayed(this, INTERVAL_MILLIS)
        }
    }

    /** Whether Android lets this app read the position; without it the sensor is not announced. */
    fun available(): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun start() = main.post {
        if (running || !available()) return@post
        running = provider.start()
        if (!running) return@post
        provider.latestFix()?.let(::sendFix)
        main.postDelayed(tick, INTERVAL_MILLIS)
    }

    fun stop() = main.post {
        running = false
        main.removeCallbacks(tick)
        provider.stop()
    }

    private fun onFix(location: Location) {
        if (running) sendFix(location)
    }

    private fun sendFix(location: Location) {
        lastSent = SystemClock.elapsedRealtime()
        send(event(location))
    }

    companion object {
        private const val INTERVAL_MILLIS = 1_000L
        /** A fix sent this recently already covers the current second. */
        private const val RESEND_MILLIS = 900L

        fun event(location: Location): ByteArray = AapMessages.location(
            timestampMillis = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
            bearingDegrees = location.bearing.takeIf { location.hasBearing() },
        )
    }
}
