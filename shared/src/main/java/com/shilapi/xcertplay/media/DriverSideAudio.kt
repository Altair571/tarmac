package com.shilapi.xcertplay.media

import android.content.Context

/**
 * Settings → Advanced, off by default: navigation prompts and calls play only from the speakers on the
 * driver's side. No Android API lets an app pick a seat or a headrest speaker on these head units, so the
 * mono voice is written into one channel of a stereo track instead. A head unit that mixes these streams
 * down to mono plays them as before.
 */
object DriverSideAudio {
    enum class Side { LEFT, RIGHT }

    private const val PREFS = "xcertplay_airplay"
    private const val KEY_ENABLED = "driver_side_voice"
    /** AirPlayPersistence's driving-side key; Android Auto cannot reach that class from this module. */
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** The driver's side when the setting is on, from the Right-hand drive setting; null when it is off. */
    fun side(context: Context): Side? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return null
        return if (prefs.getBoolean(KEY_RIGHT_HAND_DRIVE, false)) Side.RIGHT else Side.LEFT
    }

    /** Bytes [pan] writes for [length] bytes of [channels]-channel 16-bit PCM; a trailing partial frame is dropped. */
    fun outputBytes(length: Int, channels: Int): Int =
        if (channels >= 2) length - length % 4 else (length - length % 2) * 2

    /**
     * Writes 16-bit little-endian PCM as stereo with only [side] sounding. Mono plays at its own level;
     * stereo is averaged first, so a voice centred in both channels keeps its loudness. Returns the bytes
     * written to [out], which must hold [outputBytes].
     */
    fun pan(source: ByteArray, offset: Int, length: Int, channels: Int, side: Side, out: ByteArray): Int {
        val written = outputBytes(length, channels)
        val sound = if (side == Side.LEFT) 0 else 2
        val quiet = 2 - sound
        var input = offset
        var output = 0
        while (output < written) {
            val sample = if (channels >= 2) {
                (pcm16(source, input) + pcm16(source, input + 2)) / 2
            } else {
                pcm16(source, input)
            }
            input += if (channels >= 2) 4 else 2
            out[output + sound] = sample.toByte()
            out[output + sound + 1] = (sample shr 8).toByte()
            out[output + quiet] = 0
            out[output + quiet + 1] = 0
            output += 4
        }
        return written
    }

    private fun pcm16(data: ByteArray, at: Int): Int = (data[at + 1].toInt() shl 8) or (data[at].toInt() and 0xff)
}
