package com.shilapi.xcertplay.media

import android.content.Context
import com.shilapi.xcertplay.media.DriverSideAudio.Side
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DriverSideAudioTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After fun tearDown() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    private fun pan(source: ByteArray, channels: Int, side: Side, offset: Int = 0, length: Int = source.size - offset): ByteArray {
        val out = ByteArray(DriverSideAudio.outputBytes(length, channels))
        assertEquals(out.size, DriverSideAudio.pan(source, offset, length, channels, side, out))
        return out
    }

    @Test fun monoGoesToTheLeftChannelOnly() {
        assertArrayEquals(pcm(1000, 0, -2000, 0, 32767, 0), pan(pcm(1000, -2000, 32767), 1, Side.LEFT))
    }

    @Test fun monoGoesToTheRightChannelForARightHandDriveCar() {
        assertArrayEquals(pcm(0, 1000, 0, -32768), pan(pcm(1000, -32768), 1, Side.RIGHT))
    }

    @Test fun stereoIsAveragedOntoTheDriverSide() {
        // A voice centred in both channels keeps its level; one-sided content is halved.
        assertArrayEquals(pcm(1200, 0, 500, 0), pan(pcm(1200, 1200, 1000, 0), 2, Side.LEFT))
        assertArrayEquals(pcm(0, -1500), pan(pcm(-1000, -2000), 2, Side.RIGHT))
    }

    @Test fun offsetIsHonouredAndAPartialFrameIsDropped() {
        val source = pcm(9999, 300, 400) + byteArrayOf(7)
        assertArrayEquals(pcm(300, 0, 400, 0), pan(source, 1, Side.LEFT, offset = 2))
        assertEquals(8, DriverSideAudio.outputBytes(5, 1))
        assertEquals(4, DriverSideAudio.outputBytes(7, 2))
    }

    @Test fun theSettingIsOffByDefaultAndFollowsTheDrivingSide() {
        assertNull(DriverSideAudio.side(context))
        DriverSideAudio.setEnabled(context, true)
        assertEquals(Side.LEFT, DriverSideAudio.side(context))
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().putBoolean("right_hand_drive", true).commit()
        assertEquals(Side.RIGHT, DriverSideAudio.side(context))
        DriverSideAudio.setEnabled(context, false)
        assertNull(DriverSideAudio.side(context))
    }
}
