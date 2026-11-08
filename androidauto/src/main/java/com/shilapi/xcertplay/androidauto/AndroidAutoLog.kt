// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto

import android.util.Log

/** One log tag for the Android Auto receiver; quiet in plain JVM unit tests, where `Log` is a stub. */
internal object AndroidAutoLog {
    private const val TAG = "DiPlay-AndroidAuto"

    fun i(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    fun w(message: String, error: Throwable? = null) {
        runCatching { Log.w(TAG, message, error) }
    }
}
