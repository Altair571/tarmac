package com.shilapi.xcertplay.headunit

import android.os.Parcel
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
class TeyesTrackTextToolTest {
    private fun written(record: TeyesTrackTextTool.Record): Parcel = Parcel.obtain().apply {
        TeyesTrackTextTool.writeSyncSourceInfo(this, "com.altair571.tarmac", record)
        setDataPosition(0)
    }

    @Test fun otherMediaMatchesTeyesOtherMediaInfoLayout() {
        val parcel = written(TeyesTrackTextTool.Record.OTHER_MEDIA)
        assertEquals("com.altair571.tarmac", parcel.readString())
        assertEquals("typed DataWrapper present", 1, parcel.readInt())
        assertEquals("com.spd.system.entity.aidl.OtherMediaInfo", parcel.readString())
        assertEquals("deviceID", 14, parcel.readInt())
        assertEquals("packageName", "com.altair571.tarmac", parcel.readString())
        assertEquals("playTitle", TeyesTrackTextTool.TEST_TITLE, parcel.readString())
        assertEquals("playArtist", TeyesTrackTextTool.TEST_ARTIST, parcel.readString())
        assertEquals("playAlbum", TeyesTrackTextTool.TEST_ALBUM, parcel.readString())
        assertEquals(listOf(0, 0, 1, 1, 1), List(5) { parcel.readInt() })
        assertEquals("nothing after the record", 0, parcel.dataAvail())
        parcel.recycle()
    }

    @Test fun mediaMatchesTeyesMediaInfoLayout() {
        val parcel = written(TeyesTrackTextTool.Record.MEDIA)
        parcel.readString()
        parcel.readInt()
        assertEquals("com.spd.system.entity.aidl.MediaInfo", parcel.readString())
        assertEquals("deviceID", 1, parcel.readInt())
        assertEquals(listOf(TeyesTrackTextTool.TEST_TITLE, TeyesTrackTextTool.TEST_TITLE, TeyesTrackTextTool.TEST_ARTIST,
            TeyesTrackTextTool.TEST_ALBUM), List(4) { parcel.readString() })
        assertEquals("eight ints, playState last", listOf(0, 0, 0, 1, 1, 0, 0, 1), List(8) { parcel.readInt() })
        assertEquals(0, parcel.dataAvail())
        parcel.recycle()
    }

    @Test fun theAppReadsTheHelpersReport() {
        val output = """
            WARNING: linker noise
            TARMAC-TRACK|log|D/RaiseRAV4Car( 812): setSourceInfo: 0x2e 0x99 ...
            TARMAC-TRACK|log|I/SpdService-SourcManager( 501): syncSourceInfo com.altair571.tarmac
            TARMAC-TRACK|done|sent
        """.trimIndent()
        val result = TeyesTrackTextTool.parse(output)
        assertTrue(result.sent)
        assertNull(result.error)
        assertEquals(2, result.log!!.size)

        val refused = TeyesTrackTextTool.parse("Permission denied\n")
        assertFalse(refused.sent)
        assertTrue(refused.error!!.contains("root"))

        val failed = TeyesTrackTextTool.parse("TARMAC-TRACK|error|spd service not found\nTARMAC-TRACK|done|failed\n")
        assertFalse(failed.sent)
        assertEquals("spd service not found", failed.error)
        assertFalse(TeyesTrackTextTool.parse(null).sent)
    }

    @Test fun onlyTheLinesAboutTheTrackAreKept() {
        val kept = TeyesTrackTextTool.relevant(listOf(
            "D/WifiService( 1): scan",
            "I/SpdService-SourcManager( 501): syncSourceInfo",
            "D/CarInfoManage( 812): OnUpdateSourceInfo",
            "E/AndroidRuntime( 812): FATAL EXCEPTION",
            "D/Foo( 9): com.altair571.tarmac entered",
        ), "com.altair571.tarmac")
        assertEquals(4, kept.size)
    }

    @Test fun theRootCommandTakesNoTextFromOutside() {
        val command = TeyesTrackTextTool.command("/data/app/x/base.apk", "com.altair571.tarmac",
            TeyesTrackTextTool.Record.OTHER_MEDIA, enterSource = false)
        assertTrue(command, command.startsWith("CLASSPATH='/data/app/x/base.apk' app_process /system/bin "))
        assertTrue(command, command.endsWith("com.shilapi.xcertplay.headunit.TeyesTrackTextTool com.altair571.tarmac OTHER_MEDIA keep 2>&1"))
        try {
            TeyesTrackTextTool.command("/a.apk", "x; reboot", TeyesTrackTextTool.Record.MEDIA, enterSource = true)
            fail("a package name with shell characters must not reach su")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
