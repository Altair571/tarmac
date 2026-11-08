// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.shilapi.xcertplay.androidauto.AndroidAuto
import com.shilapi.xcertplay.androidauto.AndroidAutoLog
import com.shilapi.xcertplay.androidauto.AndroidAutoProjection
import com.shilapi.xcertplay.androidauto.AndroidAutoSettings
import com.shilapi.xcertplay.androidauto.R
import com.shilapi.xcertplay.androidauto.ui.AndroidAutoActivity

/**
 * Starts wireless Android Auto when a phone that has used it here before connects over Bluetooth,
 * if the driver turned that on. CarPlay's `PhoneBluetoothReceiver` answers the same broadcast for
 * the chosen iPhone; the two never match the same phone.
 */
class AndroidAutoBluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("ApplySharedPref") // The start stamp must be on disk before the start, as explained below.
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val device = intent.bluetoothDevice()
        val wake = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        // Elapsed time restarts at boot, so a stamp from before it counts as no recent start.
        val last = wake.getLong(KEY_LAST_START, -1).takeIf { it in 0..now }
        val decision = AndroidAutoBluetoothStart.decide(AndroidAutoBluetoothStart.Event(
            action = intent.action,
            address = address(app, device),
            enabled = AndroidAutoSettings.bluetoothStart(app),
            remembered = AndroidAutoSettings.wirelessPhones(app),
            busy = AndroidAuto.running || AndroidAutoProjection.status.stage == AndroidAutoProjection.Stage.RECONNECTING ||
                AndroidAuto.carPlayActive(),
            millisSinceLastStart = last?.let { now - it },
            sdkInt = Build.VERSION.SDK_INT,
            canDrawOverlays = Settings.canDrawOverlays(app),
        ))
        if (decision == AndroidAutoBluetoothStart.Decision.IGNORE) return
        // Committed before starting, so another receiver instance from the same burst of events sees it.
        wake.edit().putLong(KEY_LAST_START, now).commit()
        AndroidAutoLog.i("Bluetooth start: $decision")
        // The record goes up at once: the phone looks for it as its hands-free link connects, not later.
        AndroidAutoProjection.startWireless(app, since = now)
        val name = name(app, device)
        if (decision == AndroidAutoBluetoothStart.Decision.OPEN) {
            runCatching { context.startActivity(AndroidAutoActivity.wirelessIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure {
                    AndroidAutoLog.w("Android Auto screen not opened from Bluetooth", it)
                    AndroidAutoStartNotification.post(app, name)
                }
        } else {
            AndroidAutoStartNotification.post(app, name)
        }
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    private fun permitted(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // permitted() checks it.
    private fun address(context: Context, device: BluetoothDevice?): String? =
        device?.takeIf { permitted(context) }?.let { runCatching { it.address }.getOrNull() }

    @SuppressLint("MissingPermission") // permitted() checks it.
    private fun name(context: Context, device: BluetoothDevice?): String? =
        device?.takeIf { permitted(context) }?.let { runCatching { it.name }.getOrNull() }?.takeIf { it.isNotBlank() }

    private companion object {
        const val PREFERENCES = "android_auto_bluetooth_start"
        const val KEY_LAST_START = "last_start_elapsed"
    }
}

/** What the receiver does about one Bluetooth event. Pure, so its rules are tested without Bluetooth. */
internal object AndroidAutoBluetoothStart {
    enum class Decision {
        /** Not for Android Auto: another event or device, turned off, already running, or just started. */
        IGNORE,
        /** Start wireless Android Auto and open its screen. */
        OPEN,
        /** Start wireless Android Auto and post a "Start Android Auto" notification: Android would not show the screen. */
        NOTIFY,
    }

    class Event(
        val action: String?,
        /** Null when the event has no device or the app may not read it. */
        val address: String?,
        val enabled: Boolean,
        val remembered: Set<String>,
        /** Android Auto is running or retrying, or CarPlay holds the head unit. */
        val busy: Boolean,
        /** Since this receiver last started Android Auto, or null if it has not since boot. */
        val millisSinceLastStart: Long?,
        val sdkInt: Int,
        val canDrawOverlays: Boolean,
    )

    /** The car can send several connection events during one wake-up; these start Android Auto once. */
    const val REPEAT_MILLIS = 15_000L

    fun decide(event: Event): Decision {
        if (event.action != BluetoothDevice.ACTION_ACL_CONNECTED || !event.enabled || event.busy) return Decision.IGNORE
        val address = event.address ?: return Decision.IGNORE
        if (event.remembered.none { it.equals(address, ignoreCase = true) }) return Decision.IGNORE
        if (event.millisSinceLastStart?.let { it < REPEAT_MILLIS } == true) return Decision.IGNORE
        // Android 10+ ignores an activity start from the background unless the app may draw over other apps.
        return if (event.sdkInt >= Build.VERSION_CODES.Q && !event.canDrawOverlays) Decision.NOTIFY else Decision.OPEN
    }
}

/** "Start Android Auto": the tap that brings the screen up when Android will not let the app open by itself. */
internal object AndroidAutoStartNotification {
    private const val CHANNEL = "android_auto_start"
    private const val ID = 42
    private const val TIMEOUT_MILLIS = 120_000L

    fun post(context: Context, phone: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // High importance, so it appears over whatever is on screen when the phone arrives.
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.aa_start_channel),
                NotificationManager.IMPORTANCE_HIGH))
            Notification.Builder(context, CHANNEL).setTimeoutAfter(TIMEOUT_MILLIS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
        }
        val open = PendingIntent.getActivity(context, ID, AndroidAutoActivity.wirelessIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = builder
            .setSmallIcon(R.drawable.ic_android_auto)
            .setContentTitle(phone ?: context.getString(R.string.aa_title))
            .setContentText(context.getString(R.string.aa_start_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_android_auto),
                context.getString(R.string.aa_start_action), open).build())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { manager.notify(ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
