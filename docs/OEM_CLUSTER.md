# CarPlay's dashboard map on factory clusters with an aftermarket head unit

**Question:** with an aftermarket Android head unit in place of the factory one, can CarPlay's dashboard
map reach the car's own instrument cluster?

**Short answer:** the **video map** only reaches a cluster that the head unit itself drives as a display.
A factory cluster that is its own ECU never takes video from an aftermarket unit. At most it takes
**turn-by-turn data** (arrow, distance, maybe the street name), and only on cars whose CAN-box protocol
carries navigation messages. Tarmac can now offer that data as AMap's standard guidance broadcast, which
many Chinese-market head units already forward to the CAN box. Whether it reaches the cluster depends on
the head unit's receiver and on the car.

| Route | Map video | Turn arrows and distance | What it needs | In Tarmac |
|---|---|---|---|---|
| Cluster driven by the head unit (Android second display) | Yes | Yes (Tarmac's own turn card, over the map) | `DisplayManager` lists the cluster as a display Tarmac may use | Yes: [SECOND_DISPLAY_MAP.md](SECOND_DISPLAY_MAP.md) |
| Factory cluster behind a CAN box (Raise, Hiworld and similar) | No | Sometimes, car- and protocol-specific | A head-unit service that forwards AMap's guidance broadcast to the CAN box, and navigation messages in that car's CAN-box protocol | Yes, the broadcast: Settings → Navigation → Send turns to the car's cluster and HUD |
| Factory cluster as a separate ECU, no CAN-box navigation support | No | No | The car maker's own navigation messages or video link | No |

## 1. Android-driven clusters: a second display

Some head units drive the cluster panel themselves. This is common in Chinese "digital cockpit" retrofit
kits, where an aftermarket LCD cluster hangs off a second LVDS or HDMI output of the same Android board,
and in cars whose factory head unit and cluster share one SoC (BYD DiLink and Geely's ECARX units, which
Tarmac already supports). Android then lists the panel as another display, and any app may show a
`Presentation` on it, or an activity started with `ActivityOptions.setLaunchDisplayId`, unless the vendor
makes it private.

This is the only case where the real CarPlay map, stream 111 as Apple Maps renders it for a cluster, can
appear on the dashboard. Tarmac's Settings → Advanced → CarPlay map on dashboard → **Dashboard screen** offers
every such display. The driver picks one, because Android cannot tell a cluster from a passenger screen.

Whether a given unit qualifies shows in the diagnostic report: `allDisplays=` lists every display Android
reports, and `secondDisplay=` says what Tarmac chose.

## 2. CAN-box navigation passthrough (Raise, Hiworld and similar)

Aftermarket units talk to the car through a CAN box ("canbus decoder") from vendors such as Raise (RZC),
Hiworld (HW), XBS or Simple Soft. The box sits between the car's CAN bus and the head unit's MCU, which talks
to it over a UART protocol specific to the box vendor and the car model. The head unit's system app (on
FYT/SYU units, `com.syu.ms`; other platforms have their own) decodes it for wheel keys, doors, climate and
the reversing signal.

For some car models these protocols can also carry the other direction: navigation data the box turns into
the factory head unit's own CAN messages, so the factory cluster shows its turn arrow, distance and perhaps
the street. That can only work on cars whose cluster had a navigation field for the factory sat-nav. Whether
a model's box supports it, and in what message format, depends on the box firmware for that car. It is not
publicly documented as a whole.

What Tarmac has: CarPlay's turn-by-turn data. Apple Maps sends iAP2 RouteGuidanceUpdate and
RouteGuidanceManeuverUpdate, and Tarmac already decodes them for BYD's HUD and cluster (`BydHudRouteState`,
`ClusterTurnGuidance`, `CarPlayGlance`).

### AMap's standard guidance broadcast

Many Chinese-market OEM and aftermarket head units already solve step 1 below for one app: the stock AMap
(高德) car navigation. It announces every turn with the broadcast `AUTONAVI_STANDARD_BROADCAST_SEND`
(`KEY_TYPE` 10001), and the head unit's CAN-box or cluster service listens for it and forwards the arrow,
distance and street to the factory cluster or HUD. BYD's own AMap adapter is one such receiver, and Tarmac has
fed it for a while (`BydClusterBridge`).

**Send turns to the car's cluster and HUD** (Settings → Navigation, off by default, on head units without
BYD's adapter) sends CarPlay's guidance in the same form, from `CarPlayGlance`, through
`hud/CarGuidanceBroadcast.kt`:

- `KEY_TYPE` 10001 with `NEW_ICON`, `ROUNG_ABOUT_NUM`, `SEG_REMAIN_DIS`, `NEXT_ROAD_NAME`, `ROUTE_REMAIN_DIS`,
  `ROUTE_REMAIN_TIME`, `TYPE` 0, `EXTRA_STATE` 0. The icons are mapped from Apple's maneuver types by the same
  table as on BYD (`BydClusterFrame`).
- At once on each change, then every 5 s as a keepalive. `KEY_TYPE` 10019 with `EXTRA_STATE` 9 ("navigation
  ended") goes out when the route ends, the session ends or the switch goes off.
- No target package and the background-receiver flag, as stock navigation clients send it, so any receiver on
  the head unit gets it. Without a receiver nothing happens.
- BYD is unchanged. Where BYD's adapter (`com.byd.amapservice`, `com.example.amapservice`) is installed, the
  switch is hidden and the BYD navigation card's switch still drives `BydClusterBridge`.

Not sent: the BYD-only `IS_BYD_MAP` flags, and the other fields AMap's broadcast can carry (the older `ICON`,
current road, speed cameras, lanes). A receiver that reads only those shows nothing. Which receivers exist on
a given firmware, and which fields they read, has to be checked per head unit. A separate session is listing
the receivers for this action in TEYES CC4 firmware. None of this has run on a head unit other than BYD's.

### Writing the CAN-box protocol directly

Without such a receiver, the rest of this section applies. What is missing, per car:

1. The vendor API that lets a third-party app ask the MCU to send navigation messages to the CAN box. The
   UART belongs to the MCU and the vendor's system app. An app cannot write to it directly.
2. The navigation message set of that car's CAN-box protocol, and a mapping from CarPlay's maneuver types
   to the cluster's arrow codes (as `BydManeuverCodes` / `BydFactoryTurnCode` do for BYD).
3. The write-path rules in [CAR_CONTROLS.md](CAR_CONTROLS.md#safety-rules-for-any-write-path). This is a write
   onto the car's bus, even though the CAN box sends it. It has to be limited to the infotainment messages
   the factory head unit sent, on an allow-list, and stopped on any error.

So this route is possible car by car, with the unit and the car at hand, and is not something to build
blind. It would plug in beside `NavigationOutputWorker`, the way the BYD outputs do. The broadcast above
needs none of this, because the head unit's own service does the CAN-box side.

## 3. Clusters that are separate ECUs

Most factory clusters are their own ECU with their own graphics. They draw the map or navigation field from
data, not from a video signal, or take video only from the factory navigation ECU over a serialized
link (LVDS, APIX, GMSL and similar). That link is made by the car maker for its own head unit, often
with link-level handshakes. An aftermarket head unit has no matching serializer and does not speak the
protocol, so **no video map can reach such a cluster**.

What can reach it is the turn-by-turn data the factory head unit used to put on the bus, and only through
route 2, when the CAN box for that car emulates those messages (for example fed by the AMap broadcast). Many cars' clusters show nothing from an
aftermarket unit at all.

## What would change this

- **Cars with factory CarPlay already** show Apple's own cluster content (the same stream 111) when the car
  maker integrated it. A replacement unit loses that integration and cannot restore it.
- **A head unit that drives the cluster** (route 1) is the practical way to get the map on the dashboard
  with aftermarket hardware.

## Not verified without hardware

This page is research, not measurement. None of the three routes has been tried on a car with an
aftermarket unit. Route 1's code path is the same one BYD and Geely clusters use, and is unit-tested.
Route 2 describes how these boxes are integrated in general; I have not checked any box vendor's
navigation support for a specific car. Confirm it with the vendor for the car in question before building on it.
