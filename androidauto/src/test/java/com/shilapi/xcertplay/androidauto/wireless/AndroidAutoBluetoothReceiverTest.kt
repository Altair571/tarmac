package com.shilapi.xcertplay.androidauto.wireless

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.shilapi.xcertplay.androidauto.AndroidAutoProjection
import com.shilapi.xcertplay.androidauto.AndroidAutoSettings
import com.shilapi.xcertplay.androidauto.R
import com.shilapi.xcertplay.androidauto.ui.AndroidAutoActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

/** The receiver end to end: which Bluetooth event opens the Android Auto screen or posts the start notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidAutoBluetoothReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val phone = "12:34:56:78:9A:BC"
    private val launches = mutableListOf<Intent>()
    private val context get() = object : ContextWrapper(app) {
        override fun startActivity(intent: Intent) { launches += intent }
    }

    @Before fun reset() {
        app.getSharedPreferences("android_auto_bluetooth_start", Context.MODE_PRIVATE).edit().clear().commit()
        AndroidAutoSettings.forgetWirelessPhones(app)
        AndroidAutoSettings.setBluetoothStart(app, false)
    }

    /** The start goes on in the background and ends at "no identity", since tests have none; wait for it. */
    @After fun stop() {
        val deadline = System.currentTimeMillis() + 5_000
        while (AndroidAutoProjection.active && System.currentTimeMillis() < deadline) Thread.sleep(10)
        AndroidAutoProjection.stop()
        ShadowLooper.idleMainLooper()
    }

    private fun connected(address: String) = Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
        .putExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address))

    @Test fun aRememberedPhoneOpensTheWirelessScreenOnce() {
        ShadowSettings.setCanDrawOverlays(true)
        AndroidAutoSettings.rememberWirelessPhone(app, phone.lowercase())
        val receiver = AndroidAutoBluetoothReceiver()

        receiver.onReceive(context, connected(phone))
        assertTrue("off by default", launches.isEmpty())

        AndroidAutoSettings.setBluetoothStart(app, true)
        receiver.onReceive(context, connected("AA:BB:CC:DD:EE:FF"))
        assertTrue("not a phone that used wireless Android Auto here", launches.isEmpty())

        receiver.onReceive(context, connected(phone))
        receiver.onReceive(context, connected(phone))
        val launch = launches.single()
        assertEquals(AndroidAutoActivity::class.java.name, launch.component?.className)
        assertTrue(launch.getBooleanExtra(AndroidAutoActivity.EXTRA_WIRELESS, false))
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun withoutOverlayPermissionTheDriverGetsANotification() {
        ShadowSettings.setCanDrawOverlays(false)
        AndroidAutoSettings.rememberWirelessPhone(app, phone)
        AndroidAutoSettings.setBluetoothStart(app, true)

        AndroidAutoBluetoothReceiver().onReceive(context, connected(phone))

        assertTrue(launches.isEmpty())
        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(app.getString(R.string.aa_start_text), shadowOf(posted).contentText)
        assertEquals(app.getString(R.string.aa_start_action), posted.actions.single().title.toString())
        val open = shadowOf(posted.contentIntent).savedIntent
        assertEquals(AndroidAutoActivity::class.java.name, open.component?.className)
        assertTrue(open.getBooleanExtra(AndroidAutoActivity.EXTRA_WIRELESS, false))
    }
}
