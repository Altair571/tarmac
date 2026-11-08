// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.androidauto.AndroidAuto
import com.shilapi.xcertplay.androidauto.AndroidAutoFailure
import com.shilapi.xcertplay.androidauto.AndroidAutoProjection
import com.shilapi.xcertplay.androidauto.AndroidAutoProjection.Stage
import com.shilapi.xcertplay.androidauto.R
import com.shilapi.xcertplay.androidauto.aap.AapMessages
import com.shilapi.xcertplay.androidauto.aap.TouchAction
import com.shilapi.xcertplay.androidauto.wireless.AndroidAutoStartNotification

/**
 * The Android Auto screen: the phone's picture full screen, touches back to the phone, and a
 * short status while the phone connects or after it stops. Opened with [wirelessIntent], or after
 * "Connect wirelessly", it waits for the phone over Bluetooth instead of USB. The session belongs
 * to [AndroidAutoProjection], so leaving this screen keeps the sound playing.
 *
 * The picture is a [SurfaceView], composited by the system without a GPU copy. Android Auto draws
 * a fixed size with margins around the screen's shape; the view is made larger than the screen by
 * those margins and centred, so the margins fall outside it.
 */
class AndroidAutoActivity : Activity(), AndroidAutoProjection.Screen {
    private lateinit var root: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var statusPanel: LinearLayout
    private lateinit var message: TextView
    private lateinit var retry: Button
    private lateinit var wirelessButton: Button
    // Asked once per screen: a refusal then shows as the failure it causes, with what to allow.
    private var askedForPermissions = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(layout())
        hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        connect()
    }

    override fun onResume() {
        super.onResume()
        AndroidAutoStartNotification.cancel(this)
        AndroidAutoProjection.attach(this)
        connect()
    }

    override fun onPause() {
        AndroidAutoProjection.detach(this)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AndroidAutoProjection.refreshNight(this)
        root.post(::layoutVideo)
    }

    /** Starts a connection unless one is running or retrying, over Bluetooth when this screen was opened for that. */
    private fun connect() {
        val stage = AndroidAutoProjection.status.stage
        if (AndroidAutoProjection.active || stage == Stage.RECONNECTING) return
        val since = intent.getLongExtra(EXTRA_SINCE, SystemClock.elapsedRealtime())
        intent.removeExtra(EXTRA_SINCE)
        if (intent.getBooleanExtra(EXTRA_WIRELESS, false)) connectWirelessly(since)
        else AndroidAutoProjection.start(this, intent.usbDevice(), since)
    }

    /** Asks for what wireless needs first (Nearby devices, and Location or Nearby Wi-Fi), then waits for the phone. */
    private fun connectWirelessly(since: Long = SystemClock.elapsedRealtime()) {
        intent.putExtra(EXTRA_WIRELESS, true)
        val missing = AndroidAuto.missingWirelessPermissions(this)
        if (missing.isNotEmpty() && !askedForPermissions) {
            askedForPermissions = true
            requestPermissions(missing.toTypedArray(), REQUEST_WIRELESS_PERMISSIONS)
            return
        }
        AndroidAutoProjection.startWireless(this, since)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WIRELESS_PERMISSIONS) AndroidAutoProjection.startWireless(this)
    }

    override fun onStatus(status: AndroidAutoProjection.Status) {
        val projecting = status.stage == Stage.PROJECTING
        statusPanel.visibility = if (projecting) View.GONE else View.VISIBLE
        message.text = when (status.stage) {
            Stage.IDLE, Stage.CONNECTING -> getString(R.string.aa_status_connecting)
            Stage.NO_USB_HOST -> getString(R.string.aa_status_no_usb_host)
            Stage.NO_IDENTITY -> getString(R.string.aa_status_no_identity)
            Stage.WAITING_FOR_PHONE -> getString(R.string.aa_status_waiting_for_phone)
            Stage.PERMISSION -> getString(R.string.aa_status_permission)
            Stage.SWITCHING -> getString(R.string.aa_status_switching)
            Stage.WAITING_FOR_WIRELESS_PHONE -> getString(R.string.aa_status_waiting_for_wireless_phone)
            Stage.JOINING_WIFI -> getString(R.string.aa_status_joining_wifi)
            Stage.PROJECTING -> ""
            Stage.RECONNECTING -> failureText(status) + "\n" + getString(R.string.aa_status_reconnecting, status.retryInSeconds)
            Stage.ENDED -> if (status.endedByPhone) getString(R.string.aa_status_ended_by_phone) else failureText(status)
        }
        retry.visibility = when (status.stage) {
            Stage.WAITING_FOR_PHONE, Stage.ENDED, Stage.RECONNECTING -> View.VISIBLE
            else -> View.GONE
        }
        // Offered wherever the cable is what is missing, and after a wired attempt ends.
        wirelessButton.visibility = when (status.stage) {
            Stage.WAITING_FOR_PHONE, Stage.NO_USB_HOST -> View.VISIBLE
            Stage.ENDED -> if (AndroidAutoProjection.wireless) View.GONE else View.VISIBLE
            else -> View.GONE
        }
        if (projecting) layoutVideo()
    }

    private fun failureText(status: AndroidAutoProjection.Status): String = when (status.failure) {
        AndroidAutoFailure.PERMISSION_DENIED -> getString(R.string.aa_failure_permission)
        AndroidAutoFailure.NOT_ANDROID_AUTO -> getString(R.string.aa_failure_not_android_auto)
        AndroidAutoFailure.NO_SWITCH -> getString(R.string.aa_failure_no_switch)
        AndroidAutoFailure.USB -> getString(R.string.aa_failure_usb)
        AndroidAutoFailure.IDENTITY_REJECTED -> getString(R.string.aa_failure_identity_rejected)
        AndroidAutoFailure.INCOMPATIBLE_VERSION -> getString(R.string.aa_failure_version)
        AndroidAutoFailure.SILENT -> getString(R.string.aa_failure_silent)
        AndroidAutoFailure.DISCONNECTED -> getString(R.string.aa_failure_disconnected)
        AndroidAutoFailure.BLUETOOTH -> getString(R.string.aa_failure_bluetooth)
        AndroidAutoFailure.NO_WIRELESS_PHONE -> getString(R.string.aa_failure_no_wireless_phone)
        AndroidAutoFailure.WIFI -> getString(R.string.aa_failure_wifi)
        AndroidAutoFailure.PHONE_WIFI -> getString(R.string.aa_failure_phone_wifi)
        AndroidAutoFailure.CARPLAY_RUNNING -> getString(R.string.aa_failure_carplay)
        AndroidAutoFailure.OTHER, null -> getString(R.string.aa_failure_other, status.detail.orEmpty())
    }

    override fun onExitRequested() {
        finish()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun layout(): View = FrameLayout(this).apply {
        root = this
        setBackgroundColor(Color.BLACK)
        video = SurfaceView(this@AndroidAutoActivity).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = AndroidAutoProjection.setSurface(holder.surface)
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                // Returns once the decoder has stopped drawing into the surface Android is taking back.
                override fun surfaceDestroyed(holder: SurfaceHolder) = AndroidAutoProjection.setSurface(null)
            })
        }
        addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }
        // Touches are taken on the screen-sized root: its coordinates are those of Android Auto's content area.
        setOnTouchListener { view, event -> touch(event, view.width, view.height) }
        statusPanel = LinearLayout(this@AndroidAutoActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            val padding = dp(32)
            setPadding(padding, padding, padding, padding)
            isClickable = true
            addView(TextView(this@AndroidAutoActivity).apply {
                text = getString(R.string.aa_title)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                gravity = Gravity.CENTER
            })
            message = TextView(this@AndroidAutoActivity).apply {
                setTextColor(0xFFB0B0B8.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(24))
            }
            addView(message, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(this@AndroidAutoActivity).apply {
                gravity = Gravity.CENTER
                retry = button(getString(R.string.aa_button_retry), primary = true) {
                    AndroidAutoProjection.stop()
                    if (AndroidAutoProjection.wireless) AndroidAutoProjection.startWireless(this@AndroidAutoActivity)
                    else AndroidAutoProjection.start(this@AndroidAutoActivity, null)
                }
                addView(retry, LinearLayout.LayoutParams(-2, dp(56)).apply { marginEnd = dp(12) })
                wirelessButton = button(getString(R.string.aa_button_wireless), primary = false) {
                    AndroidAutoProjection.stop()
                    connectWirelessly()
                }
                addView(wirelessButton, LinearLayout.LayoutParams(-2, dp(56)).apply { marginEnd = dp(12) })
                addView(button(getString(R.string.aa_button_close), primary = false) {
                    AndroidAutoProjection.stop()
                    finish()
                }, LinearLayout.LayoutParams(-2, dp(56)))
            })
        }
        addView(statusPanel, FrameLayout.LayoutParams(-1, -1))
    }

    private fun button(text: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(if (primary) Color.WHITE else 0xFFE0E0E6.toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(if (primary) 0xFF0A84FF.toInt() else 0xFF2C2C2E.toInt())
        }
        setPadding(dp(28), 0, dp(28), 0)
        setOnClickListener { click() }
    }

    /** Sizes the picture so the part inside Android Auto's margins exactly fills the screen. */
    private fun layoutVideo() {
        val geometry = AndroidAutoProjection.geometry ?: return
        if (root.width <= 0 || root.height <= 0) return
        val width = root.width.toLong() * geometry.resolution.width / geometry.contentWidth
        val height = root.height.toLong() * geometry.resolution.height / geometry.contentHeight
        val params = video.layoutParams as FrameLayout.LayoutParams
        if (params.width == width.toInt() && params.height == height.toInt()) return
        params.width = width.toInt()
        params.height = height.toInt()
        video.layoutParams = params
    }

    private fun touch(event: MotionEvent, width: Int, height: Int): Boolean {
        if (AndroidAutoProjection.status.stage != Stage.PROJECTING) return false
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> TouchAction.UP
            MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
            MotionEvent.ACTION_POINTER_UP -> TouchAction.POINTER_UP
            else -> return false
        }
        val pointers = (0 until event.pointerCount).map { index ->
            Triple(event.getPointerId(index), event.getX(index), event.getY(index))
        }
        AndroidAutoProjection.touch(action, event.actionIndex, pointers, width, height)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        forward(keyCode, true) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        forward(keyCode, false) || super.onKeyUp(keyCode, event)

    /** Back and the head unit's media and D-pad keys go to Android Auto while it is on screen. */
    private fun forward(keyCode: Int, down: Boolean): Boolean {
        if (AndroidAutoProjection.status.stage != Stage.PROJECTING) return false
        if (keyCode !in AapMessages.KEYCODES) return false
        AndroidAutoProjection.key(keyCode, down)
        return true
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** `SystemClock.elapsedRealtime()` when the phone was plugged in, so the connection time counts from there. */
        internal const val EXTRA_SINCE = "com.shilapi.xcertplay.androidauto.extra.SINCE"
        /** Connect over Bluetooth and Wi-Fi rather than USB. */
        internal const val EXTRA_WIRELESS = "com.shilapi.xcertplay.androidauto.extra.WIRELESS"
        private const val REQUEST_WIRELESS_PERMISSIONS = 1

        fun intent(context: Context): Intent = Intent(context, AndroidAutoActivity::class.java)

        fun wirelessIntent(context: Context): Intent = intent(context).putExtra(EXTRA_WIRELESS, true)

        internal fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
