package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compact/full and RTL screenshots of the TEYES head-unit cards at 1.5x text size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeadUnitSettingsScreenshotsTest {
    @Test fun headUnitCardsRenderAtLargeFontsInThreeLayouts() {
        val packages = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        for (name in listOf("com.spd.carinfo", "com.spd.setting")) {
            packages.installPackage(PackageInfo().apply {
                packageName = name
                applicationInfo = ApplicationInfo().apply { packageName = name; flags = ApplicationInfo.FLAG_SYSTEM }
            })
        }
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            for ((filename, widthDp, language) in listOf(
                Triple("compact-ja", 320, "ja-JP"),
                Triple("full-en", 960, "en-US"),
                Triple("compact-ar-rtl", 320, "ar-SA"),
            )) {
                val locale = Locale.forLanguageTag(language)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                for (builder in listOf("headUnitSettings", "headUnitSignalSettings")) {
                    DiPlayActivity::class.java.getDeclaredMethod(builder, LinearLayout::class.java)
                        .apply { isAccessible = true }.invoke(activity, column)
                }
                assertTrue(allViews(column).filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.settings_head_unit_wheel_keys)
                })
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                column.layout(0, 0, width, column.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, column.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    column.draw(Canvas(bitmap))
                    val directory = File("build/reports/head-unit-ui").apply { mkdirs() }
                    FileOutputStream(File(directory, "$filename.png")).use { output ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            activity.finish()
        }
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }
}
