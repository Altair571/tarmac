package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.androidauto.ui.AndroidAutoActivity
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidAutoHomeEntryTest {
    @Config(qualifiers = "en-w1280dp-h720dp-land")
    @Test fun homeOpensAndroidAuto() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val row = descendants(activity.window.decorView).filterIsInstance<TextView>()
            .single { it.text == activity.getString(R.string.home_android_auto) }

        // The label sits inside the clickable row, as the USB and Settings rows do.
        generateSequence(row as View) { it.parent as? View }.first { it.isClickable }.performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(AndroidAutoActivity::class.java.name, started.component?.className)
        controller.pause().stop().destroy()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
