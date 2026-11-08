# Reversing camera switch

On many head units, especially aftermarket ones, the factory or aftermarket reversing camera does not
appear while a full-screen app such as CarPlay is in front. Some units do show the camera on their own
and only need CarPlay out of the way; others need their camera app opened. This switch does either
when the car goes into reverse, and brings CarPlay back once it is out of reverse.

## Where it is

Settings → Vehicle → **Reversing camera** → **Switch to the camera when reversing**. Off by default.

| Control | What it does |
|---|---|
| Gear from | Automatic, BYD gear (ADB), Geely / ECARX sensor, Car data adapter, Head-unit broadcast. |
| Head-unit broadcast | Action, extra name and the extra's value in reverse, for units that announce reverse in a broadcast. |
| Camera app | None (CarPlay moves to the back), or a launchable app. Apps whose name looks like a camera app (camera, AVM, 360, rear, 倒车, 后视…) are listed first. |

When Tarmac could not bring CarPlay back from the background, the card says so and offers
"Display over other apps" (see below).

## Gear sources

| Source | How the gear is read | Where it works |
|---|---|---|
| BYD gear | `service call autoservice 5 i32 1011 i32 555745336` over Tarmac's local ADB, the existing `GEAR` field (1 P, 2 R, 3 N, 4 D). `BydNavigationOutputs.reversing()`. | BYD with ADB access approved |
| Geely / ECARX | The ECARX `ISensor` gear sensor that `EcarxWheelSpeed` already reads (`GEAR_REVERSE`). | Head units with the ECARX car SDK |
| Car data adapter | `VehicleAdapter.gear()`: a `gear` signal in the adapter's signal map, OBD-II mode 22 or CAN (see [VEHICLE_DATA.md](VEHICLE_DATA.md)). | Any car with a mapped gear signal |
| Head-unit broadcast | A runtime receiver for the action the driver enters. The extra is compared as text, so `1`, `true` and `on` all work. | Units whose system app broadcasts reverse |

Automatic picks the first that applies, in this order: BYD head unit, ECARX SDK present, adapter with a gear
signal, configured broadcast.

**No vendor broadcast names are built in.** I found no public documentation of a reverse broadcast for FYT/SYU,
TS10 or other aftermarket platforms. Rather than guessing intent names, the driver captures the unit's own
broadcast with an intent-logging app while shifting into reverse, and enters it.

## Behaviour

`ReverseCameraPolicy` (unit-tested) decides from one reading at a time:

- R takes the screen at once.
- The screen comes back 1.5 s after the car has left R, so R-D-R while parking does not flicker.
- If the gear becomes unknown while in R (ADB dropped, adapter unplugged, broadcast silent), the screen
  comes back after 5 s. A lost source never keeps CarPlay away.

`ReverseCameraSwitch` polls the gear every 300 ms on a background thread while a CarPlay session exists and
the switch is on. It acts only when CarPlay is the screen in front, and gives the screen back only if it was
the one that took it. CarPlay stays connected throughout. A session that ends in reverse also gives the
screen back.

Bringing CarPlay back is an activity start from the background. Since Android 10 that needs one of the
background-start exemptions. In Tarmac's case that is "Display over other apps" (which the centre map card
already asks for) or the bound wheel-key accessibility service. Head-unit firmware is often more lenient.

## Code

- `common/.../ReverseCamera.kt`: `ReverseCameraSettings`, `ReverseCameraPolicy`, `ReverseGearSources`,
  `ReverseBroadcast`, `ReverseCameraSwitch`, `ReverseCameraApps`.
- `CarPlayHostActivity` reports when CarPlay comes to the front or leaves it, and starts the watcher when
  a session becomes active.
- `BydParkedState.reversing()` and `EcarxWheelSpeed.readGear()` are the new read-only gear readers.
- Tests: `ReverseCameraTest`, `BydParkedStateTest.reverseIsGearTwo`.

## Not verified without hardware

None of this has run in a car. Unverified:

- BYD: whether polling the gear every 300 ms over the ADB shell is fast enough, and whether BYD's own camera
  already covers CarPlay (in which case the switch is harmless but unneeded).
- ECARX: that `getSensorEvent(GEAR)` changes promptly when the car shifts. Until now it was read only for
  dead reckoning.
- Whether `moveTaskToBack` reveals the camera on a given aftermarket unit, or whether its camera app must be
  chosen.
- That the background start brings CarPlay back on each firmware.
