package com.shilapi.xcertplay.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import java.io.ByteArrayOutputStream

/** CarPlay navigation prompts and calls play on the driver's side only when the setting is on; music never does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class DriverSideRendererTest {
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    private val written = ByteArrayOutputStream()
    private val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> written.write(data) }
    private val opened = mutableListOf<AutoCloseable>()

    init {
        ShadowAudioTrack.addAudioDataListener(listener)
    }

    @After fun tearDown() {
        ShadowAudioTrack.removeAudioDataListener(listener)
        opened.forEach { runCatching { it.close() } }
    }

    private fun renderer(audioType: String, channels: Int, side: DriverSideAudio.Side?): Any =
        type.declaredConstructors.single { it.parameterCount == 14 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, 48_000, channels, 96, audioType),
                false, false, 0, 0, AudioFocusCoordinator(null, false, false), 0, 300,
                { _: String -> }, 0L, false, null, false, side)
            .also { opened += it as AutoCloseable; type.getDeclaredMethod("createTrack").apply { isAccessible = true }.invoke(it) }

    private fun track(renderer: Any) = type.getDeclaredField("track").apply { isAccessible = true }.get(renderer) as AudioTrack

    private fun write(renderer: Any, pcm: ByteArray) {
        type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(renderer, pcm, 0, pcm.size)
    }

    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    @Test fun navigationPromptsPlayOnTheDriversSideOfAStereoTrack() {
        val navigation = renderer("default", 1, DriverSideAudio.Side.LEFT)
        assertEquals(2, track(navigation).channelCount)
        // The fade-in scales only the first 10 ms; skip it so the samples are exact.
        type.getDeclaredField("fadeApplied").apply { isAccessible = true }.setBoolean(navigation, true)
        write(navigation, pcm(1000, -2000, 3000))
        assertArrayEquals(pcm(1000, 0, -2000, 0, 3000, 0), written.toByteArray())
    }

    @Test fun callsPlayOnTheRightForARightHandDriveCar() {
        val call = renderer("telephony", 1, DriverSideAudio.Side.RIGHT)
        assertEquals(2, track(call).channelCount)
        type.getDeclaredField("fadeApplied").apply { isAccessible = true }.setBoolean(call, true)
        write(call, pcm(500, 600))
        assertArrayEquals(pcm(0, 500, 0, 600), written.toByteArray())
    }

    @Test fun musicAndTheSettingOffKeepTheSourceLayout() {
        val music = renderer("media", 2, DriverSideAudio.Side.LEFT)
        assertEquals(2, track(music).channelCount)
        type.getDeclaredField("fadeApplied").apply { isAccessible = true }.setBoolean(music, true)
        write(music, pcm(100, 200))
        assertArrayEquals(pcm(100, 200), written.toByteArray())

        written.reset()
        val off = renderer("default", 1, null)
        assertEquals(1, track(off).channelCount)
        type.getDeclaredField("fadeApplied").apply { isAccessible = true }.setBoolean(off, true)
        write(off, pcm(700))
        assertArrayEquals(pcm(700), written.toByteArray())
    }

    @Test fun aPannedTrackCountsStereoFrames() {
        val navigation = renderer("default", 1, DriverSideAudio.Side.LEFT)
        type.getDeclaredField("fadeApplied").apply { isAccessible = true }.setBoolean(navigation, true)
        write(navigation, ByteArray(960))
        assertEquals(480L, type.getDeclaredField("totalWrittenFrames").apply { isAccessible = true }.getLong(navigation))
        assertTrue(written.size() == 1920)
    }
}
