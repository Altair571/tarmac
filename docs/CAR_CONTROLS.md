# Car controls with CarPlay (research, November 2026)

Aston Martin's CarPlay Ultra puts climate, drive modes and vehicle settings inside CarPlay. This note
records what Tarmac can and cannot do toward that on an Android head unit with access to the car's
CAN bus, and a plan for the part that is possible.

## Short answer

- **CarPlay Ultra itself: no.** Ultra is a per-manufacturer integration that Apple certifies. The iPhone
  draws the car's own climate and settings screens from design work Apple does with each maker, and it
  offers Ultra only to vehicles Apple has approved. Nothing an accessory sends can unlock it, and there
  is no public protocol to implement. Apple has said Ultra cannot be added to cars that already have
  regular CarPlay, because it needs deep integration with the car's electronics, displays and software.
- **Car controls beside CarPlay: yes.** Tarmac already draws its own Android panel next to CarPlay
  ([SIDE_PANEL.md](SIDE_PANEL.md)) using CarPlay view areas ([VIEW_AREAS.md](VIEW_AREAS.md)). The same
  mechanism can pin climate and comfort controls to one edge of the screen, which is how Ultra presents
  climate on Aston Martin's 10.25-inch display. Commands go to the car through the head unit.
- **Car controls inside CarPlay: possible with a companion iPhone app.** Tarmac already declares an iAP2
  External Accessory protocol (`com.shilapi.xcertplay`) and accepts `StartExternalAccessoryProtocolSession`
  (0xEA00). An iPhone app that names the same protocol can open a session to Tarmac and show controls in
  CarPlay with Apple's CarPlay templates. The catch is that Apple must grant that app a CarPlay entitlement.

## Why Ultra is out of reach

- The iPhone renders Ultra's gauges and car screens with assets and layouts made for each brand. A
  receiver cannot supply them.
- The iPhone gates features by identification. In Tarmac's own tests, declaring Road Object Detection
  (identification parameter 33) made the iPhone reject the whole identification
  (`IdentificationRejected` for 0x0021). The same happened for other fields reserved for approved vehicles.
- Tarmac authenticates with an experimental accessory identity recovered from a Carlinkit dongle, not a
  certificate for a specific vehicle model.
- Extra iAP2 vehicle-status fields (outside temperature, wipers, alerts) are accepted and subscribed to.
  Nothing visible changes in CarPlay or Apple Maps, and the data is read-only to the iPhone.

## Path A: Android controls next to CarPlay (recommended first)

CarPlay moves into a view area that leaves a strip free. In that strip Tarmac draws native controls:
temperature, fan, seat heat, defrost, drive-mode shortcuts. A wheel key or the CarPlay car button can
show and hide the strip. Every command goes through one `VehicleControlBackend` interface with one
adapter per platform:

| Platform | How commands reach the car | Status |
|---|---|---|
| BYD DiLink | `service call autoservice` set-int transactions over Tarmac's local ADB. Tarmac already reads speed, gear, battery and tyres this way. The climate devices and fields are listed with the autoservice catalogue. | Feasible; reading proven, writing to verify per firmware |
| Aftermarket units with a CAN box (Raise, Hiworld, XBS and similar decoders) | The CAN box talks to the head unit's MCU over UART. The vendor's system app decodes it. Third-party access is through the vendor's service, for example `com.syu.ms` on FYT/SYU units, or a vendor broadcast. Some boxes accept climate commands for cars whose climate is driven from the screen. | Feasible per vendor; needs the unit in hand |
| A USB CAN adapter (CANable/slcan, PCAN-USB) on the comfort bus | Android USB host plus the slcan text protocol. Reading is generic. Writing needs the car's own message IDs, from a DBC file or from capturing traffic. | Feasible for enthusiasts; car-specific and risky |
| OBD-II dongles (ELM327/STN) | Diagnostic requests only: gauges and fault codes. | Read-only |
| Android Automotive OS (Google built-in) | Climate properties need `CONTROL_CAR_CLIMATE`, a signature/privileged permission. | Not available to a sideloaded app |

## Path B: controls inside CarPlay

1. Build an iPhone app that lists `com.shilapi.xcertplay` (or a renamed protocol) under
   `UISupportedExternalAccessoryProtocols` and opens an `EASession` to Tarmac.
2. Define a small message set over that session: state snapshots from the car, and commands from the
   phone, with sequence numbers and acknowledgements.
3. Use CarPlay list and grid templates for climate tiles. The app needs a CarPlay entitlement from Apple.
   The automaker category fits but is granted to car makers; the driving-task category is the realistic
   request for an individual. Without the entitlement, the app can still control the car from the phone screen.
4. Add App Intents so Siri can say "set the temperature to 21" through the same session.

Tarmac's side would forward each command to the same `VehicleControlBackend` as Path A.

## Safety rules for any write path

- Allow comfort functions only: climate, seat heating and ventilation, defrost, ambient light, mirror fold.
  Never send messages for steering, braking, powertrain, airbags, door locks while moving, or lights required by law.
- Use an allow-list of exact messages and value ranges. Reject anything else, rate-limit commands, and
  stop sending on any bus error.
- Send only on the comfort bus or through the head unit's own service, never onto a powertrain bus.
  Many current cars have a gateway with message authentication, so spoofed frames are dropped or logged as faults.
- Make the feature opt-in, explain the warranty and inspection risk, and keep the car's physical controls
  working. Aston Martin keeps physical climate and drive-mode controls alongside Ultra too.

## Dashboard map on factory clusters

Whether CarPlay's dashboard map can reach a car's own instrument cluster when the head unit is aftermarket
is answered in [OEM_CLUSTER.md](OEM_CLUSTER.md). In short:

- **Android-driven clusters** that appear as a second display can show the map video. Tarmac offers them in
  Settings → Advanced → CarPlay map on dashboard → Dashboard screen ([SECOND_DISPLAY_MAP.md](SECOND_DISPLAY_MAP.md)).
- **CAN-box navigation passthrough** (Raise, Hiworld and similar) can, on some car models, put CarPlay's turn
  arrow and distance on the factory cluster. Tarmac offers CarPlay's turns as AMap's standard guidance
  broadcast (Settings → Navigation → Send turns to the car's cluster and HUD), which many head units' own
  CAN-box services already forward. Writing a CAN-box protocol directly is car-specific, needs the vendor's
  API, and is a write path under the rules above.
- **Clusters that are separate ECUs** never take video from an aftermarket unit; at most the turn-by-turn data
  that the factory head unit used to send.

The car data adapter ([VEHICLE_DATA.md](VEHICLE_DATA.md)) reads from the bus only and is not a write path.

## Suggested order

1. Path A with the BYD adapter, since Tarmac already has ADB access and the field catalogue.
2. A vendor CAN-box adapter for the head unit you own: read the climate state first, then add commands.
3. Path B once Path A's command model is stable.

## Sources

- Aston Martin and Apple launch, climate via touchscreen, buttons or Siri, physical controls kept:
  [Auto Express](https://autoexpress.co.uk/aston-martin/dbx/366834/state-art-apple-carplay-ultra-makes-debut-aston-martin-dbx),
  [Complete Car](https://www.completecar.ie/car-news/article/14107/Aston-Martin-introduces-CarPlay-Ultra),
  [Carscoops](https://www.carscoops.com/2025/05/apple-carplay-ultra-is-finally-here-to-take-over-every-screen-in-your-car/)
- Ultra cannot be added to existing CarPlay cars; it needs deep integration:
  [TechRadar hands-on](https://www.techradar.com/vehicle-tech/hybrid-electric-vehicles/ive-tried-apple-carplay-ultra-it-fixes-everything-thats-irritating-about-carplay-but-theres-a-catch)
- Rollout and automakers: [MacRumors, April 2026](https://macrumors.com/2026/04/18/apple-says-carplay-ultra-is-coming-to-these-vehicle-brands/),
  [9to5Mac, May 2026](https://9to5mac.com/2026/05/04/carplay-ultra-automakers/)
- Automaker apps for vehicle settings and climate since iOS 9:
  [iPhone in Canada](https://www.iphoneincanada.ca/news/carplay-automaker-apps-ios-9)
- Tarmac's own findings: [SIDE_PANEL.md](SIDE_PANEL.md), [VIEW_AREAS.md](VIEW_AREAS.md),
  `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2IdentificationClient.kt`
