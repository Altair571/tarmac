package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent

/** How DiPlay opens itself when the car starts: after boot, or when a sleeping head unit wakes. */
internal object CarStartLaunch {
    // Shaped like the launcher icon's intent: a USB authenticator or iPhone plugged in can open CarPlay
    // before this launch, and DiPlayActivity then returns to that session instead of showing Home over it.
    fun intent(context: Context): Intent = Intent(context, DiPlayActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }
}
