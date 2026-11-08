package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.shilapi.xcertplay.host.R
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Icons for CarPlay's car button: BYD's logo as shipped, and Tarmac's own designs on iOS-style tiles. */
internal enum class CarButtonStyle(
    val key: String,
    val label: Int,
    val glyph: Int?,
    val top: Int,
    val bottom: Int,
    /** The glyph is a full adaptive-icon foreground with its own safe zone, not a 24 dp symbol. */
    val fullBleed: Boolean = false,
) {
    BYD("byd", R.string.settings_car_button_style_byd, null, 0, 0),
    TARMAC("tarmac", R.string.settings_car_button_style_tarmac, R.drawable.ic_tarmac_foreground,
        Color.rgb(72, 72, 74), Color.rgb(17, 17, 19), fullBleed = true),
    CAR("car", R.string.settings_car_button_style_car, R.drawable.ic_dp_car, Color.rgb(10, 132, 255), Color.rgb(0, 88, 208)),
    HOME("home", R.string.settings_car_button_style_home, R.drawable.ic_dp_home, Color.rgb(52, 199, 89), Color.rgb(36, 138, 61)),
    WHEEL("wheel", R.string.settings_car_button_style_wheel, R.drawable.ic_dp_steering_wheel,
        Color.rgb(255, 149, 0), Color.rgb(214, 98, 0)),
}

/**
 * Saves a style as the car button's custom image, which every connection already sends. The image's
 * fingerprint is kept beside it, so a later image from the CarPlay menu or the crop screen reads as the
 * driver's own without those paths knowing about styles.
 */
internal object CarButtonStyles {
    private const val SIZE = 256
    private const val PREFS = "car_button_style"
    private const val KEY = "rendered"

    /** The style in use, or null when the car button shows the driver's own image. */
    fun current(context: Context): CarButtonStyle? {
        val file = AirPlayPersistence.loadCustomAirPlayIconFile(context) ?: return CarButtonStyle.BYD
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val key = saved.substringBefore(':')
        val fingerprint = saved.substringAfter(':')
        if (runCatching { fingerprint(file.readBytes()) }.getOrNull() != fingerprint) return null
        return CarButtonStyle.entries.firstOrNull { it.key == key }
    }

    fun apply(context: Context, style: CarButtonStyle) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (style == CarButtonStyle.BYD) {
            AirPlayPersistence.clearCustomAirPlayIcon(context)
            preferences.edit().remove(KEY).apply()
            return
        }
        val image = render(context, style)
        AirPlayPersistence.saveCustomAirPlayIcon(context, image)
        preferences.edit().putString(KEY, "${style.key}:${fingerprint(image)}").apply()
    }

    /** A square tile with a top-to-bottom gradient and a white symbol; iOS rounds the corners itself. */
    fun render(context: Context, style: CarButtonStyle): ByteArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, SIZE.toFloat(), style.top, style.bottom, Shader.TileMode.CLAMP)
            })
            style.glyph?.let { context.getDrawable(it)?.mutate() }?.let { glyph ->
                if (style.fullBleed) {
                    glyph.setBounds(0, 0, SIZE, SIZE)
                } else {
                    val inset = SIZE * 22 / 100
                    glyph.setTint(Color.WHITE)
                    glyph.setBounds(inset, inset, SIZE - inset, SIZE - inset)
                }
                glyph.draw(canvas)
            }
            return ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
