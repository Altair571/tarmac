package com.shilapi.xcertplay.headunit

import android.os.Binder
import android.os.Parcel
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FytKeyLinkTest {
    @Test fun voiceAndCallKeysGoToCarPlay() {
        assertEquals(FytLinkKey.CarPlay(HeadUnitKeyAction.SIRI), FytLinkKeys.forKey(576))
        for (answer in listOf(KeyEvent.KEYCODE_CALL, 588)) {
            assertEquals(FytLinkKey.CarPlay(HeadUnitKeyAction.ANSWER_CALL), FytLinkKeys.forKey(answer))
        }
        for (hangUp in listOf(KeyEvent.KEYCODE_ENDCALL, 587)) {
            assertEquals(FytLinkKey.CarPlay(HeadUnitKeyAction.END_CALL), FytLinkKeys.forKey(hangUp))
        }
    }

    @Test fun everyOtherForwardedKeyIsGivenBackAsTheUnitWould() {
        for (media in listOf(85, 87, 88, 126, 127)) assertEquals(FytLinkKey.Media(media), FytLinkKeys.forKey(media))
        assertEquals(FytLinkKey.System(KeyEvent.KEYCODE_HOME), FytLinkKeys.forKey(574))
        assertEquals(FytLinkKey.System(KeyEvent.KEYCODE_BACK), FytLinkKeys.forKey(575))
        assertNull("com.syu.ms forwards no other key", FytLinkKeys.forKey(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test fun theHelperPutsBackWhatWasThere() {
        assertEquals("0", FytKeyGateTool.restored("0", "3"))
        assertEquals("0", FytKeyGateTool.restored("", "3"))
        assertEquals("a link type left by an earlier helper is cleared", "0", FytKeyGateTool.restored("3", "3"))
        assertEquals("1", FytKeyGateTool.restored("1", "3"))
    }

    @Test fun theRootCommandTakesOnlyNumbers() {
        val command = FytKeyGateTool.command("/data/app/x/base.apk", 4321, FytKeyLink.LINK_TYPE)
        assertEquals("CLASSPATH='/data/app/x/base.apk' app_process /system/bin " +
            "com.shilapi.xcertplay.headunit.FytKeyGateTool 4321 3", command)
        try {
            FytKeyGateTool.command("/a.apk", 0, 3)
            fail("a missing process ID must not reach su")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test fun theLinkRegistersWithoutReplayingTheLastKey() {
        val sent = mutableListOf<Int>()
        val module = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.syu.ipc.IRemoteModule")
                assertEquals(3, code)
                data.readStrongBinder()
                sent += data.readInt()
                sent += data.readInt()
                reply?.writeNoException()
                return true
            }
        }
        FytModuleIpc.register(module, Binder(), FytSyuPlatform.U_LINK_KEY, sendCurrent = false)
        FytModuleIpc.register(module, Binder(), FytSyuPlatform.U_CANBUS_ID)
        assertEquals(listOf(133, 0, 1000, 1), sent)
    }

    @Test fun onlyFytLinksItsKeysThroughRoot() {
        assertTrue(FytSyuPlatform.linksKeysWithRoot)
        assertFalse(SpdTeyesPlatform.linksKeysWithRoot)
    }
}
