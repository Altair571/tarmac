package com.shilapi.xcertplay

import android.graphics.Color

/** Semantic colors for DiPlay-owned chrome. Media canvases and cluster-specific palettes stay separate. */
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
) {
    companion object {
        fun of(night: Boolean): DiPlayPalette = if (night) DARK else LIGHT

        // iOS system colours: grouped background, secondary grouped cells, label colours and tints.
        val DARK = DiPlayPalette(
            background = Color.rgb(0, 0, 0),
            surface = Color.rgb(28, 28, 30),
            button = Color.rgb(44, 44, 46),
            outline = Color.rgb(44, 44, 46),
            railSelected = Color.rgb(10, 116, 226),
            railSelectedOutline = Color.rgb(10, 116, 226),
            primaryText = Color.rgb(255, 255, 255),
            secondaryText = Color.rgb(152, 152, 159),
            accent = Color.rgb(10, 116, 226),
            onAccent = Color.WHITE,
            accentTrack = Color.rgb(48, 209, 88),
            trackOff = Color.rgb(57, 57, 61),
            warning = Color.rgb(255, 159, 10),
            success = Color.rgb(48, 209, 88),
            danger = Color.rgb(255, 69, 58),
            ripple = 0x29FFFFFF,
            focusRing = Color.rgb(10, 116, 226),
            systemBar = Color.rgb(0, 0, 0),
            systemBarIconsAreDark = false,
            overlayBackground = Color.rgb(28, 28, 30),
            overlayPrimaryText = Color.WHITE,
            overlaySecondaryText = Color.rgb(152, 152, 159),
            overlayAccent = Color.rgb(48, 209, 88),
            overlayOnAccent = Color.BLACK,
            overlayAccentTrack = Color.rgb(48, 209, 88),
            overlayTrackOff = Color.rgb(57, 57, 61),
            overlayDanger = Color.rgb(255, 69, 58),
        )

        val LIGHT = DiPlayPalette(
            background = Color.rgb(242, 242, 247),
            surface = Color.WHITE,
            button = Color.rgb(238, 238, 240),
            outline = Color.rgb(229, 229, 234),
            railSelected = Color.rgb(0, 98, 204),
            railSelectedOutline = Color.rgb(0, 98, 204),
            primaryText = Color.BLACK,
            secondaryText = Color.rgb(108, 108, 112),
            accent = Color.rgb(0, 98, 204),
            onAccent = Color.WHITE,
            accentTrack = Color.rgb(36, 138, 61),
            trackOff = Color.rgb(233, 233, 234),
            warning = Color.rgb(166, 82, 0),
            success = Color.rgb(30, 123, 52),
            danger = Color.rgb(255, 59, 48),
            ripple = 0x1F000000,
            focusRing = Color.rgb(0, 98, 204),
            systemBar = Color.rgb(242, 242, 247),
            systemBarIconsAreDark = true,
            overlayBackground = Color.rgb(242, 242, 247),
            overlayPrimaryText = Color.BLACK,
            overlaySecondaryText = Color.rgb(108, 108, 112),
            overlayAccent = Color.rgb(30, 123, 52),
            overlayOnAccent = Color.WHITE,
            overlayAccentTrack = Color.rgb(36, 138, 61),
            overlayTrackOff = Color.rgb(233, 233, 234),
            overlayDanger = Color.rgb(215, 0, 21),
        )
    }
}

/** The iOS system colours, in their dark and light variants, for icon squares and category tints. */
internal object IosColors {
    fun blue(night: Boolean) = if (night) Color.rgb(10, 132, 255) else Color.rgb(0, 122, 255)
    fun indigo(night: Boolean) = if (night) Color.rgb(94, 92, 230) else Color.rgb(88, 86, 214)
    fun pink(night: Boolean) = if (night) Color.rgb(255, 55, 95) else Color.rgb(255, 45, 85)
    fun green(night: Boolean) = if (night) Color.rgb(48, 209, 88) else Color.rgb(52, 199, 89)
    fun orange(night: Boolean) = if (night) Color.rgb(255, 159, 10) else Color.rgb(255, 149, 0)
    fun red(night: Boolean) = if (night) Color.rgb(255, 69, 58) else Color.rgb(255, 59, 48)
    fun teal(night: Boolean) = if (night) Color.rgb(100, 210, 255) else Color.rgb(50, 173, 230)
    fun gray(night: Boolean) = if (night) Color.rgb(142, 142, 147) else Color.rgb(142, 142, 147)
}

/** The tint a driver picks for buttons, selection and focus, as iOS apps let you pick an accent. */
internal enum class AccentTint(val key: String, private val dark: Int, private val light: Int) {
    BLUE("blue", Color.rgb(10, 116, 226), Color.rgb(0, 98, 204)),
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
    val onColor = if (androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.35) Color.BLACK else Color.WHITE
    return copy(accent = color, onAccent = onColor, railSelected = color, railSelectedOutline = color, focusRing = color)
}
