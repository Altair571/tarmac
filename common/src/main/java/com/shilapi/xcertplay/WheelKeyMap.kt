package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.CarPlayCallCard
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * Learned wheel keys for any head unit (docs/WHEEL_KEY_MAPPING.md): whatever key event an aftermarket unit
 * delivers for its steering-wheel buttons can be learned for next, previous, answer/hang up or CarPlay home.
 * Siri and play/pause keep their own learnable keys ([WheelZoomSettings.Role.SIRI],
 * [WheelZoomSettings.Role.MEDIA_PLAY_PAUSE]), which win over a key learned here, as do BYD's map-zoom and
 * joystick keys while those are on.
 *
 * The key service ([WheelKeyService]) acts on these keys in its input filter; the CarPlay screen does the same
 * when a key reaches it instead.
 */
internal object WheelKeyMap {
    private const val PREFS = "diplay_wheel_key_map"
    private const val KEY_ENABLED = "enabled"

    enum class Action { NEXT, PREVIOUS, CALL, HOME }

    /** What a learned key sends to the iPhone. */
    enum class Command { NEXT, PREVIOUS, ANSWER, END, HOME }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        WheelKeyService.settingsChanged()
    }

    fun key(context: Context, action: Action): WheelKey? = WheelKey.decode(prefs(context).getString(action.name, null))

    fun assign(context: Context, action: Action, key: WheelKey) {
        prefs(context).edit().putString(action.name, key.encode()).apply()
    }

    fun clear(context: Context, action: Action) {
        prefs(context).edit().remove(action.name).apply()
    }

    /** The learned action for [key], when learned keys are on and nothing with priority owns the key. */
    fun actionFor(context: Context, key: WheelKey): Action? {
        if (!enabled(context) || !claimable(context, key)) return null
        return Action.entries.firstOrNull { key(context, it) == key }
    }

    /** False for the Siri key, the play/pause key, and an active zoom or joystick key: those keep their meaning. */
    fun claimable(context: Context, key: WheelKey): Boolean =
        !WheelZoomSettings.isSiriKey(context, key) && !WheelZoomSettings.isMediaPlayPauseKey(context, key) &&
            !((WheelZoomSettings.enabled(context) || WheelZoomSettings.joystick(context)) &&
                WheelZoomSettings.roleOf(context, key) != null)

    /**
     * What already has [key], for the "already used" message: a role first, since roles win when a key is
     * pressed, then another learned action. Null when the key is free.
     */
    fun takenBy(context: Context, action: Action, key: WheelKey): Any? {
        if (WheelZoomSettings.isSiriKey(context, key)) return WheelZoomSettings.Role.SIRI
        if (WheelZoomSettings.isMediaPlayPauseKey(context, key)) return WheelZoomSettings.Role.MEDIA_PLAY_PAUSE
        if (WheelZoomSettings.enabled(context) || WheelZoomSettings.joystick(context)) {
            WheelZoomSettings.roleOf(context, key)?.let { return it }
        }
        return Action.entries.firstOrNull { it != action && key(context, it) == key }
    }

    /**
     * What a press of a learned key does now; null leaves the key to the car. Without a CarPlay session every key
     * keeps the car's own action. During a call only the call key acts: it answers a ringing call and ends a
     * dialling or active one, the car's own call handling keeps the rest.
     */
    fun decide(action: Action, session: Boolean, call: CarPlayCallCard.Phase?, inCall: Boolean): Command? {
        if (!session) return null
        return when (action) {
            Action.CALL -> when (call) {
                CarPlayCallCard.Phase.RINGING -> Command.ANSWER
                CarPlayCallCard.Phase.DIALING, CarPlayCallCard.Phase.ACTIVE -> Command.END
                null -> null
            }
            Action.NEXT -> Command.NEXT.takeUnless { inCall }
            Action.PREVIOUS -> Command.PREVIOUS.takeUnless { inCall }
            Action.HOME -> Command.HOME.takeUnless { inCall }
        }
    }

    fun perform(command: Command, controller: CarPlayController?): Boolean = when (command) {
        Command.NEXT -> controller?.sendMediaButton(CarPlayMediaButton.NEXT) == true
        Command.PREVIOUS -> controller?.sendMediaButton(CarPlayMediaButton.PREVIOUS) == true
        Command.ANSWER -> controller?.answerCall() == true
        Command.END -> controller?.endCall() == true
        // The knob report's home button, as a car's own CarPlay home key sends it.
        Command.HOME -> controller?.sendKnob(AirPlayKnobState(home = true)) == true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
