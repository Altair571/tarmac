// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto

import com.shilapi.xcertplay.androidauto.aap.AapIncompatibleVersion
import java.io.IOException
import javax.net.ssl.SSLException

/** Why Android Auto stopped, in terms the screen can explain and the reconnect logic can act on. */
enum class AndroidAutoFailure(internal val retries: Boolean) {
    /** The driver declined the USB prompt. */
    PERMISSION_DENIED(false),
    /** The phone does not answer the accessory request: no Android Auto, or a charge-only cable. */
    NOT_ANDROID_AUTO(false),
    /** The phone accepted the switch but did not come back as an accessory in time. */
    NO_SWITCH(true),
    /** Android could not open or claim the phone's USB interface. */
    USB(true),
    /** The phone ended the secure handshake: it rejects this head unit's certificate. */
    IDENTITY_REJECTED(false),
    INCOMPATIBLE_VERSION(false),
    /** No byte from the phone for the silence limit, although the head unit kept pinging. */
    SILENT(true),
    /** The cable was pulled, the phone left the bus, or it closed the wireless connection. */
    DISCONNECTED(true),
    /** Bluetooth is off, not allowed, or refuses the Android Auto record. */
    BLUETOOTH(false),
    /** No phone opened Android Auto over Bluetooth while the head unit waited. */
    NO_WIRELESS_PHONE(false),
    /** The Wi-Fi network for the phone did not start. */
    WIFI(true),
    /** The phone did not join that network, or did not connect over it. */
    PHONE_WIFI(true),
    /** A CarPlay session holds the head unit's Wi-Fi; wireless Android Auto would take it away. */
    CARPLAY_RUNNING(false),
    OTHER(true);

    internal companion object {
        fun of(error: Throwable?): AndroidAutoFailure? = when (error) {
            null -> null
            is AndroidAutoException -> error.failure
            is AapIncompatibleVersion -> INCOMPATIBLE_VERSION
            is SSLException -> IDENTITY_REJECTED
            else -> OTHER
        }
    }
}

/** A failure with its [failure] kind attached where it happened. */
class AndroidAutoException(val failure: AndroidAutoFailure, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * When to try again after a session fails on its own: soon at first, then less often, and not at
 * all for failures that need the driver (a declined prompt, a rejected identity).
 */
internal object AndroidAutoReconnect {
    private val DELAYS_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

    /** A session that ran this long counts as working: the next failure starts the backoff again. */
    const val HEALTHY_SESSION_MILLIS = 15_000L

    /** The wait before retry number [attempt] (from 0), or null to stop trying. */
    fun delayMillis(failure: AndroidAutoFailure?, attempt: Int): Long? {
        if (failure == null || !failure.retries) return null
        return DELAYS_MILLIS.getOrNull(attempt)
    }
}
