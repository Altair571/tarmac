# Steering-wheel keys on aftermarket head units

Tarmac maps BYD's and Geely's wheel keys through code written for those cars (`WheelKeyService.kt`,
`GeelyWheelKeys.kt`, `CarPlayMediaKeys.kt`, `NavigationWheelKeyPolicy.kt`). An aftermarket head unit sends
its wheel buttons as whatever key events its own steering-wheel-control (SWC) set-up produces. **More wheel
keys** lets the driver learn any of those key events for a CarPlay action.

## Where it is

Settings → Vehicle → Wheel keys → **More wheel keys**. Off by default.

| Action | What the iPhone gets |
|---|---|
| Next track | CarPlay media HID "next" (`CarPlayMediaButton.NEXT`) |
| Previous track | CarPlay media HID "previous" |
| Answer / hang up | Telephony HID Hook Switch for a ringing CarPlay call, Drop for a dialling or active one |
| CarPlay home | The knob report's home button (`AirPlayKnobState(home = true)`), as a car's CarPlay home key sends it |

Siri and play/pause already had learnable keys in the same card (Siri key, Wheel play/pause). They keep
their own settings, including Siri's double press and long press, rather than being duplicated here. Together
they cover all six actions.

Tap an action to learn its key, then press the wheel button within ten seconds. Tap a learned action again
to learn a new key or remove it.

## Rules

- A key acts only while a CarPlay session exists. Without one it keeps the car's own action.
- During a call only the answer / hang-up key acts, and only on a CarPlay call (iAP2 call state). With no
  CarPlay call it goes to the car, so a native Bluetooth call is never declined by mistake. The other keys
  keep the car's own call handling.
- The Siri key, the play/pause key, and BYD's map-zoom and joystick keys while those are on, win over a learned
  key. Learning such a key is refused with "That key is already the … key".
- Down and up of a press always stay together (`WheelKeyPresses`), even if a setting or the session changes
  mid-press.

## Where keys are caught

1. The wheel-key accessibility service (`WheelKeyService.mappedKey`), in the input filter, before the head
   unit's window manager acts on the key. This needs the service on, as the card offers (ADB or the
   accessibility settings).
2. The CarPlay screen (`CarPlayHostActivity.dispatchKeyEvent`), for keys that reach the app window instead,
   or when the service is off.
3. Learning listens in the service and in the Settings window at once, like the Siri key does, because some
   units send a key to one and not the other.

## Vendor broadcasts (FYT/SYU, MTCD/MTCE, TS10)

Not implemented:

- **MTCD/MTCE**: the documented broadcast is `com.microntek.irkeyDown` with an int extra `keyCode`, as used
  by the open-source MtcdTools. It was left out at the owner's request.
- **FYT/SYU and TS10**: I found no public documentation of a key broadcast. These units let the driver
  assign each wheel button a function in their own SWC learning screen (factory settings). When that function
  is a media or navigation key, Android receives an ordinary key event and More wheel keys can learn it. A
  receiver for an undocumented intent name would be guesswork, so none is included.

## Code

- `common/.../WheelKeyMap.kt`: settings, priority rules (`claimable`, `takenBy`), the decision
  (`decide`), and sending (`perform`).
- `WheelKeyService`: `mappedKey()`, `learnAny()`. Role learning is no longer cancelled by the eligibility poll
  while only a learned-key capture is running.
- `CarPlayHostActivity.dispatchKeyEvent` and `TarmacActivity` (the Settings controls and window learning).
- Tests: `WheelKeyMapTest`; `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

## Not verified without hardware

Tested only with Robolectric key events. Not checked on an aftermarket unit:

- which key codes, scan codes and input-device names a given unit's SWC produces, and whether they reach the
  accessibility service, the app window, or neither (some units consume the keys in their MCU service);
- that CarPlay's home action from the knob report behaves on every iOS version as the car's home key does.
