# Dashboard map on a second screen (any head unit)

Tarmac has long put CarPlay's dashboard map (stream 111, `maps:/car/instrumentcluster/map`) on BYD and
Geely instrument clusters. Those routes match measured display names and sizes and never pick a screen
by guesswork. This feature adds the same map to **any screen that Android reports as a second display**,
for head units where the built-in detection finds nothing.

## Where it is

Settings → Advanced → **CarPlay map on dashboard (experimental)** → **Dashboard screen**.

The row appears only when all of these hold:

- the built-in detection (DiLink 3/4/5/5.1, Geely FX11) finds no cluster;
- neither BYD route that owns the cluster is on: ADB task routing or the platform-21 route
  (`AirPlayPersistence.loadAdbClusterEnabled` / `loadLegacyClusterEnabled`), and the head unit is not a
  verified DiLink 5.1;
- `DisplayManager` lists at least one screen that is not the main display, is not private to another app
  (`FLAG_PRIVATE`), is valid and has a short edge of at least 200 px. A screen chosen earlier stays in the
  list as "(not connected)" so it can be unset.

Tarmac never chooses a screen by itself. To Android a passenger or rear screen looks the same as a
cluster, and a moving map on a passenger screen is not what the driver asked for.

## How it shows

| Choice | What Tarmac does |
|---|---|
| **Presentation** (default) | An Android `Presentation` on that display: the same window type as the BYD and Geely routes (`ClusterMapPresentation`, its generic full-panel layout). |
| **App window** | `SecondDisplayMapActivity`, started with `ActivityOptions.setLaunchDisplayId` (Android 8.0 and later). For screens that show no presentation window. The window is `FLAG_NOT_FOCUSABLE`, so wheel keys and touch stay with CarPlay on the main screen, and it closes itself if Android moves it to another display (for example when the screen disconnects), so the map never lands on the centre screen. |

Both routes use the stream that `clusterDisplayConfig()` sizes for that display: 83 % of the panel by
default, the size presets and the car-marker sliders apply. BYD's small-window navi controls and its
"only in small and full navi" pause are hidden there, because they read BYD's cluster state.

The choice is saved by display name and size, not by display id, because ids change when a screen
reconnects. A change applies at the next connection ("Reconnect now" bar).

## Code

- `common/.../SecondDisplayMap.kt`: the choice (`SecondDisplayMap`), the app-window bridge
  (`SecondDisplayMapOutput`) and `SecondDisplayMapActivity`.
- `ClusterMapPresentation.findDisplay()` is now `builtInDisplay() ?: SecondDisplayMap.find()`. Every
  caller (stream sizing, the display listener, map-zoom eligibility, the settings card) sees the chosen
  screen the same way it sees a BYD cluster.
- `CarPlayHostActivity.ensureClusterPresentation()` hands the chosen screen to `ensureSecondDisplayWindow()`
  when the app-window route is selected; `dismissClusterPresentation()`, the stream-state updates and the
  turn-card overlay reach it too.
- Tests: `SecondDisplayMapTest`.

## Not verified without hardware

Only Robolectric covers this so far. It has not run on a head unit with a real second display. Open
questions per head unit:

- Whether the vendor exposes the cluster as a public display at all. Many aftermarket units drive their
  cluster through a CAN box or a separate ECU, and then no display appears (see
  [OEM_CLUSTER.md](OEM_CLUSTER.md)).
- Whether that display's owner lets a third-party `Presentation` or activity draw there. Android allows
  both on public displays. Some vendors keep their own layer on top, as Geely does until its DIM service
  is asked to project.
- Whether the panel scales a smaller stream up, as BYD's do. If the map looks soft, choose the 100 % size.
