package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.shilapi.xcertplay.WheelKeyMap.Action
import com.shilapi.xcertplay.WheelKeyMap.Command
import com.shilapi.xcertplay.hud.CarPlayCallCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelKeyMapTest {
    private lateinit var service: WheelKeyService
    private val sent = mutableListOf<Command>()
    private var phone: Any? = "phone-one"
    private var phase: CarPlayCallCard.Phase? = null
    private val nextKey = WheelKey(KeyEvent.KEYCODE_F6, 0, "?")

    @Before fun setUp() {
        service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        for (name in listOf("diplay_wheel_map_zoom", "diplay_wheel_key_map", "diplay_navigation_wheel")) {
            service.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        service.session = { phone }
        service.performMapped = { sent.add(it); true }
        service.callPhase = { phase }
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    }

    @After fun tearDown() {
        service.onDestroy()
        CarPlayBackgroundSession.clear()
    }

    private fun key(code: Int, down: Boolean): Boolean = service.javaClass
        .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }.invoke(service,
            KeyEvent(0, 0, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, 0, -1, 0),
        ) as Boolean

    @Test fun eachActionSendsItsCommandOnlyWithACarPlaySession() {
        assertEquals(Command.NEXT, WheelKeyMap.decide(Action.NEXT, session = true, call = null, inCall = false))
        assertEquals(Command.PREVIOUS, WheelKeyMap.decide(Action.PREVIOUS, true, null, false))
        assertEquals(Command.HOME, WheelKeyMap.decide(Action.HOME, true, null, false))
        for (action in Action.entries) assertNull(WheelKeyMap.decide(action, session = false, call = null, inCall = false))
    }

    @Test fun theCallKeyAnswersARingingCallAndEndsAnActiveOne() {
        assertEquals(Command.ANSWER, WheelKeyMap.decide(Action.CALL, true, CarPlayCallCard.Phase.RINGING, true))
        assertEquals(Command.END, WheelKeyMap.decide(Action.CALL, true, CarPlayCallCard.Phase.ACTIVE, true))
        assertEquals(Command.END, WheelKeyMap.decide(Action.CALL, true, CarPlayCallCard.Phase.DIALING, true))
        // No CarPlay call: the car keeps the key, so a native call is never declined by mistake.
        assertNull(WheelKeyMap.decide(Action.CALL, true, null, false))
        // During a call the other keys keep the car's own call handling.
        assertNull(WheelKeyMap.decide(Action.NEXT, true, CarPlayCallCard.Phase.ACTIVE, true))
    }

    @Test fun aLearnedKeyActsOnlyWhileTheSwitchIsOnAndNothingElseOwnsIt() {
        WheelKeyMap.assign(service, Action.NEXT, nextKey)
        assertNull(WheelKeyMap.actionFor(service, nextKey))
        WheelKeyMap.setEnabled(service, true)
        assertEquals(Action.NEXT, WheelKeyMap.actionFor(service, nextKey))
        WheelZoomSettings.setSiriKey(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.SIRI, nextKey)
        assertNull("the Siri key wins", WheelKeyMap.actionFor(service, nextKey))
        assertEquals(WheelZoomSettings.Role.SIRI, WheelKeyMap.takenBy(service, Action.HOME, nextKey))
        WheelZoomSettings.setSiriKey(service, false)
        assertEquals(Action.NEXT, WheelKeyMap.takenBy(service, Action.HOME, nextKey))
        assertNull(WheelKeyMap.takenBy(service, Action.NEXT, nextKey))
        WheelKeyMap.clear(service, Action.NEXT)
        assertNull(WheelKeyMap.key(service, Action.NEXT))
    }

    @Test fun theKeyServiceSendsALearnedKeyAndKeepsItsRelease() {
        WheelKeyMap.setEnabled(service, true)
        WheelKeyMap.assign(service, Action.NEXT, nextKey)
        assertTrue(key(KeyEvent.KEYCODE_F6, true))
        assertTrue(key(KeyEvent.KEYCODE_F6, false))
        assertEquals(listOf(Command.NEXT), sent)
        // Without a session the key goes to the car.
        phone = null
        assertFalse(key(KeyEvent.KEYCODE_F6, true))
        assertFalse(key(KeyEvent.KEYCODE_F6, false))
        assertEquals(1, sent.size)
    }

    @Test fun anyKeyCanBeLearnedWithoutTheRoleSettings() {
        WheelKeyMap.setEnabled(service, true)
        var learnt: WheelKey? = null
        assertTrue(WheelKeyService.learnAny { learnt = it })
        assertTrue(WheelKeyService.isLearning())
        // The periodic eligibility check must not end it although no role setting is on.
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(key(KeyEvent.KEYCODE_F7, true))
        assertTrue(key(KeyEvent.KEYCODE_F7, false))
        assertEquals(KeyEvent.KEYCODE_F7, learnt?.code)
        assertFalse(WheelKeyService.isLearning())
        assertTrue(sent.isEmpty())
    }
}
