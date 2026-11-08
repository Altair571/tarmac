package com.shilapi.xcertplay.headunit

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.WheelKeyLog

/** The head unit's own voice and call keys, sent to CarPlay while a session runs. Media keys are left alone. */
internal object HeadUnitWheelKeys {
    private const val TAG = "Tarmac-HeadUnit"

    /** The action for this key, or null when the key is not the platform's or no CarPlay session runs. */
    fun route(platform: HeadUnitPlatform?, keyCode: Int, deviceId: Int, sessionRunning: Boolean): HeadUnitKeyAction? =
        if (!sessionRunning) null else platform?.keyAction(keyCode, deviceId)

    /** Returns true when the key went to CarPlay and must not reach the head unit. */
    fun onKey(context: Context, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (!HeadUnitSettings.wheelKeys(context)) return false
        val controller = CarPlayBackgroundSession.snapshot()?.controller
        val action = route(HeadUnitPlatforms.current(context), event.keyCode, event.deviceId,
            controller?.activeAirPlaySessionToken() != null) ?: return false
        // Both halves of the press are kept from the unit; CarPlay acts on the release, once.
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            val sent = when (action) {
                HeadUnitKeyAction.SIRI -> controller?.requestSiri()
                HeadUnitKeyAction.ANSWER_CALL -> controller?.answerCall()
                HeadUnitKeyAction.END_CALL -> controller?.endCall()
            } == true
            Log.i(TAG, "key ${event.keyCode} from device ${event.deviceId}: $action sent=$sent")
            WheelKeyLog.note("head unit key ${event.keyCode} -> CarPlay $action sent=$sent")
        }
        return true
    }
}

/** DiPlay's own choices for the head-unit adapter. */
internal object HeadUnitSettings {
    private const val PREFS = "diplay_head_unit"
    private const val KEY_WHEEL_KEYS = "wheel_keys"
    private const val KEY_LINK_KEYS = "link_keys"
    private const val KEY_TRACK_TEST_RECORD = "track_test_record"
    private const val KEY_TRACK_TEST_ENTER_SOURCE = "track_test_enter_source"

    fun wheelKeys(context: Context): Boolean = prefs(context).getBoolean(KEY_WHEEL_KEYS, true)

    fun saveWheelKeys(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WHEEL_KEYS, enabled).apply()
    }

    /** FYT's root key link; off until the driver turns it on, since it takes the keys from the unit. */
    fun linkKeys(context: Context): Boolean = prefs(context).getBoolean(KEY_LINK_KEYS, false)

    fun saveLinkKeys(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LINK_KEYS, enabled).apply()
    }

    fun trackTestRecord(context: Context): TeyesTrackTextTool.Record =
        TeyesTrackTextTool.Record.entries.firstOrNull { it.name == prefs(context).getString(KEY_TRACK_TEST_RECORD, null) }
            ?: TeyesTrackTextTool.Record.OTHER_MEDIA

    fun saveTrackTestRecord(context: Context, record: TeyesTrackTextTool.Record) {
        prefs(context).edit().putString(KEY_TRACK_TEST_RECORD, record.name).apply()
    }

    fun trackTestEnterSource(context: Context): Boolean = prefs(context).getBoolean(KEY_TRACK_TEST_ENTER_SOURCE, false)

    fun saveTrackTestEnterSource(context: Context, enter: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRACK_TEST_ENTER_SOURCE, enter).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
