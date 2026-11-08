package com.shilapi.xcertplay.headunit

import android.os.Parcel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterTestsTest {
    @Test fun guidanceStartsTurnsAndEndsLikeAmap() {
        val run = AmapGuidanceTest.run(0L)
        assertTrue(run.all { it.action == "AUTONAVI_STANDARD_BROADCAST_SEND" })
        assertEquals(10019, run.first().getIntExtra("KEY_TYPE", -1))
        assertEquals(AmapGuidanceTest.STATE_STARTED, run.first().getIntExtra("EXTRA_STATE", -1))
        assertEquals(AmapGuidanceTest.STATE_ENDED, run.last().getIntExtra("EXTRA_STATE", -1))

        val guidance = run.drop(1).dropLast(1)
        assertEquals(5, guidance.size)
        for (intent in guidance) {
            assertEquals(10001, intent.getIntExtra("KEY_TYPE", -1))
            // FYT's CAN-box profiles read ICON, TEYES's read NEW_ICON.
            assertEquals(AmapGuidanceTest.ICON_LEFT, intent.getIntExtra("ICON", -1))
            assertEquals(AmapGuidanceTest.ICON_LEFT, intent.getIntExtra("NEW_ICON", -1))
            assertEquals(AmapGuidanceTest.NEXT_ROAD, intent.getStringExtra("NEXT_ROAD_NAME"))
            assertTrue(intent.getStringExtra("ETA_TEXT")!!.matches(Regex("预计\\d{2}:\\d{2}到达")))
        }
        val distances = guidance.map { it.getIntExtra("SEG_REMAIN_DIS", -1) }
        assertEquals(distances.sortedDescending(), distances)
        assertEquals("350 m", guidance.first().getStringExtra("SEG_REMAIN_DIS_AUTO"))
    }

    @Test fun fytTrackUsesTheThirdPlayerSlot() {
        val withSource = FytTrackText.commands(becomeSource = true)
        assertEquals(FytTrackText.C_APP_ID, withSource.first().code)
        assertArrayEquals(intArrayOf(10), withSource.first().ints)
        assertNull(withSource.first().strs)

        val fields = FytTrackText.commands(becomeSource = false)
        assertEquals(3, fields.size)
        assertTrue(fields.all { it.code == FytTrackText.C_PLAY_INFO && it.ints[0] == FytTrackText.APP_ID_THIRD_PLAYER })
        assertEquals(listOf(0, 1, 2), fields.map { it.ints[1] })
        assertEquals(TeyesTrackTextTool.TEST_TITLE, fields[0].strs!![0])
        assertEquals(TeyesTrackTextTool.TEST_ARTIST, fields[1].strs!![0])
        assertEquals("album takes two strings", 2, fields[2].strs!!.size)
    }

    @Test fun fytCommandParcelMatchesItsModuleInterface() {
        val parcel = Parcel.obtain()
        FytModuleIpc.writeCmd(parcel, 23, intArrayOf(10, 0), arrayOf("Tarmac test"))
        parcel.setDataPosition(0)
        assertEquals(23, parcel.readInt())
        assertArrayEquals(intArrayOf(10, 0), parcel.createIntArray())
        assertNull(parcel.createFloatArray())
        assertArrayEquals(arrayOf("Tarmac test"), parcel.createStringArray())
        parcel.recycle()
    }

    @Test fun bothPlatformsOfferTheDisplayTests() {
        assertEquals(TrackTestRoute.TEYES_ROOT, SpdTeyesPlatform.trackTestRoute)
        assertEquals(TrackTestRoute.FYT_MODULE, FytSyuPlatform.trackTestRoute)
        assertTrue(SpdTeyesPlatform.forwardsAmapGuidance && FytSyuPlatform.forwardsAmapGuidance)
    }
}
