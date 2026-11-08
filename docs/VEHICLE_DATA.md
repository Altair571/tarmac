# Car data from OBD-II or CAN (EV routing, reverse gear)

Apple Maps plans charging stops for an electric car when the accessory sends iAP2 VehicleStatus: range,
battery percentage, charging state (see `Iap2VehicleStatus.kt`). Tarmac has done this on BYD for a while,
reading autoservice over its local ADB. This feature adds a **car data adapter** for any car, plus the
gear for the reversing-camera switch ([REVERSE_CAMERA.md](REVERSE_CAMERA.md)).

## Where it is

Settings → Advanced → **Car data adapter (experimental)**. Off by default.

| Control | What it does |
|---|---|
| Adapter | Off, OBD-II over Bluetooth, OBD-II over USB, CAN over USB (slcan, listen-only). |
| Bluetooth adapter | The paired ELM327/STN adapter. Pair it in Android's Bluetooth settings first. |
| Allow the USB adapter | Shown when a USB serial adapter is plugged in but Tarmac has no permission yet. |
| Signal map | The car's JSON signal map (below). Required for CAN, optional for OBD-II. |
| Charge and range for Apple Maps | Sends the adapter's reading as VehicleStatus. Applies at the next connection. |
| Range at full charge | For cars that report only their charge: the range is then charge × this figure. |
| Charging connectors | The inlets declared to the iPhone with the EV. Applies at the next connection. |

On BYD, the existing switch in the BYD ADB card keeps priority: when it is on, the iPhone gets BYD's reading
and the adapter's is not used for VehicleStatus.

## Code

`shared/src/main/java/com/shilapi/xcertplay/vehicle/`:

- `VehicleData.kt`: `VehicleReading`, the `VehicleDataSource` interface, and `VehicleStatusMapping` (reading
  → `VehicleStatusSnapshot`).
- `VehicleAdapter.kt`: settings, the background reader (`VehicleAdapter`), the sources (`BydVehicleDataSource`
  wraps BYD's existing reader), and `VehicleStatusSources`, which picks BYD or the adapter for each connection.
- `Elm327.kt`, `Slcan.kt`: the two protocols. `SerialLinks.kt`: Bluetooth SPP and USB CDC-ACM.
- `CarPlayHostActivity` now asks `VehicleStatusSources` for `vehicleStatusEnabled`, the charging connectors
  and the provider, and starts the reader when the CarPlay screen opens.
- Tests: `VehicleAdapterProtocolTest` (shared), `VehicleAdapterSettingsTest` (common).

## Read-only, by construction

- **OBD-II** works by request and answer, so the adapter does put request frames on the diagnostic bus.
  `Elm327Requests` allows only services 01 (current data), 09 (vehicle information) and 22 (read data by
  identifier), plus a fixed list of adapter set-up commands (`ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATAT1`,
  `ATSP0`, `ATI`) and `ATSH` with an 11-bit id. A signal map asking for anything else (04 clear codes, 10
  session, 11 reset, 14, 27, 2E write, 2F, 31 routines) is rejected when it is saved, and `Elm327.command()`
  refuses it again before sending.
- **CAN** adapters are opened with slcan's `L` (listen-only), never `O`. In listen-only mode the CAN controller
  does not acknowledge other nodes' frames or send any of its own. `SlcanCommands` allows only `C`, `S0`–`S8`,
  `L` and `V`. If the adapter refuses `L`, Tarmac reports it and does not open the channel at all.
- This follows the rules in [CAR_CONTROLS.md](CAR_CONTROLS.md#safety-rules-for-any-write-path); there is no
  write path here.

## Standard PIDs

Without a map, an OBD-II adapter is asked only for PID `01 5B` (hybrid/EV battery pack remaining life,
A × 100 / 255 %), when the car's PID bitmaps list it. Many hybrids answer it; most battery-electric cars do
not, and they need their own mode 22 PIDs from a map. OBD-II has no standard gear PID that tells reverse
apart, so the gear always comes from the map.

## Signal map format

```json
{
  "name": "My car",
  "bitrate": 500000,
  "obd": [
    { "signal": "soc", "header": "7E4", "request": "220101", "byte": 4, "length": 1, "scale": 0.5 },
    { "signal": "gear", "header": "7E1", "request": "22E001", "byte": 0,
      "values": { "1": "P", "2": "R", "3": "N", "4": "D" } }
  ],
  "can": [
    { "signal": "gear", "id": "0x123", "start": 4, "length": 3, "endian": "little",
      "values": { "0": "P", "1": "R", "2": "N", "3": "D" } },
    { "signal": "soc", "id": "0x456", "start": 7, "length": 10, "endian": "big", "scale": 0.1 }
  ]
}
```

The ids, PIDs and bit positions above are **placeholders that show the format**, not values for any real
car. Take real ones from the car's DBC file (for example [opendbc](https://github.com/commaai/opendbc)) or a
community PID list for the model.

- `signal`: `soc` (%), `range_km`, `charging`, `gear`, `speed_kmh`, `fuel_pct` (fuel tank, %), `odometer_km`.
  `fuel_pct` and `odometer_km` go only to CarPlay Ultra's Vehicle app ([CARPLAY_ULTRA.md](CARPLAY_ULTRA.md#car-data-in-the-vehicle-app));
  the standard OBD-II PIDs are `012F` (A × 100 / 255) and `01A6` (four bytes, ÷ 10), where the car supports them.
- Value = raw × `scale` + `offset`. `gear` needs `values` (raw → `P`, `R`, `N`, `D`). `charging` is true for
  any raw value except 0, or only for the raw values in `charging_values`.
- OBD entries: `header` is the 11-bit CAN id the request goes to (omit it for the broadcast `7DF`),
  `request` is service and PID in hex, `byte` counts from the first data byte after the echoed service and
  PID (Torque's A = 0, B = 1), `length` is 1–4 bytes big-endian, `signed` optional.
- CAN entries follow DBC numbering: `start` is the start bit, `length` in bits, `endian` `little` (Intel,
  `@1`, start = least significant bit) or `big` (Motorola, `@0`, start = most significant bit), `extended`
  for 29-bit ids (implied for ids above `0x7FF`), `signed` optional. `bitrate` must be an slcan rate (10k to 1M).

Gear and speed are read every cycle (about every 200 ms per OBD request, every frame on CAN); the rest is
read about every five seconds. A gear older than two seconds counts as unknown, so a lost adapter can never
leave the reversing camera switch stuck.

## First connection of a drive

The iPhone decides at identification whether the car is an EV, and an OBD-II adapter takes several seconds
to find the car's protocol. So once a given adapter setup has read the battery, Tarmac declares the EV at the
next identification before the first live reading. It then sends only live readings: a status update goes out
as soon as one exists, and none before. Changing the adapter, its Bluetooth device or its map clears that.

## Not verified without hardware

None of this has run against a real adapter or car. The protocol drivers are tested against scripted
adapter answers only. Specifically unverified:

- ELM327 clones vary. Some ignore `ATS0` or answer `ATZ` slowly. The parser strips spaces and waits up to 5 s
  for the reset and 15 s for the protocol search, but a clone that never prints the `>` prompt will not work.
- USB support covers CDC-ACM only (STN chips, CANable's slcan firmware). FTDI, CH340, CP210x and PL2303
  adapters, which most cheap USB ELM327s use, need drivers Tarmac does not have. The baud rate alternates
  between 38400 and 115200 on each retry.
- USB permission is per plug-in on Android. A head unit that cuts USB power at every start asks again on
  every drive. Tarmac's USB auto-confirm accessibility service may answer that dialog. This is untested for
  this device class.
- Whether the CANable firmware on a given adapter accepts `L`. The original slcan firmware does. If it
  refuses, Tarmac stays off the bus by design.
- Whether Apple Maps uses the estimated range (charge × range at full charge) the same way as a range the
  car reports.
