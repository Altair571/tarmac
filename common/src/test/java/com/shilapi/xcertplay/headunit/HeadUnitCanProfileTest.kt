package com.shilapi.xcertplay.headunit

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HeadUnitCanProfileTest {
    @Test fun fytProfileCombinesTheTablesWithTheUnitsOwnFlags() {
        // 1 is FYT's first VW profile; its protocol reads guidance and the third-party player's text.
        val profile = FytCanProfile.profile(mapOf(
            FytSyuPlatform.U_CANBUS_ID to 1,
            FytSyuPlatform.U_EXIST_DOOR to 1,
            FytSyuPlatform.U_EXIST_AIR to 0,
        ))!!
        assertEquals(1, profile.protocol)
        assertEquals(0, profile.variant)
        assertTrue(profile.names.isEmpty())
        assertEquals(HeadUnitSupport.YES, profile.features[HeadUnitCanFeature.GUIDANCE])
        assertEquals(HeadUnitSupport.YES, profile.features[HeadUnitCanFeature.TRACK_TEXT])
        assertEquals(HeadUnitSupport.YES, profile.features[HeadUnitCanFeature.DOORS])
        assertEquals(HeadUnitSupport.NO, profile.features[HeadUnitCanFeature.CLIMATE])
        assertFalse("a flag FYT did not send stays unknown", HeadUnitCanFeature.SURROUND_CAMERA in profile.features)

        assertNull("no CAN box", FytCanProfile.profile(mapOf(FytSyuPlatform.U_CANBUS_ID to 0)))
        assertNull("no answer", FytCanProfile.profile(mapOf(FytSyuPlatform.U_EXIST_DOOR to 1)))
    }

    @Test fun fytTablesFollowTheProtocolEachProfileOpens() {
        assertTrue(HeadUnitCanTables.fytGuidance(17) && HeadUnitCanTables.fytTrackText(17))
        assertFalse(HeadUnitCanTables.fytGuidance(2) || HeadUnitCanTables.fytTrackText(2))
        // Profile 20 sends track text but takes no guidance.
        assertFalse(HeadUnitCanTables.fytGuidance(20))
        assertTrue(HeadUnitCanTables.fytTrackText(20))
        // Protocol 439 picks its class by car variant: variant 67 reads guidance, the protocol's default does not.
        assertTrue(HeadUnitCanTables.fytGuidance(67 shl 16 or 439))
        assertFalse(HeadUnitCanTables.fytGuidance(439))
        // A protocol number FYT does not know falls back to its generic class.
        assertFalse(HeadUnitCanTables.fytGuidance(0x7000) || HeadUnitCanTables.fytTrackText(0x7000))
    }

    @Test fun teyesProfileNamesTheModelInTheServicesOrder() {
        val asked = mutableListOf<Pair<Int, Int>>()
        val lists = mapOf(
            SpdCanProfile.ALL_CANBOX to mapOf(1 to "Raise", 2 to "Hiworld"),
            SpdCanProfile.ALL_MAKE to mapOf(1 to "Volkswagen"),
            SpdCanProfile.ALL_MODEL to mapOf(1 to " Volkswagen PQ "),
        )
        val hiworldVwPq = 0x02010001
        val profile = SpdCanProfile.profile(hiworldVwPq, navigationBox = false) { what, arg ->
            asked += what to arg
            lists.getValue(what)
        }!!
        assertEquals(listOf(SpdCanProfile.ALL_CANBOX to 0, SpdCanProfile.ALL_MAKE to 2, SpdCanProfile.ALL_MODEL to 1), asked)
        assertEquals(listOf("Hiworld", "Volkswagen", "Volkswagen PQ"), profile.names)
        assertEquals(HeadUnitSupport.YES, profile.features[HeadUnitCanFeature.GUIDANCE])
        assertEquals(HeadUnitSupport.ROOT, profile.features[HeadUnitCanFeature.TRACK_TEXT])

        val raiseAudiTt = 0x01180001
        val audi = SpdCanProfile.profile(raiseAudiTt, navigationBox = false) { _, _ -> emptyMap() }!!
        assertTrue("names the unit does not give are left out", audi.names.isEmpty())
        assertEquals(HeadUnitSupport.NO, audi.features[HeadUnitCanFeature.GUIDANCE])
        assertEquals(HeadUnitSupport.NO, audi.features[HeadUnitCanFeature.TRACK_TEXT])
        val withBox = SpdCanProfile.profile(raiseAudiTt, navigationBox = true) { _, _ -> emptyMap() }!!
        assertEquals("the add-on navigation box takes guidance for any model",
            HeadUnitSupport.YES, withBox.features[HeadUnitCanFeature.GUIDANCE])

        assertNull("TEYES's None", SpdCanProfile.profile(0, navigationBox = true) { _, _ -> emptyMap() })
    }

    @Test fun teyesNameListsAreReadFromTheServicesBundle() {
        val service = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.spd.carinfo.ICarInfoAidlInterface")
                assertEquals(12, code)
                assertEquals(SpdCanProfile.ALL_MAKE, data.readInt())
                assertEquals(2, data.readInt())
                reply!!.writeNoException()
                reply.writeInt(1)
                Bundle().apply {
                    putStringArray("names", arrayOf("Volkswagen", "Benz"))
                    putIntArray("values", intArrayOf(1, 0x20))
                }.writeToParcel(reply, 0)
                return true
            }
        }
        val names = SpdCanProfile.namesFrom(SpdCarInfoClient(service).getBundle(SpdCanProfile.ALL_MAKE, 2))
        assertEquals(mapOf(1 to "Volkswagen", 0x20 to "Benz"), names)
        assertTrue(SpdCanProfile.namesFrom(null).isEmpty())
    }

    @Test fun fytCallbackHandsOverEachUpdate() {
        val updates = mutableListOf<Pair<Int, List<Int>?>>()
        val callback: IBinder = FytModuleIpc.callback { code, ints -> updates += code to ints?.toList() }
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.syu.ipc.IModuleCallback")
            data.writeInt(FytSyuPlatform.U_CANBUS_ID)
            data.writeIntArray(intArrayOf(0x2a01bb))
            data.writeFloatArray(null)
            data.writeStringArray(null)
            assertTrue(callback.transact(1, data, null, IBinder.FLAG_ONEWAY))
        } finally {
            data.recycle()
        }
        assertEquals(listOf(FytSyuPlatform.U_CANBUS_ID to listOf(0x2a01bb)), updates)
    }

    @Test fun bothPlatformsReadTheirProfile() {
        assertTrue(SpdTeyesPlatform.readsCanProfile && FytSyuPlatform.readsCanProfile)
    }
}
