package com.shilapi.xcertplay

import android.graphics.Color
import androidx.core.graphics.ColorUtils

/**
 * Semantic colors for Tarmac-owned chrome. Media canvases and cluster-specific palettes stay separate.
 *
 * The look is a road at night and by day: asphalt neutrals with a little blue in them, white and amber
 * road paint, and signal colours for state. [accent] fills controls; [accentText] is the same tint
 * brightened (or darkened by day) until it reads as text on a card or a button.
 */
internal data class DiPlayPalette(
    val background: Int,
    val surface: Int,
    val button: Int,
    val outline: Int,
    val railSelected: Int,
    val railSelectedOutline: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val onAccent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val warning: Int,
    val success: Int,
    val danger: Int,
    val ripple: Int,
    val focusRing: Int,
    val systemBar: Int,
    val systemBarIconsAreDark: Boolean,
    val overlayBackground: Int,
    val overlayPrimaryText: Int,
    val overlaySecondaryText: Int,
    val overlayAccent: Int,
    val overlayOnAccent: Int,
    val overlayAccentTrack: Int,
    val overlayTrackOff: Int,
    val overlayDanger: Int,
    /** The accent as text or a glyph on [background], [surface] or [button]. */
    val accentText: Int = accent,
    /** The lit top edge of a card: a hairline lighter than [outline] at night, the outline itself by day. */
    val hairline: Int = outline,
) {
    companion object {
        fun of(night: Boolean): DiPlayPalette = if (night) DARK else LIGHT

        /** The amber of a road's centre line: Tarmac's own mark, never a control colour. */
        val LANE_AMBER = Color.rgb(255, 197, 61)

        // Night tarmac: near-black asphalt, cards one step up, controls another.
        val DARK = DiPlayPalette(
            background = Color.rgb(7, 8, 10),
            surface = Color.rgb(20, 22, 26),
            button = Color.rgb(32, 35, 42),
            outline = Color.rgb(43, 47, 55),
            railSelected = Color.rgb(22, 104, 227),
            railSelectedOutline = Color.rgb(22, 104, 227),
            primaryText = Color.rgb(245, 246, 248),
            secondaryText = Color.rgb(154, 161, 172),
            accent = Color.rgb(22, 104, 227),
            onAccent = Color.WHITE,
            accentTrack = Color.rgb(52, 199, 119),
            trackOff = Color.rgb(58, 62, 71),
            warning = Color.rgb(255, 178, 36),
            success = Color.rgb(61, 214, 140),
            danger = Color.rgb(255, 92, 92),
            ripple = 0x24FFFFFF,
            focusRing = Color.rgb(116, 172, 255),
            systemBar = Color.rgb(7, 8, 10),
            systemBarIconsAreDark = false,
            overlayBackground = Color.rgb(20, 22, 26),
            overlayPrimaryText = Color.WHITE,
            overlaySecondaryText = Color.rgb(154, 161, 172),
            overlayAccent = Color.rgb(61, 214, 140),
            overlayOnAccent = Color.BLACK,
            overlayAccentTrack = Color.rgb(52, 199, 119),
            overlayTrackOff = Color.rgb(58, 62, 71),
            overlayDanger = Color.rgb(255, 92, 92),
            accentText = Color.rgb(116, 172, 255),
            hairline = Color.rgb(52, 57, 66),
        )

        // Day tarmac: pale concrete, white cards, ink text.
        val LIGHT = DiPlayPalette(
            background = Color.rgb(241, 242, 245),
            surface = Color.WHITE,
            button = Color.rgb(233, 235, 239),
            outline = Color.rgb(223, 226, 231),
            railSelected = Color.rgb(15, 98, 214),
            railSelectedOutline = Color.rgb(15, 98, 214),
            primaryText = Color.rgb(13, 15, 18),
            secondaryText = Color.rgb(90, 97, 108),
            accent = Color.rgb(15, 98, 214),
            onAccent = Color.WHITE,
            accentTrack = Color.rgb(31, 138, 76),
            trackOff = Color.rgb(218, 221, 227),
            warning = Color.rgb(154, 91, 0),
            success = Color.rgb(23, 116, 63),
            danger = Color.rgb(217, 45, 45),
            ripple = 0x1A000000,
            focusRing = Color.rgb(15, 98, 214),
            systemBar = Color.rgb(241, 242, 245),
            systemBarIconsAreDark = true,
            overlayBackground = Color.rgb(241, 242, 245),
            overlayPrimaryText = Color.rgb(13, 15, 18),
            overlaySecondaryText = Color.rgb(90, 97, 108),
            overlayAccent = Color.rgb(23, 116, 63),
            overlayOnAccent = Color.WHITE,
            overlayAccentTrack = Color.rgb(31, 138, 76),
            overlayTrackOff = Color.rgb(218, 221, 227),
            overlayDanger = Color.rgb(200, 30, 30),
            accentText = Color.rgb(11, 88, 196),
            hairline = Color.rgb(223, 226, 231),
        )
    }
}

/** Signal colours for icon wells and category tints, in their night and day variants. */
internal object Tints {
    fun blue(night: Boolean) = if (night) Color.rgb(76, 154, 255) else Color.rgb(27, 111, 224)
    fun indigo(night: Boolean) = if (night) Color.rgb(139, 135, 255) else Color.rgb(91, 85, 214)
    fun pink(night: Boolean) = if (night) Color.rgb(255, 107, 154) else Color.rgb(217, 58, 110)
    fun green(night: Boolean) = if (night) Color.rgb(61, 214, 140) else Color.rgb(29, 154, 87)
    fun orange(night: Boolean) = if (night) Color.rgb(255, 178, 36) else Color.rgb(208, 122, 0)
    fun red(night: Boolean) = if (night) Color.rgb(255, 107, 94) else Color.rgb(216, 57, 47)
    fun teal(night: Boolean) = if (night) Color.rgb(69, 207, 224) else Color.rgb(14, 148, 168)
    fun gray(night: Boolean) = if (night) Color.rgb(160, 167, 178) else Color.rgb(122, 129, 140)
}

/** The tint a driver picks for buttons, selection and focus. */
internal enum class AccentTint(val key: String, private val dark: Int, private val light: Int) {
    BLUE("blue", Color.rgb(22, 104, 227), Color.rgb(15, 98, 214)),
    GREEN("green", Color.rgb(48, 209, 88), Color.rgb(36, 138, 61)),
    ORANGE("orange", Color.rgb(255, 159, 10), Color.rgb(201, 100, 0)),
    PINK("pink", Color.rgb(255, 55, 95), Color.rgb(255, 45, 85)),
    PURPLE("purple", Color.rgb(191, 90, 242), Color.rgb(175, 82, 222)),
    RED("red", Color.rgb(255, 69, 58), Color.rgb(255, 59, 48));

    fun color(night: Boolean) = if (night) dark else light

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: BLUE
    }
}

/** Blue keeps the shared palette itself, so code that compares palettes by identity still matches. */
internal fun DiPlayPalette.withAccent(tint: AccentTint, night: Boolean): DiPlayPalette {
    if (tint == AccentTint.BLUE) return this
    val color = tint.color(night)
    // Orange and green are light enough that black reads better on them than white.
    val onColor = if (ColorUtils.calculateLuminance(color) > 0.35) Color.BLACK else Color.WHITE
    val text = readableOn(color, button, toward = if (night) Color.WHITE else Color.BLACK)
    return copy(accent = color, onAccent = onColor, railSelected = color, railSelectedOutline = color,
        focusRing = text, accentText = text)
}

/** [color] moved [toward] white or black in small steps until it reads as text on [surface]. */
private fun readableOn(color: Int, surface: Int, toward: Int): Int {
    var result = color
    repeat(20) {
        if (ColorUtils.calculateContrast(result, surface) >= 4.5) return result
        result = ColorUtils.blendARGB(result, toward, .08f)
    }
    return result
}
